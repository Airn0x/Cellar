package main

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func findProot() (string, error) {
	if p := os.Getenv("CELLAR_PROOT"); p != "" {
		return p, nil
	}
	p, err := exec.LookPath("proot")
	if err != nil {
		return "", fmt.Errorf("proot not found in PATH (install it, or set CELLAR_PROOT)")
	}
	return p, nil
}

// prootArgs builds the argv (after the proot binary itself) for running
// cmd inside a machine's rootfs. Mirrors proot-distro's proven flag set:
// -0 fakes root so apk/apt work, --link2symlink survives no-hardlink
// filesystems, --kill-on-exit guarantees no orphaned guest processes.
//
// shmDir, when non-empty, is bound over the guest's /dev/shm: Android has
// no /dev/shm, and binding the host /dev masks whatever the rootfs
// shipped — without this, POSIX shared memory fails and takes Python
// multiprocessing, PostgreSQL and friends down with it.
func prootArgs(rootfs, shmDir string, cmd []string) []string {
	args := []string{
		"--kill-on-exit",
		"--link2symlink",
		"-0",
		"-r", rootfs,
		"-b", "/dev",
		"-b", "/proc",
		"-b", "/sys",
	}
	if shmDir != "" {
		args = append(args, "-b", shmDir+":/dev/shm")
	}
	return append(append(args, "-w", "/root"), cmd...)
}

// machineShm returns the machine's host-side /dev/shm directory, creating
// it on demand. An unwritable path is not fatal — the bind is simply
// skipped (better a guest without shm than a machine that won't start).
func machineShm(name string) string {
	dir := filepath.Join(machineDir(name), "shm")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return ""
	}
	return dir
}

// guestEnv is the clean environment for the proot process (and therefore
// the guest). The host environment is deliberately not inherited: Termux
// paths and LD_PRELOAD leak breakage into guests.
func guestEnv(extra []string) []string {
	term := os.Getenv("TERM")
	if term == "" {
		term = "xterm-256color"
	}
	env := []string{
		"HOME=/root",
		"USER=root",
		"PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
		"TERM=" + term,
		"LANG=C.UTF-8",
		"TMPDIR=/tmp",
	}
	// proot itself reads these from its own environment.
	if t := os.Getenv("TMPDIR"); t != "" {
		env = append(env, "PROOT_TMP_DIR="+t)
	}
	// Inside an app, proot cannot extract its embedded loader (W^X blocks
	// exec from writable storage) — the host sets PROOT_LOADER to a
	// jniLib path, and the clean env must not strip it.
	for _, k := range []string{"PROOT_LOADER", "PROOT_LOADER_32"} {
		if v := os.Getenv(k); v != "" {
			env = append(env, k+"="+v)
		}
	}
	// proot's seccomp fast path races with node/npm process management
	// ("Exit handler never called!") — trade speed for correctness.
	// CELLAR_SECCOMP=1 re-enables the fast path for benchmarking.
	if os.Getenv("CELLAR_SECCOMP") != "1" {
		env = append(env, "PROOT_NO_SECCOMP=1")
	}
	return append(env, extra...)
}

// shQuote makes one argv safe to pass through /bin/sh -c.
func shQuote(args []string) string {
	quoted := make([]string, len(args))
	for i, a := range args {
		quoted[i] = "'" + strings.ReplaceAll(a, "'", `'\''`) + "'"
	}
	return strings.Join(quoted, " ")
}

// shellCommand turns a user argv into one shell command string: a single
// argument is taken as-is (shell syntax allowed), multiple arguments are
// quoted so they survive the sh -c round trip verbatim.
func shellCommand(argv []string) string {
	if len(argv) == 1 {
		return argv[0]
	}
	return shQuote(argv)
}

// runInMachine runs argv inside the machine with stdio attached and
// returns its exit code. -c (not -lc): exec output must stay clean for
// pipes/agents; profile noise belongs to interactive shells only.
func runInMachine(name string, argv []string, extraEnv []string) (int, error) {
	return runRaw(name, []string{"/bin/sh", "-c", shellCommand(argv)}, extraEnv)
}

// runRaw runs an exact guest argv (no sh -c wrapping) with stdio attached.
func runRaw(name string, guestCmd []string, extraEnv []string) (int, error) {
	proot, err := findProot()
	if err != nil {
		return 1, err
	}
	cmd := exec.Command(proot, prootArgs(rootfsDir(name), machineShm(name), guestCmd)...)
	cmd.Env = guestEnv(extraEnv)
	cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, os.Stdout, os.Stderr
	err = cmd.Run()
	if exit, ok := err.(*exec.ExitError); ok {
		return exit.ExitCode(), nil
	}
	if err != nil {
		return 1, err
	}
	return 0, nil
}

