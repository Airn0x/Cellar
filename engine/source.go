package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"time"
)

// Rootfs images come from the linuxcontainers.org image server (the same
// community builds incus uses): /images/<distro>/<release>/<arch>/default/
// contains dated build dirs, each with rootfs.tar.xz + SHA256SUMS.

var defaultReleases = map[string]string{
	"alpine": "3.24",
	"debian": "trixie",
	"ubuntu": "noble",
}

func imageServer() string {
	if s := os.Getenv("CELLAR_IMAGE_SERVER"); s != "" {
		return strings.TrimRight(s, "/")
	}
	return "https://images.linuxcontainers.org"
}

func hostArch() (string, error) {
	switch runtime.GOARCH {
	case "arm64":
		return "arm64", nil
	case "amd64":
		return "amd64", nil
	}
	return "", fmt.Errorf("unsupported architecture %s", runtime.GOARCH)
}

var indexClient = &http.Client{Timeout: 30 * time.Second}

// buildDirRe matches dated build dirs like href="20260724_05%3A24/".
var buildDirRe = regexp.MustCompile(`href="(2\d{7}_\d{2}(?:%3A|:)\d{2})/"`)

func httpGetString(u string) (string, error) {
	resp, err := indexClient.Get(u)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("GET %s: %s", u, resp.Status)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	return string(b), err
}

// latestBuild returns the URL-encoded name of the newest build dir in an index page.
func latestBuild(indexHTML string) (string, error) {
	matches := buildDirRe.FindAllStringSubmatch(indexHTML, -1)
	if len(matches) == 0 {
		return "", fmt.Errorf("no builds found in image index")
	}
	// Dirs are named YYYYMMDD_HH:MM so lexical order is chronological;
	// the server lists them ascending, but don't rely on that.
	best := ""
	for _, m := range matches {
		name := strings.ReplaceAll(m[1], ":", "%3A")
		if name > best {
			best = name
		}
	}
	return best, nil
}

// sumFor extracts the sha256 for a filename from SHA256SUMS content.
func sumFor(sums, filename string) (string, error) {
	for _, line := range strings.Split(sums, "\n") {
		fields := strings.Fields(line)
		if len(fields) == 2 && fields[1] == filename && len(fields[0]) == 64 {
			return fields[0], nil
		}
	}
	return "", fmt.Errorf("%s not found in SHA256SUMS", filename)
}

type image struct {
	distro, release, build string
	rootfsURL, sha256      string
}

func resolveImage(distro, release string) (*image, error) {
	arch, err := hostArch()
	if err != nil {
		return nil, err
	}
	if release == "" {
		release = defaultReleases[distro]
	}
	if release == "" {
		return nil, fmt.Errorf("unknown distro %q (have: alpine, debian, ubuntu; or pass --release)", distro)
	}
	base := fmt.Sprintf("%s/images/%s/%s/%s/default/", imageServer(), distro, release, arch)
	idx, err := httpGetString(base)
	if err != nil {
		return nil, fmt.Errorf("image index: %w", err)
	}
	build, err := latestBuild(idx)
	if err != nil {
		return nil, fmt.Errorf("%w (distro %q release %q arch %s — check %s)", err, distro, release, arch, base)
	}
	sums, err := httpGetString(base + build + "/SHA256SUMS")
	if err != nil {
		return nil, fmt.Errorf("SHA256SUMS: %w", err)
	}
	sum, err := sumFor(sums, "rootfs.tar.xz")
	if err != nil {
		return nil, err
	}
	return &image{
		distro: distro, release: release, build: build,
		rootfsURL: base + build + "/rootfs.tar.xz",
		sha256:    sum,
	}, nil
}

