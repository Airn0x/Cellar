# Field notes — what Android actually does to a Linux engine

Every entry below was hit for real while bringing engine v0 up on a stock, unrooted
Android 16 phone (Termux host). Each one cost a debugging round; recorded here so they
cost nobody a second one. This file is the raw material for the "surviving Android"
write-ups promised in the plan.

## 1. Static Go binaries can't resolve DNS

Android has no `/etc/resolv.conf`. A CGO-free Go binary uses the pure-Go resolver,
which — finding no config — falls back to `localhost:53` and dies with
`connection refused`. Bionic-linked binaries get DNS via Android's resolver daemon;
static ones get nothing.

**Fix:** when `/etc/resolv.conf` is absent, install a custom `net.DefaultResolver`
that dials public DNS directly (engine: `net.go`, override with `CELLAR_DNS`).

## 2. …or verify TLS certificates

Same story for CA roots: no `/etc/ssl` anywhere a static binary looks. Termux ships a
bundle at `$PREFIX/etc/tls/cert.pem`; Android keeps the system store in
`/apex/com.android.conscrypt/cacerts` (Android 14+) or `/system/etc/security/cacerts`.

**Fix:** probe those locations and set `SSL_CERT_FILE`/`SSL_CERT_DIR` before the first
TLS handshake (Go reads them once).

## 3. Hard links are forbidden in app data

SELinux denies `link()` for app-private storage. Distro rootfs tarballs are full of
hardlinks (Debian's `/usr/bin/perl5.40.1` → `perl`), so plain `tar -x` fails with
`Cannot hard link ... Permission denied`.

**Fix:** first shipped as running the extraction under `proot --link2symlink -0`
(proot-distro's own recipe). Engine v0.2 moved extraction into the binary itself
(pure-Go tar/xz, hardlinks materialized as copies) — an APK has no `tar`, so this had
to happen anyway, and it removes proot from the extract path as a bonus.

## 4. suid file modes break your own tooling

Alpine ships `/bin/bbsuid` as mode `4711` — setuid, executable, *not readable*. You own
the extracted file but can't read it, so `tar` (export) fails, and read-only
directories break recursive delete. suid grants nothing under proot anyway.

**Fix:** after extraction, normalize owner bits (`u+rw` on files, `u+rwx` on dirs).
Same normalization pass again before machine deletion, since partial extracts can
leave read-only directories.

## 5. proot's seccomp fast path breaks npm

`npm install` inside a machine dies with npm's infamous `Exit handler never called!`
and an otherwise-empty debug log. Node itself runs fine. The culprit is the
interaction between proot's seccomp acceleration and node/npm's process management.

**Fix:** default `PROOT_NO_SECCOMP=1` for guest processes (correctness over speed;
`CELLAR_SECCOMP=1` re-enables the fast path for benchmarking). With it set, Claude
Code installs and runs.

## 6. npm's rename dance fails under proot

Re-installing a global npm package renames the existing package dir aside; under proot
that rename fails with `ENOTEMPTY`. First install works, second breaks.

**Fix:** catalog stacks are idempotent — skip when the tool is already present instead
of reinstalling over it.

## 7. Android has no `/dev/shm`, and binding host `/dev` hides the guest's

Binding the host `/dev` into a machine is necessary — and it masks whatever `/dev/shm`
the rootfs shipped, because Android simply has no `/dev/shm`. POSIX shared memory then
fails, which quietly takes down Python's `multiprocessing` (`FileNotFoundError` out of
`sem_open`), PostgreSQL's dynamic shared memory, and anything Chromium-shaped.

**Fix:** bind a per-machine host directory over `/dev/shm`. Verified on-device: before,
`mp.Queue()` raised; after, it round-trips.

## 8. A hard stop leaks guest daemons — and `--kill-on-exit` can't help

proot's `--kill-on-exit` reaps guests when proot exits *normally*. When a stop escalates
to `SIGKILL` (an init that ignores `SIGTERM`), proot dies without ever running it, and
any guest that called `setsid()` — sshd, a `nohup`'d agent — survives in its own process
group: invisible to the CLI, still burning Android's 32-child phantom-process budget.

Finding those orphans is not obvious. proot fakes chroot via ptrace, so
`/proc/<pid>/root` still shows the *host* root, and a guest's cmdline is its own argv
with no rootfs path in it. **Parentage is the only reliable signal** — walk `/proc` for
`PPid` and kill proot's descendants deepest-first before killing proot itself.
`setsid()` changes the session, not the parent, so the tree walk still finds them.

Measured A/B on the escalation path: without the descendant kill, one orphan survives;
with it, zero.

## 9. The hardlink ban reaches *inside* the machine too

Extraction isn't the only thing that hardlinks. Modern package managers link from a
local cache to save space — `uv` does it by default — so installing Aider inside a
machine dies with `Operation not permitted` on `link()`, the same SELinux rule as field
note 3, one layer deeper.

Worse, the *second* attempt fails differently: proot's `--link2symlink` leaves
`.l2s.*` files and symlink chains behind, and reads through a half-populated cache then
fail with `ELOOP: Too many levels of symbolic links`.

**Fix:** tell the tool to copy (`UV_LINK_MODE=copy`) **and** clear any cache a failed
hardlink run poisoned. Both live in the catalog stack, so a user never meets either error.

## 10. Installers assume a TTY that machine commands don't have

Vendor install scripts often end with an interactive configurator. Goose's does, and it
died on `/dev/tty: No such device or address` — `cellar exec` has stdio, not a terminal.
Most such scripts have an opt-out (`CONFIGURE=false` here); the trap is *where* the
variable goes: `VAR=false curl … | bash` sets it for `curl`, not for the shell that
actually runs the installer. It belongs on the right-hand side: `curl … | VAR=false bash`.

## 11. `os.Exit` skips your deferred cleanup

Not Android's fault, but found here: an error-path `die()` (which calls `os.Exit`)
means `defer`red cleanup never runs, leaving half-created machines on disk.

**Fix:** explicit cleanup calls on error paths; no cleanup-in-defer in a CLI that
exits through `os.Exit`.

## Proof it all works

At the end of this debugging chain, on a stock unrooted phone:

```
$ cellar create dev --distro debian     # 90 MB, sha256-verified, ~1 min
$ cellar apply dev claude-code
$ cellar exec dev -- claude --version
2.1.220 (Claude Code)
```

An AI agent harness, running inside a rootless Linux machine, on a phone.
