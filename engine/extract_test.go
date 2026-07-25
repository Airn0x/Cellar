package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/ulikunitz/xz"
)

type entry struct {
	name, link, body string
	typeflag         byte
	mode             int64
}

func buildTar(t *testing.T, entries []entry, compress string) string {
	t.Helper()
	var buf bytes.Buffer
	var w io.WriteCloser
	switch compress {
	case "gz":
		w = gzip.NewWriter(&buf)
	case "xz":
		xw, err := xz.NewWriter(&buf)
		if err != nil {
			t.Fatal(err)
		}
		w = xw
	default:
		w = struct {
			io.Writer
			io.Closer
		}{&buf, io.NopCloser(nil)}
	}
	tw := tar.NewWriter(w)
	for _, e := range entries {
		hdr := &tar.Header{Name: e.name, Mode: e.mode, Typeflag: e.typeflag, Linkname: e.link}
		if e.typeflag == tar.TypeReg {
			hdr.Size = int64(len(e.body))
		}
		if err := tw.WriteHeader(hdr); err != nil {
			t.Fatal(err)
		}
		if e.typeflag == tar.TypeReg {
			if _, err := tw.Write([]byte(e.body)); err != nil {
				t.Fatal(err)
			}
		}
	}
	if err := tw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "fixture.tar."+compress)
	if err := os.WriteFile(path, buf.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

// The realistic rootfs shape: dirs, a suid binary, a hardlink to it,
// a symlink, device nodes, and a nested (non-top-level) dev dir.
var rootfsEntries = []entry{
	{name: "./", typeflag: tar.TypeDir, mode: 0o755},
	{name: "./bin/", typeflag: tar.TypeDir, mode: 0o755},
	{name: "./bin/tool", typeflag: tar.TypeReg, mode: 0o4711, body: "#!/bin/sh\necho hi\n"},
	{name: "./bin/tool-link", link: "./bin/tool", typeflag: tar.TypeLink, mode: 0o4711},
	{name: "./bin/sh", link: "tool", typeflag: tar.TypeSymlink, mode: 0o777},
	{name: "./dev/", typeflag: tar.TypeDir, mode: 0o755},
	{name: "./dev/null", typeflag: tar.TypeChar, mode: 0o666},
	{name: "./srv/dev/", typeflag: tar.TypeDir, mode: 0o755},
	{name: "./srv/dev/keep.txt", typeflag: tar.TypeReg, mode: 0o644, body: "nested dev dirs survive"},
}

func checkExtracted(t *testing.T, dest string) {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(dest, "bin", "tool"))
	if err != nil || !strings.Contains(string(b), "echo hi") {
		t.Fatalf("regular file: %v / %q", err, b)
	}
	info, err := os.Stat(filepath.Join(dest, "bin", "tool"))
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm()&0o600 != 0o600 {
		t.Fatalf("suid mode not normalized: %v", info.Mode())
	}
	if info.Mode().Perm()&0o100 == 0 {
		t.Fatalf("exec bit lost: %v", info.Mode())
	}
	lb, err := os.ReadFile(filepath.Join(dest, "bin", "tool-link"))
	if err != nil || string(lb) != string(b) {
		t.Fatalf("hardlink not copied: %v / %q", err, lb)
	}
	li, err := os.Lstat(filepath.Join(dest, "bin", "sh"))
	if err != nil || li.Mode()&os.ModeSymlink == 0 {
		t.Fatalf("symlink lost: %v %v", err, li)
	}
	if _, err := os.Lstat(filepath.Join(dest, "dev", "null")); !os.IsNotExist(err) {
		t.Fatal("top-level dev content should be skipped")
	}
	if _, err := os.Stat(filepath.Join(dest, "srv", "dev", "keep.txt")); err != nil {
		t.Fatal("nested dev dir was wrongly excluded:", err)
	}
}

func TestExtractTarGzAndXz(t *testing.T) {
	for _, c := range []string{"gz", "xz"} {
		dest := t.TempDir()
		if err := extractTar(buildTar(t, rootfsEntries, c), dest); err != nil {
			t.Fatalf("[%s] %v", c, err)
		}
		checkExtracted(t, dest)
	}
}

func TestExtractRejectsTraversal(t *testing.T) {
	evil := []entry{
		{name: "../escape.txt", typeflag: tar.TypeReg, mode: 0o644, body: "nope"},
	}
	dest := filepath.Join(t.TempDir(), "inner")
	if err := extractTar(buildTar(t, evil, "gz"), dest); err == nil {
		t.Fatal("want error for ../ escape")
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(dest), "escape.txt")); !os.IsNotExist(err) {
		t.Fatal("escape file was written outside dest")
	}
}

func TestExtractNeverWritesThroughSymlink(t *testing.T) {
	// a symlink pointing outside, then a file "through" it
	outside := t.TempDir()
	victim := filepath.Join(outside, "victim.txt")
	evil := []entry{
		{name: "./leak", link: victim, typeflag: tar.TypeSymlink, mode: 0o777},
		{name: "./leak", typeflag: tar.TypeReg, mode: 0o644, body: "overwritten"},
	}
	dest := t.TempDir()
	if err := extractTar(buildTar(t, evil, "gz"), dest); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(victim); !os.IsNotExist(err) {
		t.Fatal("file was written through a planted symlink")
	}
	b, err := os.ReadFile(filepath.Join(dest, "leak"))
	if err != nil || string(b) != "overwritten" {
		t.Fatalf("later entry should replace the symlink in place: %v %q", err, b)
	}
}

func TestExportRoundTrip(t *testing.T) {
	t.Setenv("CELLAR_HOME", t.TempDir())
	if err := os.MkdirAll(rootfsDir("m1"), 0o700); err != nil {
		t.Fatal(err)
	}
	meta := &Meta{Name: "m1", Distro: "alpine", Release: "3.24", Created: "2026-07-25T00:00:00Z"}
	if err := saveMeta(meta); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(rootfsDir("m1"), "hello.txt"), []byte("hi"), 0o640); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("hello.txt", filepath.Join(rootfsDir("m1"), "link")); err != nil {
		t.Fatal(err)
	}
	// state files must stay out of the archive
	if err := os.WriteFile(pidPath("m1"), []byte("42\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	dest := filepath.Join(t.TempDir(), "m1.tar.gz")
	if err := exportMachine("m1", dest); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(dest)
	if err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf("archive perms: %v %v", err, info.Mode())
	}

	back := t.TempDir()
	if err := extractTar(dest, back); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(filepath.Join(back, "rootfs", "hello.txt"))
	if err != nil || string(b) != "hi" {
		t.Fatalf("round trip content: %v %q", err, b)
	}
	if li, err := os.Lstat(filepath.Join(back, "rootfs", "link")); err != nil || li.Mode()&os.ModeSymlink == 0 {
		t.Fatalf("round trip symlink: %v", err)
	}
	if _, err := os.Stat(filepath.Join(back, "meta.json")); err != nil {
		t.Fatal("meta.json missing from export:", err)
	}
	if _, err := os.Lstat(filepath.Join(back, "init.pid")); !os.IsNotExist(err) {
		t.Fatal("pidfile leaked into export")
	}
}
