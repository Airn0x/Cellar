# Cellar

**A pocket homelab for AI agents. The server room in your cellphone.**

Cellar turns any modern Android phone — no root required — into a rootless Linux
server built to run AI setups: agent harnesses, coding agents, MCP servers, schedulers,
and small local models. Pick a distro, tap to start it, install an agent stack from the
catalog, and manage everything from a native console UI.

> **Status: pre-alpha — design phase.** The full technical plan lives in
> [`docs/PLAN.md`](docs/PLAN.md). The engine prototype comes first; the app follows.

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
