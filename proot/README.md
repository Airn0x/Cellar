# proot for Cellar — build recipe & license compliance

Cellar ships [proot](https://proot-me.github.io/) inside its APK as two native
binaries. This directory is the **complete corresponding source offer** for them
(GPL-2.0 §3): the exact upstream, the exact tag, and the exact build script used.

## What we build

| file in APK | from | why |
|---|---|---|
| `jniLibs/arm64-v8a/libproot.so` | `src/proot` | the tracer itself |
| `jniLibs/arm64-v8a/libproot_loader.so` | `src/loader/loader` | see below |

**Upstream:** [`termux/proot`](https://github.com/termux/proot) tag **`v5.1.107.87`**
(zip sha256 `ae5a1b6941e4fe367f825667e446f6916be2bdd9825b000362afafffef50bce5`).

The termux fork — not upstream proot-me — because it is the only lineage carrying the
Android patch set Cellar depends on: `link2symlink`, SysV IPC emulation (Android kernels
have no SysV IPC), SIGSYS/seccomp trapping for syscalls Android's app filter denies,
statx and f2fs fixes, `fake_id0` (`-0`) and `--kill-on-exit`.

## Why the loader is a separate file

proot normally carries its loader as an embedded blob, and at the first guest `execve()`
it **writes that blob to a temp file and execs it**. Inside an Android app that exec is
SELinux-denied (W^X: no exec from app-writable storage) — so a single static proot binary
cannot work in an APK, no matter how it's built.

`PROOT_UNBUNDLE_LOADER` emits the loader as its own artifact; Cellar ships it as a second
jniLib (the only exec-legal location) and points `PROOT_LOADER` at it. The engine passes
that variable through into the guest environment (`engine/proot.go`).

## Build

Requires Docker; runs on any x86_64 machine or CI runner:

```sh
git clone https://github.com/termux/termux-packages
mkdir -p termux-packages/packages/proot-apk
cp build.sh termux-packages/packages/proot-apk/build.sh
cd termux-packages
./scripts/run-docker.sh ./build-package.sh -a aarch64 proot-apk
# artifacts: libproot.so, libproot_loader.so in the repo root
```

Both artifacts must pass:

```sh
readelf -l libproot.so libproot_loader.so | grep -E 'INTERP|Align'
# expect: no INTERP (static), and Align >= 0x4000 (16 KB pages, Android 15+)
```

Do **not** enable `PROOT_WITH_LIBANDROID_SHMEM` — it pulls in a shared Termux library
that does not exist inside an app; the fork's built-in sysvipc extension covers IPC.

## License

proot is GPL-2.0-or-later; the statically linked talloc is LGPL. Cellar's own code is
MIT. proot is shipped as a **separate program that Cellar exec's** — never linked into
the app — which is mere aggregation under GPLv2, the same pattern Termux, UserLAnd and
AnotherTerm use. This directory (tag + script + these notes) is published as the
corresponding source so anyone can rebuild or replace the binaries.
