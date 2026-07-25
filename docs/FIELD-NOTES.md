# Field notes — what Android actually does to a Linux engine

Everything below was hit for real while bringing engine v0 up on a stock, unrooted
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

## 7. `os.Exit` skips your deferred cleanup

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
