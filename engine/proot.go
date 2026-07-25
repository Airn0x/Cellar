package main

import (
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
func prootArgs(rootfs string, cmd []string) []string {
	args := []string{
		"--kill-on-exit",
		"--link2symlink",
		"-0",
		"-r", rootfs,
		"-b", "/dev",
		"-b", "/proc",
		"-b", "/sys",
		"-w", "/root",
	}
	return append(args, cmd...)
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

// runInMachine runs argv inside the machine with stdio attached and
// returns its exit code.
func runInMachine(name string, argv []string, extraEnv []string) (int, error) {
	proot, err := findProot()
	if err != nil {
		return 1, err
	}
	// -c (not -lc): exec output must stay clean for pipes/agents;
	// profile noise belongs to interactive shells only.
	guestCmd := []string{"/bin/sh", "-c", shQuote(argv)}
	if len(argv) == 1 {
		guestCmd = []string{"/bin/sh", "-c", argv[0]} // allow shell syntax in single-string form
	}
	cmd := exec.Command(proot, prootArgs(rootfsDir(name), guestCmd)...)
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

// runRaw runs an exact guest argv (no sh -c wrapping) with stdio attached.
func runRaw(name string, guestCmd []string, extraEnv []string) (int, error) {
	proot, err := findProot()
	if err != nil {
		return 1, err
	}
	cmd := exec.Command(proot, prootArgs(rootfsDir(name), guestCmd)...)
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
// logging to logs/init.log, and records the proot pid.
func startDetached(name string, initCmd string, extraEnv []string) (int, error) {
	proot, err := findProot()
	if err != nil {
		return 0, err
	}
	if err := os.MkdirAll(logDir(name), 0o700); err != nil {
		return 0, err
	}
	logf, err := os.OpenFile(filepath.Join(logDir(name), "init.log"),
		os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return 0, err
	}
	defer logf.Close()
	fmt.Fprintf(logf, "\n--- cellar start %s: %s ---\n", time.Now().Format(time.RFC3339), initCmd)

	guestCmd := []string{"/bin/sh", "-lc", initCmd}
	cmd := exec.Command(proot, prootArgs(rootfsDir(name), guestCmd)...)
	cmd.Env = guestEnv(extraEnv)
	cmd.Stdout, cmd.Stderr = logf, logf
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := cmd.Start(); err != nil {
		return 0, err
	}
	pid := cmd.Process.Pid
	if err := os.WriteFile(pidPath(name), []byte(strconv.Itoa(pid)+"\n"), 0o600); err != nil {
		_ = syscall.Kill(-pid, syscall.SIGKILL)
		return 0, err
	}
	_ = cmd.Process.Release()
	return pid, nil
}

// runningPid returns the machine's live init pid, or 0. Stale pidfiles
// (dead process, or pid reused by something that isn't proot) count as
// not running and are cleaned up.
func runningPid(name string) int {
	b, err := os.ReadFile(pidPath(name))
	if err != nil {
		return 0
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(b)))
	if err != nil || pid <= 0 {
		return 0
	}
	cmdline, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid))
	if err != nil || !strings.Contains(string(cmdline), "proot") {
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
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	_ = syscall.Kill(-pid, syscall.SIGKILL)
	os.Remove(pidPath(name))
	return nil
}