// download fetches img.rootfsURL into the cache (verifying sha256) and
// returns the local path. Cached files that already match are reused.
func download(img *image) (string, error) {
	if err := os.MkdirAll(cacheDir(), 0o700); err != nil {
		return "", err
	}
	decoded, _ := url.PathUnescape(img.build)
	local := filepath.Join(cacheDir(),
		fmt.Sprintf("%s-%s-%s-rootfs.tar.xz", img.distro, img.release, strings.ReplaceAll(decoded, ":", "")))

	if ok, _ := fileMatchesSum(local, img.sha256); ok {
		fmt.Fprintf(os.Stderr, "using cached %s\n", filepath.Base(local))
		return local, nil
	}

	fmt.Fprintf(os.Stderr, "downloading %s %s (%s) ...\n", img.distro, img.release, decoded)
	resp, err := (&http.Client{}).Get(img.rootfsURL)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("GET %s: %s", img.rootfsURL, resp.Status)
	}

	tmp := local + ".part"
	f, err := os.Create(tmp)
	if err != nil {
		return "", err
	}
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(f, h), resp.Body)
	closeErr := f.Close()
	if err != nil || closeErr != nil {
		os.Remove(tmp)
		return "", fmt.Errorf("download failed after %d bytes: %v", n, cmpErr(err, closeErr))
	}
	got := hex.EncodeToString(h.Sum(nil))
	if got != img.sha256 {
		os.Remove(tmp)
		return "", fmt.Errorf("sha256 mismatch: got %s want %s", got, img.sha256)
	}
	if err := os.Rename(tmp, local); err != nil {
		return "", err
	}
	fmt.Fprintf(os.Stderr, "downloaded %.1f MB, sha256 ok\n", float64(n)/1e6)
	return local, nil
}

func cmpErr(a, b error) error {
	if a != nil {
		return a
	}
	return b
}

func fileMatchesSum(path, want string) (bool, error) {
	f, err := os.Open(path)
	if err != nil {
		return false, err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return false, err
	}
	return hex.EncodeToString(h.Sum(nil)) == want, nil
}

// extract unpacks a rootfs tarball. Runs the system tar (rather than
// archive/tar) so xz/gz detection and long names are its problem;
// device nodes are excluded because mknod is impossible without root
// and proot binds the real /dev anyway. When proot is available, tar
// runs under it: Android denies hard links in app data, and proot's
// --link2symlink converts link() into symlinks (rootfs tarballs are
// full of hardlinks — Debian's perl alone has several). This is
// proot-distro's proven extraction recipe.
func extract(tarball, dest string) error {
	if err := os.MkdirAll(dest, 0o700); err != nil {
		return err
	}
	tarArgs := []string{
		"--exclude=dev/*", "--exclude=./dev/*",
		"--warning=no-unknown-keyword",
		"-xf", tarball, "-C", dest,
	}
	var cmd *exec.Cmd
	if proot, err := findProot(); err == nil {
		cmd = exec.Command(proot, append([]string{"--link2symlink", "-0", "tar"}, tarArgs...)...)
		if t := os.Getenv("TMPDIR"); t != "" {
			cmd.Env = append(os.Environ(), "PROOT_TMP_DIR="+t)
		}
	} else {
		cmd = exec.Command("tar", tarArgs...) // plain Linux dev box
	}
	cmd.Stdout = os.Stderr
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("tar extract: %w", err)
	}
	return finishRootfs(dest)
}

// finishRootfs makes a freshly extracted rootfs usable under proot.
func finishRootfs(root string) error {
	for _, d := range []string{"dev", "proc", "sys", "tmp", "run", "root", "etc"} {
		if err := os.MkdirAll(filepath.Join(root, d), 0o755); err != nil {
			return err
		}
	}
	// Normalize owner access: suid-style modes like 4711 (e.g. Alpine's
	// bbsuid) leave files we own unreadable, which breaks export and rm.
	// suid grants nothing under proot, so owner rw costs nothing.
	err := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.Type()&os.ModeSymlink != 0 {
			return nil // unreadable entry or symlink: chmod would follow the link; skip
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		perm := info.Mode().Perm()
		want := perm | 0o600
		if d.IsDir() {
			want = perm | 0o700
		}
		if want != perm {
			os.Chmod(path, want)
		}
		return nil
	})
	if err != nil {
		return err
	}
	// resolv.conf is often a broken symlink to systemd-resolved; replace it.
	rc := filepath.Join(root, "etc", "resolv.conf")
	os.Remove(rc)
	if err := os.WriteFile(rc, []byte("nameserver 1.1.1.1\nnameserver 8.8.8.8\n"), 0o644); err != nil {
		return err
	}
	hosts := filepath.Join(root, "etc", "hosts")
	if _, err := os.Stat(hosts); os.IsNotExist(err) {
		if err := os.WriteFile(hosts, []byte("127.0.0.1 localhost\n"), 0o644); err != nil {
			return err
		}
	}
	return nil
}