// startDetached launches the machine's init command in its own session,
// logging to logs/init.log, and records the proot pid. The pidfile is
// created O_EXCL *before* spawning, so two concurrent starts cannot both
// launch an init (the loser errors out instead of orphaning a session).
func startDetached(name string, initCmd string, extraEnv []string) (int, error) {
	proot, err := findProot()
	if err != nil {
		return 0, err
	}
	if err := os.MkdirAll(logDir(name), 0o700); err != nil {
		return 0, err
	}
	pf, err := os.OpenFile(pidPath(name), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return 0, fmt.Errorf("machine %q is already starting or running (pidfile exists)", name)
	}
	logf, err := os.OpenFile(filepath.Join(logDir(name), "init.log"),
		os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		pf.Close()
		os.Remove(pidPath(name))
		return 0, err
	}
	defer logf.Close()
	fmt.Fprintf(logf, "\n--- cellar start %s: %s ---\n", time.Now().Format(time.RFC3339), initCmd)

	guestCmd := []string{"/bin/sh", "-c", initCmd}
	cmd := exec.Command(proot, prootArgs(rootfsDir(name), machineShm(name), guestCmd)...)
	cmd.Env = guestEnv(extraEnv)
	cmd.Stdout, cmd.Stderr = logf, logf
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := cmd.Start(); err != nil {
		pf.Close()
		os.Remove(pidPath(name))
		return 0, err
	}
	pid := cmd.Process.Pid
	_, werr := fmt.Fprintf(pf, "%d\n", pid)
	if cerr := pf.Close(); werr != nil || cerr != nil {
		_ = syscall.Kill(-pid, syscall.SIGKILL)
		os.Remove(pidPath(name))
		return 0, errors.Join(werr, cerr)
	}
	_ = cmd.Process.Release()
	return pid, nil
}

// runningPid returns the machine's live init pid, or 0. Stale pidfiles
// (dead process, or pid reused by another process) count as not running
// and are cleaned up. Identity check: the cmdline must reference THIS
// machine's rootfs — matching just "proot" would let a reused pid point
// at some other proot session, which stop would then kill.
func runningPid(name string) int {
	b, err := os.ReadFile(pidPath(name))
	if err != nil {
		return 0
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(b)))
	if err != nil || pid <= 0 {
		os.Remove(pidPath(name))
		return 0
	}
	cmdline, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid))
	if err != nil || !strings.Contains(string(cmdline), rootfsDir(name)) {
		os.Remove(pidPath(name))
		return 0
	}
	return pid
}

// stopMachine TERMs the init's whole session, escalating to KILL.
func stopMachine(name string) error {
	pid := runningPid(name)
	if pid == 0 {
		return fmt.Errorf("machine %q is not running", name)
	}
	_ = syscall.Kill(-pid, syscall.SIGTERM)
	for i := 0; i < 50; i++ {
		if runningPid(name) == 0 {
			return nil // proot exited: --kill-on-exit reaped the guests
		}
		time.Sleep(100 * time.Millisecond)
	}
	// SIGKILL can't be handled, so proot never runs --kill-on-exit: kill
	// its descendants first, or guest daemons that called setsid() (sshd,
	// nohup'd agents) survive in another process group — invisible to the
	// CLI and still counting against Android's phantom-process budget.
	killDescendants(pid)
	_ = syscall.Kill(-pid, syscall.SIGKILL)
	os.Remove(pidPath(name))
	killDescendants(pid) // anything re-parented while proot was dying
	return nil
}

// killDescendants SIGKILLs every process descended from pid, deepest
// first. Guests can't be found by filesystem root (proot fakes chroot
// via ptrace, so /proc/<pid>/root still shows the host's) nor by
// cmdline (a guest's argv is its own) — parentage is what actually
// identifies them, and it survives setsid, which only changes session.
func killDescendants(pid int) {
	children := map[int][]int{}
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return
	}
	for _, e := range entries {
		p, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		if ppid, ok := parentOf(p); ok {
			children[ppid] = append(children[ppid], p)
		}
	}
	var walk func(int) []int
	walk = func(p int) []int {
		var out []int
		for _, c := range children[p] {
			out = append(out, walk(c)...)
			out = append(out, c)
		}
		return out
	}
	for _, p := range walk(pid) { // deepest first: no re-parenting races
		_ = syscall.Kill(p, syscall.SIGKILL)
	}
}

// parentOf reads PPid from /proc/<pid>/stat. The comm field can contain
// spaces and parentheses, so the fields after the final ')' are what
// can be split safely.
func parentOf(pid int) (int, bool) {
	b, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return 0, false
	}
	i := strings.LastIndexByte(string(b), ')')
	if i < 0 {
		return 0, false
	}
	fields := strings.Fields(string(b)[i+1:]) // [0]=state, [1]=ppid
	if len(fields) < 2 {
		return 0, false
	}
	ppid, err := strconv.Atoi(fields[1])
	return ppid, err == nil
}

// captureInMachine runs a shell snippet inside the machine and returns
// its stdout. Used for quick probes (is this installed?) where streaming
// to the terminal would be noise.
func captureInMachine(name, script string) (string, error) {
	proot, err := findProot()
	if err != nil {
		return "", err
	}
	cmd := exec.Command(proot, prootArgs(rootfsDir(name), machineShm(name),
		[]string{"/bin/sh", "-c", script})...)
	cmd.Env = guestEnv(nil)
	out, err := cmd.Output()
	return string(out), err
}
