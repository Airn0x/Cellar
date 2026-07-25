package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
)

// A catalog stack is a directory containing meta.json and apply.sh;
// apply.sh runs inside the machine as root via /bin/sh.
type StackMeta struct {
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Distros     []string `json:"distros,omitempty"` // empty = any
}

// catalogDir resolution order: $CELLAR_CATALOG, catalog/ next to the
// binary's parent (repo layout), $CELLAR_HOME/catalog.
func catalogDir() string {
	if d := os.Getenv("CELLAR_CATALOG"); d != "" {
		return d
	}
	if exe, err := os.Executable(); err == nil {
		repo := filepath.Join(filepath.Dir(exe), "..", "catalog")
		if st, err := os.Stat(repo); err == nil && st.IsDir() {
			return repo
		}
	}
	return filepath.Join(cellarHome(), "catalog")
}

func loadStack(stack string) (*StackMeta, string, error) {
	dir := filepath.Join(catalogDir(), stack)
	if _, err := os.Stat(dir); err != nil {
		return nil, "", fmt.Errorf("no stack %q in catalog %s", stack, catalogDir())
	}
	b, err := os.ReadFile(filepath.Join(dir, "meta.json"))
	if err != nil {
		return nil, "", fmt.Errorf("stack %q has no readable meta.json (%w)", stack, err)
	}
	var m StackMeta
	if err := json.Unmarshal(b, &m); err != nil {
		return nil, "", fmt.Errorf("corrupt meta for stack %q: %w", stack, err)
	}
	m.Name = stack // the directory name is the identity; never trust the file's
	script := filepath.Join(dir, "apply.sh")
	if _, err := os.Stat(script); err != nil {
		return nil, "", fmt.Errorf("stack %q has no apply.sh", stack)
	}
	return &m, script, nil
}

func listStacks() ([]*StackMeta, error) {
	entries, err := os.ReadDir(catalogDir())
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	out := []*StackMeta{} // non-nil: `catalog --json` must print [], not null
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		if m, _, err := loadStack(e.Name()); err == nil {
			out = append(out, m)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out, nil
}

// applyStack copies the stack's script into the machine and runs it.
func applyStack(machine, stack string) (int, error) {
	meta, script, err := loadStack(stack)
	if err != nil {
		return 1, err
	}
	m, err := loadMeta(machine)
	if err != nil {
		return 1, err
	}
	if len(meta.Distros) > 0 {
		ok := false
		for _, d := range meta.Distros {
			if d == m.Distro {
				ok = true
			}
		}
		if !ok {
			return 1, fmt.Errorf("stack %q supports %v, machine %q is %s", stack, meta.Distros, machine, m.Distro)
		}
	}
	b, err := os.ReadFile(script)
	if err != nil {
		return 1, err
	}
	// pid-unique name (concurrent applies must not clobber each other's
	// script) and remove-then-O_EXCL (never write through a symlink the
	// guest may have planted at a predictable path in its /tmp).
	scriptName := fmt.Sprintf(".cellar-apply-%d.sh", os.Getpid())
	guestPath := filepath.Join(rootfsDir(machine), "tmp", scriptName)
	os.Remove(guestPath)
	f, err := os.OpenFile(guestPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o700)
	if err != nil {
		return 1, err
	}
	_, werr := f.Write(b)
	if cerr := f.Close(); werr != nil || cerr != nil {
		os.Remove(guestPath)
		return 1, errors.Join(werr, cerr)
	}
	defer os.Remove(guestPath)
	fmt.Fprintf(os.Stderr, "applying %s to %s ...\n", stack, machine)
	return runRaw(machine, []string{"/bin/sh", "/tmp/" + scriptName}, nil)
}
