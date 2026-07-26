// cellar is a rootless Linux machine manager for Android/Termux (and any
// Linux host with proot). Machines are proot rootfs directories; the AI
// stacks that run inside them come from the catalog. Every read command
// takes --json so UIs and agents can drive this CLI directly.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
)

const version = "0.1.0"

const usage = `cellar — a pocket homelab for AI agents

usage:
  cellar create <name> --distro alpine|debian|ubuntu [--release R] [--json]
  cellar ls [--json]
  cellar shell <name>
  cellar attach <name> [--cols N --rows N] [--raw] [-e K=V]...
  cellar exec <name> [-e K=V]... -- <command...>
  cellar start <name> [-e K=V]... [-- <init command...>]
  cellar stop <name>
  cellar logs <name>
  cellar rm <name> [--force]
  cellar export <name> [-o file.tar.gz]
  cellar apply <machine> <stack>
  cellar catalog [--json]
  cellar installed <machine> [--json]
  cellar version

environment:
  CELLAR_HOME     state dir (default ~/.cellar)
  CELLAR_PROOT    proot binary (default: found in PATH)
  CELLAR_CATALOG  catalog dir (default: ../catalog relative to the binary — the repo
                  layout — then $CELLAR_HOME/catalog)
`

func die(err error) {
	fmt.Fprintln(os.Stderr, "cellar:", err)
	os.Exit(1)
}

// envFlags collects repeated -e K=V flags.
type envFlags []string

func (e *envFlags) String() string { return strings.Join(*e, ",") }
func (e *envFlags) Set(v string) error {
	if !strings.Contains(v, "=") {
		return fmt.Errorf("want K=V, got %q", v)
	}
	*e = append(*e, v)
	return nil
}

// splitDashDash separates flags from the trailing "-- command..." part.
func splitDashDash(args []string) (flags, cmd []string) {
	for i, a := range args {
		if a == "--" {
			return args[:i], args[i+1:]
		}
	}
	return args, nil
}

// popName takes the machine name from the front of args, so users can
// write "cellar create dev --distro alpine" — Go's flag package would
// otherwise stop parsing at "dev". The name must come before any flags;
// guessing it out of the middle can't be done safely (a flag's value
// looks exactly like a positional).
func popName(args []string) (name string, rest []string) {
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		return args[0], args[1:]
	}
	return "", args
}

func main() {
	setupCerts()
	setupResolver()
	if len(os.Args) < 2 {
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
	switch os.Args[1] {
	case "create":
		cmdCreate(os.Args[2:])
	case "ls":
		cmdLs(os.Args[2:])
	case "shell":
		cmdShell(os.Args[2:])
	case "attach":
		cmdAttach(os.Args[2:])
	case "exec":
		cmdExec(os.Args[2:])
	case "start":
		cmdStart(os.Args[2:])
	case "stop":
		cmdStop(os.Args[2:])
	case "logs":
		cmdLogs(os.Args[2:])
	case "rm":
		cmdRm(os.Args[2:])
	case "export":
		cmdExport(os.Args[2:])
	case "apply":
		cmdApply(os.Args[2:])
	case "catalog":
		cmdCatalog(os.Args[2:])
	case "installed":
		cmdInstalled(os.Args[2:])
	case "version":
		fmt.Println("cellar", version)
	case "help", "-h", "--help":
		fmt.Print(usage)
	default:
		fmt.Fprintf(os.Stderr, "cellar: unknown command %q\n\n%s", os.Args[1], usage)
		os.Exit(2)
	}
}

func cmdCreate(args []string) {
	fs := flag.NewFlagSet("create", flag.ExitOnError)
	distro := fs.String("distro", "", "alpine|debian|ubuntu")
	release := fs.String("release", "", "distro release (default: engine's pick)")
	asJSON := fs.Bool("json", false, "machine info as JSON")
	name, rest := popName(args)
	fs.Parse(rest)
	if name == "" || fs.NArg() != 0 || *distro == "" {
		die(fmt.Errorf("usage: cellar create <name> --distro alpine|debian|ubuntu"))
	}
	if err := validName(name); err != nil {
		die(err)
	}
	if machineDirExists(name) { // covers half-created dirs too, not just healthy machines
		die(fmt.Errorf("machine %q already exists (clean up leftovers with: cellar rm %s --force)", name, name))
	}

	img, err := resolveImage(*distro, *release)
	if err != nil {
		die(err)
	}

	// Reserve the machine dir atomically (Mkdir, not MkdirAll) so two
	// concurrent creates of the same name can't interleave extractions.
	if err := os.MkdirAll(machinesDir(), 0o700); err != nil {
		die(err)
	}
	if err := os.Mkdir(machineDir(name), 0o700); err != nil {
		die(fmt.Errorf("machine %q is already being created: %w", name, err))
	}
	// Clean up on Ctrl-C/TERM: die() exits without running defers, and an
	// interrupted extract would otherwise leave an invisible half-machine.
	// (SIGKILL can't be caught; `cellar rm --force` handles that leftover.)
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sig
		fmt.Fprintln(os.Stderr, "\ncellar: interrupted — removing half-created machine")
		removeMachineDir(name)
		os.Exit(130)
	}()

	tarball, err := download(img)
	if err != nil {
		removeMachineDir(name)
		die(err)
	}
	fmt.Fprintf(os.Stderr, "extracting ...\n")
	if err := extract(tarball, rootfsDir(name)); err != nil {
		removeMachineDir(name)
		die(err)
	}
	meta := &Meta{
		Name: name, Distro: img.distro, Release: img.release,
		Created: time.Now().UTC().Format(time.RFC3339),
	}
	if err := saveMeta(meta); err != nil {
		removeMachineDir(name)
		die(err)
	}
	signal.Stop(sig)

	if *asJSON {
		json.NewEncoder(os.Stdout).Encode(map[string]string{
			"name": name, "distro": img.distro, "release": img.release, "build": img.build,
		})
		return
	}
	fmt.Printf("machine %q ready (%s %s)\n  shell:  cellar shell %s\n  stacks: cellar apply %s <stack>\n",
		name, img.distro, img.release, name, name)
}

