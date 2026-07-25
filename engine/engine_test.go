package main

import (
	"os"
	"reflect"
	"strings"
	"testing"
)

const indexFixture = `<head><title>Index of /images/debian/trixie/arm64/default/</title></head>
<body>
<h1>Index of /images/debian/trixie/arm64/default/</h1><hr><pre><a href="../">../</a>
<a href="20260722_18%3A21/">20260722_18:21/</a>                                    22-Jul-2026 20:46                   -
<a href="20260723_05%3A24/">20260723_05:24/</a>                                    23-Jul-2026 07:19                   -
<a href="20260724_05%3A24/">20260724_05:24/</a>                                    24-Jul-2026 07:17                   -
</pre><hr></body>
</html>`

func TestLatestBuild(t *testing.T) {
	got, err := latestBuild(indexFixture)
	if err != nil {
		t.Fatal(err)
	}
	if got != "20260724_05%3A24" {
		t.Fatalf("latestBuild = %q, want 20260724_05%%3A24", got)
	}
}

func TestLatestBuildEmpty(t *testing.T) {
	if _, err := latestBuild("<html>nothing here</html>"); err == nil {
		t.Fatal("want error on index with no builds")
	}
}

const sumsFixture = `f12441f46dcb267d6fbb072545b731348091f43d1b5bf27f90fc30e634e5ec01  rootfs.tar.xz
908b05e96d30c72571586c3a3a1aec13f9440b64c24a839ee4da6e1ce50921ec  rootfs.squashfs
23ef37babfd252d7715b3da8fbf6807222f184c5ce00041a1f2e2858b46985ca  build.log`

func TestSumFor(t *testing.T) {
	got, err := sumFor(sumsFixture, "rootfs.tar.xz")
	if err != nil {
		t.Fatal(err)
	}
	if got != "f12441f46dcb267d6fbb072545b731348091f43d1b5bf27f90fc30e634e5ec01" {
		t.Fatalf("wrong sum: %s", got)
	}
	if _, err := sumFor(sumsFixture, "missing.tar"); err == nil {
		t.Fatal("want error for absent file")
	}
}

func TestValidName(t *testing.T) {
	for _, ok := range []string{"dev", "web-1", "a", "agent-runner-01"} {
		if err := validName(ok); err != nil {
			t.Errorf("validName(%q) rejected: %v", ok, err)
		}
	}
	for _, bad := range []string{"", "-x", "UPPER", "a b", "x/y", "../evil", strings.Repeat("a", 40)} {
		if err := validName(bad); err == nil {
			t.Errorf("validName(%q) accepted, want reject", bad)
		}
	}
}

func TestMetaRoundTrip(t *testing.T) {
	t.Setenv("CELLAR_HOME", t.TempDir())
	m := &Meta{Name: "dev", Distro: "alpine", Release: "3.24", Created: "2026-07-24T00:00:00Z", Init: "sleep 1"}
	if err := os.MkdirAll(machineDir("dev"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := saveMeta(m); err != nil {
		t.Fatal(err)
	}
	got, err := loadMeta("dev")
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(m, got) {
		t.Fatalf("round trip mismatch: %+v != %+v", got, m)
	}
	all, err := listMachines()
	if err != nil || len(all) != 1 || all[0].Name != "dev" {
		t.Fatalf("listMachines = %v, %v", all, err)
	}
}

func TestProotArgs(t *testing.T) {
	got := prootArgs("/x/rootfs", []string{"/bin/sh", "-c", "id"})
	want := []string{
		"--kill-on-exit", "--link2symlink", "-0",
		"-r", "/x/rootfs",
		"-b", "/dev", "-b", "/proc", "-b", "/sys",
		"-w", "/root",
		"/bin/sh", "-c", "id",
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("prootArgs:\n got %v\nwant %v", got, want)
	}
}

func TestShQuote(t *testing.T) {
	cases := map[string][]string{
		"'echo' 'hi there'":        {"echo", "hi there"},
		`'it'\''s' 'fine'`:         {"it's", "fine"},
		"'a' '$HOME' '`cmd`' ';x'": {"a", "$HOME", "`cmd`", ";x"},
	}
	for want, in := range cases {
		if got := shQuote(in); got != want {
			t.Errorf("shQuote(%v) = %s, want %s", in, got, want)
		}
	}
}

func TestPopName(t *testing.T) {
	name, rest := popName([]string{"dev", "--distro", "alpine"})
	if name != "dev" || !reflect.DeepEqual(rest, []string{"--distro", "alpine"}) {
		t.Fatalf("popName trailing-flags: %q %v", name, rest)
	}
	// name must be first: a flag value ("K=V") must never be mistaken for it
	name, rest = popName([]string{"-e", "K=V", "dev"})
	if name != "" || !reflect.DeepEqual(rest, []string{"-e", "K=V", "dev"}) {
		t.Fatalf("popName leading-flags: %q %v", name, rest)
	}
	if name, _ := popName([]string{"--json"}); name != "" {
		t.Fatalf("popName flags-only: %q", name)
	}
}

func TestGuestEnvClean(t *testing.T) {
	t.Setenv("LD_PRELOAD", "/system/evil.so")
	env := guestEnv([]string{"FOO=bar"})
	joined := strings.Join(env, "\n")
	if strings.Contains(joined, "LD_PRELOAD") {
		t.Fatal("host LD_PRELOAD leaked into guest env")
	}
	if !strings.Contains(joined, "FOO=bar") || !strings.Contains(joined, "HOME=/root") {
		t.Fatalf("missing expected entries in %v", env)
	}
}
