package main

import (
	"bufio"
	"encoding/base64"
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"unsafe"
)

// A real terminal, allocated without cgo. Verified on a stock unrooted
// Android phone in an app-style SELinux domain: /dev/ptmx opens, the
// ioctls succeed, and the guest reports a live TTY — which is why the
// app needs no ttyd, no JNI and no third native binary.

const (
	tiocGPTN   = 0x80045430
	tiocSPTLCK = 0x40045431
	tiocSWINSZ = 0x5414
)

type winsize struct{ rows, cols, x, y uint16 }

func ioctl(fd, req, arg uintptr) error {
	if _, _, e := syscall.Syscall(syscall.SYS_IOCTL, fd, req, arg); e != 0 {
		return e
	}
	return nil
}

// openPTY returns the master side and the slave's path.
func openPTY() (*os.File, string, error) {
	master, err := os.OpenFile("/dev/ptmx", os.O_RDWR, 0)
	if err != nil {
		return nil, "", fmt.Errorf("open /dev/ptmx: %w", err)
	}
	var unlock int32
	if err := ioctl(master.Fd(), tiocSPTLCK, uintptr(unsafe.Pointer(&unlock))); err != nil {
		master.Close()
		return nil, "", fmt.Errorf("unlock pty: %w", err)
	}
	var n uint32
	if err := ioctl(master.Fd(), tiocGPTN, uintptr(unsafe.Pointer(&n))); err != nil {
		master.Close()
		return nil, "", fmt.Errorf("pty number: %w", err)
	}
	return master, fmt.Sprintf("/dev/pts/%d", n), nil
}

func setWinsize(f *os.File, cols, rows int) error {
	ws := winsize{rows: uint16(rows), cols: uint16(cols)}
	return ioctl(f.Fd(), tiocSWINSZ, uintptr(unsafe.Pointer(&ws)))
}

// loginShell prefers bash when the machine has it, so history and
// completion behave the way people expect.
const loginShell = "if [ -x /bin/bash ]; then exec /bin/bash -l; else exec /bin/sh -l; fi"

// attach runs an interactive shell in the machine on a PTY.
//
// Input arrives one of two ways. With --raw, stdin is copied straight
// through (a human at a terminal). Otherwise stdin is line-framed, which
// lets a GUI multiplex keystrokes and window size over one pipe without
// escaping ambiguity:
//
//	i <base64>      bytes to type
//	r <cols> <rows> window size
//
// Output is always the raw PTY stream, so a terminal emulator can render
// it untouched.
func attach(name string, cols, rows int, raw bool, extraEnv []string) (int, error) {
	proot, err := findProot()
	if err != nil {
		return 1, err
	}
	master, slavePath, err := openPTY()
	if err != nil {
		return 1, err
	}
	defer master.Close()

	slave, err := os.OpenFile(slavePath, os.O_RDWR|syscall.O_NOCTTY, 0)
	if err != nil {
		return 1, fmt.Errorf("open %s: %w", slavePath, err)
	}
	if err := setWinsize(master, cols, rows); err != nil {
		fmt.Fprintln(os.Stderr, "cellar: warning: could not set window size:", err)
	}

	guestCmd := []string{"/bin/sh", "-c", loginShell}
	cmd := exec.Command(proot, prootArgs(rootfsDir(name), machineShm(name), guestCmd)...)
	cmd.Env = append(guestEnv(extraEnv),
		fmt.Sprintf("COLUMNS=%d", cols), fmt.Sprintf("LINES=%d", rows))
	cmd.Stdin, cmd.Stdout, cmd.Stderr = slave, slave, slave
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true, Setctty: true, Ctty: 0}

	if err := cmd.Start(); err != nil {
		slave.Close()
		return 1, err
	}
	slave.Close() // the child owns it now; our copy would hold the pty open

	done := make(chan struct{})
	go func() { // pty -> our stdout, verbatim
		io.Copy(os.Stdout, master)
		close(done)
	}()

	if raw {
		// a human at a real terminal: pass keystrokes through and track
		// the window size of our own tty
		winch := make(chan os.Signal, 1)
		signal.Notify(winch, syscall.SIGWINCH)
		defer signal.Stop(winch)
		go func() {
			for range winch {
				var ws winsize
				if ioctl(os.Stdin.Fd(), 0x5413 /* TIOCGWINSZ */, uintptr(unsafe.Pointer(&ws))) == nil {
					setWinsize(master, int(ws.cols), int(ws.rows))
					cmd.Process.Signal(syscall.SIGWINCH)
				}
			}
		}()
		go func() {
			io.Copy(master, os.Stdin)
			master.Close()
		}()
	} else {
		go func() {
			sc := bufio.NewScanner(os.Stdin)
			sc.Buffer(make([]byte, 0, 64*1024), 1<<20)
			for sc.Scan() {
				line := sc.Text()
				switch {
				case strings.HasPrefix(line, "i "):
					if b, err := base64.StdEncoding.DecodeString(line[2:]); err == nil {
						master.Write(b)
					}
				case strings.HasPrefix(line, "r "):
					f := strings.Fields(line[2:])
					if len(f) == 2 {
						c, _ := strconv.Atoi(f[0])
						r, _ := strconv.Atoi(f[1])
						if c > 0 && r > 0 {
							setWinsize(master, c, r)
							cmd.Process.Signal(syscall.SIGWINCH)
						}
					}
				}
			}
			master.Close() // stdin closed: end the session
		}()
	}

	err = cmd.Wait()
	<-done // let the last of the output drain
	if exit, ok := err.(*exec.ExitError); ok {
		return exit.ExitCode(), nil
	}
	if err != nil {
		return 1, err
	}
	return 0, nil
}