type lsRow struct {
	Name    string `json:"name"`
	Distro  string `json:"distro"`
	Release string `json:"release"`
	Created string `json:"created"`
	Running bool   `json:"running"`
	Pid     int    `json:"pid,omitempty"`
	Broken  bool   `json:"broken,omitempty"` // dir without meta.json (interrupted create)
}

func cmdLs(args []string) {
	fs := flag.NewFlagSet("ls", flag.ExitOnError)
	asJSON := fs.Bool("json", false, "list as JSON")
	fs.Parse(args)
	metas, brokenNames, err := listMachines()
	if err != nil {
		die(err)
	}
	rows := make([]lsRow, 0, len(metas)+len(brokenNames))
	for _, m := range metas {
		pid := runningPid(m.Name)
		rows = append(rows, lsRow{Name: m.Name, Distro: m.Distro, Release: m.Release,
			Created: m.Created, Running: pid != 0, Pid: pid})
	}
	for _, n := range brokenNames {
		rows = append(rows, lsRow{Name: n, Distro: "?", Release: "?", Broken: true})
	}
	if *asJSON {
		json.NewEncoder(os.Stdout).Encode(rows)
		return
	}
	if len(rows) == 0 {
		fmt.Println("no machines — try: cellar create dev --distro alpine")
		return
	}
	fmt.Printf("%-16s %-8s %-8s %-8s %s\n", "NAME", "DISTRO", "RELEASE", "STATE", "CREATED")
	for _, r := range rows {
		state := "stopped"
		switch {
		case r.Broken:
			state = "broken" // clean up with: cellar rm <name> --force
		case r.Running:
			state = fmt.Sprintf("up:%d", r.Pid)
		}
		fmt.Printf("%-16s %-8s %-8s %-8s %s\n", r.Name, r.Distro, r.Release, state, r.Created)
	}
}

func mustMachine(name string) {
	if err := validName(name); err != nil {
		die(err)
	}
	if !machineExists(name) {
		die(fmt.Errorf("no machine %q (see: cellar ls)", name))
	}
}

func cmdShell(args []string) {
	if len(args) != 1 {
		die(fmt.Errorf("usage: cellar shell <name>"))
	}
	mustMachine(args[0])
	// login shell; bash if the machine has it, sh otherwise
	code, err := runRaw(args[0], []string{"/bin/sh", "-lc",
		"if [ -x /bin/bash ]; then exec /bin/bash -l; else exec /bin/sh -l; fi"}, nil)
	if err != nil {
		die(err)
	}
	os.Exit(code)
}

