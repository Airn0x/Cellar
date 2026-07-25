package main

import (
	"archive/tar"
	"compress/gzip"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/ulikunitz/xz"
)

// Pure-Go extraction and export. The engine must run where no external
// tar exists (inside an Android app), and Android forbids hard links in
// app data anyway — so hardlink entries are materialized as file copies
// and suid-style modes are normalized to keep files owner-accessible
// (see docs/FIELD-NOTES.md #3 and #4).

// openTarball wraps a rootfs tarball in the right decompressor based on
// its magic bytes (xz and gzip are what image servers actually ship;
// anything else is assumed to be plain tar).
func openTarball(path string) (io.ReadCloser, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	magic := make([]byte, 6)
	n, _ := io.ReadFull(f, magic)
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		f.Close()
		return nil, err
	}
	switch {
	case n >= 6 && string(magic) == "\xfd7zXZ\x00":
		xr, err := xz.NewReader(f)
		if err != nil {
			f.Close()
			return nil, fmt.Errorf("xz: %w", err)
		}
		return struct {
			io.Reader
			io.Closer
		}{xr, f}, nil
	case n >= 2 && magic[0] == 0x1f && magic[1] == 0x8b:
		gr, err := gzip.NewReader(f)
		if err != nil {
			f.Close()
			return nil, fmt.Errorf("gzip: %w", err)
		}
		return struct {
			io.Reader
			io.Closer
		}{gr, f}, nil
	}
	return f, nil
}

// safeJoin resolves a tar member name inside dest. Any ".." element is
// rejected outright — checking BEFORE rooting, because Clean("/../x")
// silently clamps to "/x" and would hide the hostile intent. Leading
// slashes are stripped (standard tar behavior).
func safeJoin(dest, name string) (string, error) {
	for _, part := range strings.Split(filepath.ToSlash(name), "/") {
		if part == ".." {
			return "", fmt.Errorf("archive entry escapes destination: %q", name)
		}
	}
	clean := filepath.Clean("/" + name) // one canonical, rooted form
	if clean == "/" {
		return "", nil // the archive's root dir entry; nothing to do
	}
	return filepath.Join(dest, strings.TrimPrefix(clean, "/")), nil
}

// normPerm gives the owner rw (files) or rwx (dirs) on top of the
// archive's mode — suid-style modes like 4711 otherwise leave files we
// own unreadable, which breaks export and rm.
func normPerm(mode os.FileMode, isDir bool) os.FileMode {
	perm := mode.Perm() | 0o600
	if isDir {
		perm |= 0o700
	}
	return perm
}

// isTopLevelDev reports whether a member is dev/* at the archive root —
// device nodes can't exist in app storage and proot binds the real /dev.
func isTopLevelDev(name string) bool {
	rel := strings.TrimPrefix(filepath.Clean("/"+name), "/")
	return rel == "dev" || strings.HasPrefix(rel, "dev/")
}

// extractTar unpacks a rootfs tarball into dest with no external tools.
func extractTar(tarball, dest string) error {
	if err := os.MkdirAll(dest, 0o700); err != nil {
		return err
	}
	rc, err := openTarball(tarball)
	if err != nil {
		return err
	}
	defer rc.Close()

	tr := tar.NewReader(rc)
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return fmt.Errorf("tar: %w", err)
		}
		if isTopLevelDev(hdr.Name) {
			continue
		}
		path, err := safeJoin(dest, hdr.Name)
		if err != nil {
			return err
		}
		if path == "" {
			continue
		}

		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(path, normPerm(hdr.FileInfo().Mode(), true)); err != nil {
				return err
			}
			// MkdirAll won't tighten/loosen an existing dir; make sure
			// the recorded mode (plus owner rwx) actually applies
			os.Chmod(path, normPerm(hdr.FileInfo().Mode(), true))

		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
				return err
			}
			os.Remove(path) // never write through a pre-existing symlink
			f, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, normPerm(hdr.FileInfo().Mode(), false))
			if err != nil {
				return err
			}
			_, cpErr := io.Copy(f, tr)
			clErr := f.Close()
			if cpErr != nil || clErr != nil {
				return fmt.Errorf("write %s: %w", hdr.Name, errors.Join(cpErr, clErr))
			}

		case tar.TypeSymlink:
			if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
				return err
			}
			os.Remove(path)
			if err := os.Symlink(hdr.Linkname, path); err != nil {
				return err
			}

		case tar.TypeLink:
			// Android denies link(); copy the already-extracted target.
			// (Hardlink members always follow their target in practice.)
			target, err := safeJoin(dest, hdr.Linkname)
			if err != nil || target == "" {
				return fmt.Errorf("hardlink %s -> %q: bad target", hdr.Name, hdr.Linkname)
			}
			if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
				return err
			}
			os.Remove(path)
			if err := copyFile(target, path); err != nil {
				return fmt.Errorf("hardlink %s -> %s: %w", hdr.Name, hdr.Linkname, err)
			}

		default:
			// char/block/fifo/socket: impossible without root, unneeded
			// under proot. Skip silently, like proot-distro does.
		}
	}
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	info, err := in.Stat()
	if err != nil {
		return err
	}
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_EXCL|os.O_WRONLY, normPerm(info.Mode(), false))
	if err != nil {
		return err
	}
	_, cpErr := io.Copy(out, in)
	clErr := out.Close()
	return errors.Join(cpErr, clErr)
}

// exportMachine writes machineDir's meta.json + rootfs as a tar.gz.
// Symlinks are stored as symlinks (Lstat — a link's target is guest
// data and must never be followed on the host).
func exportMachine(name, dest string) error {
	out, err := os.OpenFile(dest, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	gw := gzip.NewWriter(out)
	tw := tar.NewWriter(gw)

	base := machineDir(name)
	walkErr := filepath.WalkDir(base, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(base, path)
		if err != nil || rel == "." {
			return err
		}
		// state files stay home; the archive is meta + rootfs only
		if rel == "init.pid" || rel == "logs" {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		info, err := d.Info() // Lstat semantics via DirEntry
		if err != nil {
			return err
		}
		link := ""
		if info.Mode()&os.ModeSymlink != 0 {
			if link, err = os.Readlink(path); err != nil {
				return err
			}
		} else if !info.Mode().IsRegular() && !d.IsDir() {
			return nil // sockets/fifos left over from guests: skip
		}
		hdr, err := tar.FileInfoHeader(info, link)
		if err != nil {
			return err
		}
		hdr.Name = filepath.ToSlash(rel)
		if d.IsDir() {
			hdr.Name += "/"
		}
		if err := tw.WriteHeader(hdr); err != nil {
			return err
		}
		if info.Mode().IsRegular() {
			f, err := os.Open(path)
			if err != nil {
				return err
			}
			_, cpErr := io.Copy(tw, f)
			f.Close()
			if cpErr != nil {
				return cpErr
			}
		}
		return nil
	})

	twErr := tw.Close()
	gwErr := gw.Close()
	outErr := out.Close()
	if err := errors.Join(walkErr, errors.Join(twErr, errors.Join(gwErr, outErr))); err != nil {
		os.Remove(dest)
		return err
	}
	return nil
}
