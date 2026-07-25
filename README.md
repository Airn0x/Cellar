# Cellar

**A pocket homelab for AI agents. The server room in your cellphone.**

Cellar turns any modern Android phone — no root required — into a rootless Linux
server built to run AI setups: agent harnesses, coding agents, MCP servers, schedulers,
and small local models. Pick a distro, tap to start it, install an agent stack from the
catalog, and manage everything from a native console UI.

> **Status: pre-alpha — engine v0 works.** Machines create/run/stop on a real unrooted
> phone, and Claude Code installs and runs inside one (see
> [`docs/FIELD-NOTES.md`](docs/FIELD-NOTES.md) for the Android battle scars). The full
> technical plan lives in [`docs/PLAN.md`](docs/PLAN.md). The Android app is next.

## Why a phone?

A phone is the worst place to host a website and a surprisingly good place to host an
**AI agent**:

- **Agents are network-bound, not CPU-bound.** They spend their lives waiting on model
  APIs. The overhead of rootless (proot-based) Linux — the thing that makes phone-hosted
  databases slow — barely shows up in agent workloads.
- **The phone knows things no VPS ever will.** Notifications, SMS, location, camera,
  sensors, battery state. Cellar exposes these as a permission-gated bridge, so an agent
  on your phone can act on your real-world context. That is the feature no cloud box can copy.
- **It's already on, already yours, already paid for.** 8–16 GB of RAM in your pocket,
  idle 22 hours a day.

## What it will do

- **Machines** — create rootless Linux containers (Alpine / Debian / Ubuntu rootfs),
  start/stop/export them, one tap each. No root, no Termux knowledge needed.
- **Catalog** — one-tap stacks: Python + uv, Node, code-server, SSH, agent harnesses,
  MCP servers, llama.cpp for small local models.
- **Agent-first plumbing** — API keys stored in Android Keystore (never plaintext on
  disk), injected as env vars at start; schedules ("run my research agent at 2 a.m.,
  only while charging"); activity feed and audit log for phone-bridge access.
- **Console UI** — Jetpack Compose dashboard: thermal/battery/RAM gauges, container
  cards, live logs, a real terminal per machine.
- **Reachability** — phones live behind carrier NAT, so sharing a server means a tunnel
  (Tailscale / cloudflared integration planned).

## Builds

Test APKs are published on the [releases page](https://github.com/Airn0x/Cellar/releases)
— arm64, Android 10+, debug-signed (Android will warn when installing). Pushing a `v*`
tag builds and publishes a new one automatically.

| version | what landed |
|---|---|
| [v0.4.0](https://github.com/Airn0x/Cellar/releases/tag/v0.4.0) | catalog, chat, console, setup screen, key vault |
| [v0.3.0](https://github.com/Airn0x/Cellar/releases/tag/v0.3.0) | create wizard, machine actions, foreground service |
| [v0.2.0](https://github.com/Airn0x/Cellar/releases/tag/v0.2.0) | proot + loader bundled — machines runnable from the app |
| [v0.1.0](https://github.com/Airn0x/Cellar/releases/tag/v0.1.0) | app skeleton, engine executing inside the sandbox |

## Try it today (Termux, pre-app)

The engine is a standalone CLI already usable under [Termux](https://termux.dev):

```sh
pkg install proot golang git
git clone https://github.com/Airn0x/Cellar && cd Cellar/engine
go build -o cellar . && export PATH="$PATH:$PWD"

cellar create dev --distro debian      # alpine and ubuntu work too
cellar shell dev                       # a root shell on your new machine
cellar apply dev claude-code           # or: python-uv, node, sshd
cellar exec dev -e ANTHROPIC_API_KEY=sk-... -- claude -p "hello from my phone"
cellar start dev -- '/usr/sbin/sshd -D -e'   # daemons, with logs + stop
```

## What it deliberately is not

- **Not hosting.** Phones reboot, overheat, and leave the house. Cellar is a personal
  lab, not a datacenter — the UI says so and the scheduler works around it.
- **Not multi-tenant.** Everything runs under one Android UID. Containers organize your
  work; they do not defend against hostile code. Run things you trust.

## Architecture (short version)

```
┌───────────────────────────── Android app (Kotlin / Compose) ────────────┐
│  Dashboard · Catalog · Schedules · Keys (Keystore) · Phone bridge · UI  │
│                    │ foreground service + wake lock                     │
│                    ▼                                                    │
│   cellar-engine (static Go binary, shipped as jniLib)                   │
│     create / start / stop / exec / export · JSON state · catalog runner │
│                    │                                                    │
│                    ▼                                                    │
│   proot ─── rootfs per machine (app-private storage)                    │
└──────────────────────────────────────────────────────────────────────────┘
```

The engine is a standalone CLI first — it runs today under Termux/proot for development
and dogfooding — and the app is a frontend over it. See [`docs/PLAN.md`](docs/PLAN.md)
for the full design, the honest constraints list (W^X, phantom process killer, thermals,
Play policy), and how each one is handled.

## License

[MIT](LICENSE)