// cmdAttach is the terminal entry point: a PTY-backed interactive shell.
// GUIs speak the framed protocol on stdin (see pty.go); --raw is for a
// human running this from a real terminal.
func cmdAttach(args []string) {
	flagPart, _ := splitDashDash(args)
	fs := flag.NewFlagSet("attach", flag.ExitOnError)
	cols := fs.Int("cols", 80, "terminal width")
	rows := fs.Int("rows", 24, "terminal height")
	raw := fs.Bool("raw", false, "copy stdin through instead of the framed protocol")
	var env envFlags
	fs.Var(&env, "e", "extra env K=V (repeatable)")
	name, rest := popName(flagPart)
	fs.Parse(rest)
	if name == "" || fs.NArg() != 0 {
		die(fmt.Errorf("usage: cellar attach <name> [--cols N --rows N] [--raw]"))
	}
	mustMachine(name)
	code, err := attach(name, *cols, *rows, *raw, env)
	if err != nil {
		die(err)
	}
	os.Exit(code)
}

func cmdExec(args []string) {
	flagPart, cmdPart := splitDashDash(args)
	fs := flag.NewFlagSet("exec", flag.ExitOnError)
	var env envFlags
	fs.Var(&env, "e", "extra env K=V (repeatable)")
	name, rest := popName(flagPart)
	fs.Parse(rest)
	if name == "" || fs.NArg() != 0 || len(cmdPart) == 0 {
		die(fmt.Errorf("usage: cellar exec <name> [-e K=V]... -- <command...>"))
	}
	mustMachine(name)
	code, err := runInMachine(name, cmdPart, env)
	if err != nil {
		die(err)
	}
	os.Exit(code)
}

func cmdStart(args []string) {
	flagPart, cmdPart := splitDashDash(args)
	fs := flag.NewFlagSet("start", flag.ExitOnError)
	var env envFlags
	fs.Var(&env, "e", "extra env K=V (repeatable)")
	name, rest := popName(flagPart)
	fs.Parse(rest)
	if name == "" || fs.NArg() != 0 {
		die(fmt.Errorf("usage: cellar start <name> [-e K=V]... [-- <init command...>]"))
	}
	mustMachine(name)
	if pid := runningPid(name); pid != 0 {
		die(fmt.Errorf("machine %q already running (pid %d)", name, pid))
	}
	meta, err := loadMeta(name)
	if err != nil {
		die(err)
	}
	initCmd := meta.Init
	if len(cmdPart) > 0 {
		// same semantics as exec: one arg = shell syntax as-is, several
		// args = quoted so they survive sh -c (and persistence) verbatim
		initCmd = shellCommand(cmdPart)
	}
	if initCmd == "" {
		die(fmt.Errorf("machine %q has no init command — start it once with: cellar start %s -- <command>", name, name))
	}
	pid, err := startDetached(name, initCmd, env)
	if err != nil {
		die(err)
	}
	if initCmd != meta.Init {
		meta.Init = initCmd // remember last-used init for next time
		if err := saveMeta(meta); err != nil {
			fmt.Fprintln(os.Stderr, "cellar: warning: could not persist init command:", err)
		}
	}
	fmt.Printf("started %q (pid %d) — logs: cellar logs %s\n", name, pid, name)
}

func cmdStop(args []string) {
	if len(args) != 1 {
		die(fmt.Errorf("usage: cellar stop <name>"))
	}
	mustMachine(args[0])
	if err := stopMachine(args[0]); err != nil {
		die(err)
	}
	fmt.Printf("stopped %q\n", args[0])
}

func cmdLogs(args []string) {
	if len(args) != 1 {
		die(fmt.Errorf("usage: cellar logs <name>"))
	}
	mustMachine(args[0])
	b, err := os.ReadFile(logDir(args[0]) + "/init.log")
	if err != nil {
		die(fmt.Errorf("no logs yet for %q", args[0]))
	}
	os.Stdout.Write(b)
}

func cmdRm(args []string) {
	fs := flag.NewFlagSet("rm", flag.ExitOnError)
	force := fs.Bool("force", false, "stop first if running")
	name, rest := popName(args)
	fs.Parse(rest)
	if name == "" || fs.NArg() != 0 {
		die(fmt.Errorf("usage: cellar rm <name> [--force]"))
	}
	if err := validName(name); err != nil {
		die(err)
	}
	// dir-existence, not meta-existence: rm must be able to clean up
	// half-created machines that have no meta.json
	if !machineDirExists(name) {
		die(fmt.Errorf("no machine %q (see: cellar ls)", name))
	}
	if pid := runningPid(name); pid != 0 {
		if !*force {
			die(fmt.Errorf("machine %q is running (pid %d) — stop it or use --force", name, pid))
		}
		if err := stopMachine(name); err != nil {
			die(err)
		}
	}
	if err := removeMachineDir(name); err != nil {
		die(err)
	}
	fmt.Printf("removed %q\n", name)
}

