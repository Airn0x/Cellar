package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
)

// Meta is the persistent record of one machine, stored as meta.json
// next to its rootfs.
type Meta struct {
	Name    string `json:"name"`
	Distro  string `json:"distro"`
	Release string `json:"release"`
	Created string `json:"created"` // RFC3339
	Init    string `json:"init,omitempty"`
}

var nameRe = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,31}$`)

func validName(name string) error {
	if !nameRe.MatchString(name) {
		return fmt.Errorf("invalid machine name %q (want: lowercase letters, digits, dashes, max 32 chars)", name)
	}
	return nil
}

func cellarHome() string {
	if h := os.Getenv("CELLAR_HOME"); h != "" {
		return h
	}
	home, err := os.UserHomeDir()
	if err != nil {
		home = "."
	}
	return filepath.Join(home, ".cellar")
}

func machinesDir() string           { return filepath.Join(cellarHome(), "machines") }
func cacheDir() string              { return filepath.Join(cellarHome(), "cache") }
func machineDir(name string) string { return filepath.Join(machinesDir(), name) }
func rootfsDir(name string) string  { return filepath.Join(machineDir(name), "rootfs") }
func metaPath(name string) string   { return filepath.Join(machineDir(name), "meta.json") }
func pidPath(name string) string    { return filepath.Join(machineDir(name), "init.pid") }
func logDir(name string) string     { return filepath.Join(machineDir(name), "logs") }

func machineExists(name string) bool {
	_, err := os.Stat(metaPath(name))
	return err == nil
}

// removeMachineDir deletes a machine directory, first restoring owner
// rwx on directories: a partially extracted rootfs can contain
// read-only dirs that plain RemoveAll cannot descend into.
func removeMachineDir(name string) error {
	dir := machineDir(name)
	filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
		if err == nil && d.IsDir() {
			if info, e := d.Info(); e == nil && info.Mode().Perm()&0o700 != 0o700 {
				os.Chmod(path, info.Mode().Perm()|0o700)
			}
		}
		return nil
	})
	return os.RemoveAll(dir)
}

func loadMeta(name string) (*Meta, error) {
	b, err := os.ReadFile(metaPath(name))
	if err != nil {
		return nil, fmt.Errorf("no machine %q (%w)", name, err)
	}
	var m Meta
	if err := json.Unmarshal(b, &m); err != nil {
		return nil, fmt.Errorf("corrupt meta for %q: %w", name, err)
	}
	return &m, nil
}

func saveMeta(m *Meta) error {
	b, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(metaPath(m.Name), append(b, '\n'), 0o600)
}

func listMachines() ([]*Meta, error) {
	entries, err := os.ReadDir(machinesDir())
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var out []*Meta
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		m, err := loadMeta(e.Name())
		if err != nil {
			continue // half-created or foreign dir; skip rather than fail ls
		}
		out = append(out, m)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out, nil
}