func cmdExport(args []string) {
	fs := flag.NewFlagSet("export", flag.ExitOnError)
	out := fs.String("o", "", "output file (default <name>-<date>.tar.gz)")
	name, rest := popName(args)
	fs.Parse(rest)
	if name == "" || fs.NArg() != 0 {
		die(fmt.Errorf("usage: cellar export <name> [-o file.tar.gz]"))
	}
	mustMachine(name)
	if runningPid(name) != 0 {
		fmt.Fprintf(os.Stderr, "cellar: warning: %q is running; export may be inconsistent\n", name)
	}
	dest := *out
	if dest == "" {
		dest = fmt.Sprintf("%s-%s.tar.gz", name, time.Now().Format("20060102"))
	}
	// 0600 archive (exportMachine creates it so): a machine image is
	// home dirs, host keys, whatever agents left behind
	if err := exportMachine(name, dest); err != nil {
		die(fmt.Errorf("export: %w", err))
	}
	fmt.Printf("exported %q to %s\n", name, dest)
}

func cmdApply(args []string) {
	if len(args) != 2 {
		die(fmt.Errorf("usage: cellar apply <machine> <stack>"))
	}
	mustMachine(args[0])
	code, err := applyStack(args[0], args[1])
	if err != nil {
		die(err)
	}
	if code != 0 {
		die(fmt.Errorf("stack %q failed with exit code %d", args[1], code))
	}
	fmt.Printf("applied %q to %q\n", args[1], args[0])
}

// cmdInstalled answers the only catalog question a user actually has:
// what does THIS machine already have? One `command -v` per stack, in a
// single guest shell — cheap enough to run whenever a UI opens.
func cmdInstalled(args []string) {
	fs := flag.NewFlagSet("installed", flag.ExitOnError)
	asJSON := fs.Bool("json", false, "list as JSON")
	name, rest := popName(args)
	fs.Parse(rest)
	if name == "" || fs.NArg() != 0 {
		die(fmt.Errorf("usage: cellar installed <machine> [--json]"))
	}
	mustMachine(name)
	stacks, err := listStacks()
	if err != nil {
		die(err)
	}
	var probes []string
	var names []string
	for _, s := range stacks {
		if s.Check == "" {
			continue
		}
		names = append(names, s.Name)
		probes = append(probes, fmt.Sprintf("command -v %s >/dev/null 2>&1 && echo %s", s.Check, s.Name))
	}
	found := map[string]bool{}
	if len(probes) > 0 {
		out, err := captureInMachine(name, strings.Join(probes, "; "))
		if err == nil {
			for _, line := range strings.Split(out, "\n") {
				if line = strings.TrimSpace(line); line != "" {
					found[line] = true
				}
			}
		}
	}
	if *asJSON {
		rows := make(map[string]bool, len(names))
		for _, n := range names {
			rows[n] = found[n]
		}
		json.NewEncoder(os.Stdout).Encode(rows)
		return
	}
	for _, n := range names {
		state := "-"
		if found[n] {
			state = "installed"
		}
		fmt.Printf("%-14s %s\n", n, state)
	}
}

func cmdCatalog(args []string) {
	fs := flag.NewFlagSet("catalog", flag.ExitOnError)
	asJSON := fs.Bool("json", false, "list as JSON")
	fs.Parse(args)
	stacks, err := listStacks()
	if err != nil {
		die(err)
	}
	if *asJSON {
		json.NewEncoder(os.Stdout).Encode(stacks)
		return
	}
	if len(stacks) == 0 {
		fmt.Printf("no stacks in %s\n", catalogDir())
		return
	}
	for _, s := range stacks {
		notes := []string{}
		if s.Size != "" {
			notes = append(notes, s.Size)
		}
		if len(s.Distros) > 0 {
			notes = append(notes, strings.Join(s.Distros, "/"))
		}
		if s.NeedsKey != "" {
			notes = append(notes, "needs $"+s.NeedsKey)
		}
		if !s.Verified {
			notes = append(notes, "UNVERIFIED on a phone")
		}
		suffix := ""
		if len(notes) > 0 {
			suffix = "  (" + strings.Join(notes, " · ") + ")"
		}
		fmt.Printf("%-14s %s%s\n", s.Name, s.Description, suffix)
	}
}
