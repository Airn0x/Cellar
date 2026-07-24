# Cellar — technical plan

*Last updated: 2026-07-24 · status: design phase, nothing built yet.*

Cellar turns an unrooted Android phone into a rootless Linux server focused on **running
AI agents and harnesses**, managed from a native GUI. This document is the complete
plan: positioning, engine choice, every known platform constraint with its chosen
mitigation, the app design, and the roadmap.

---

## 1. Positioning

Existing "Linux on Android" apps (UserLAnd, Andronix, Termux) tell a *Linux desktop on
your phone* story. Cellar tells a different one:

> **Your pocket AI homelab.** Machines you can tap to start, agent stacks you can tap to
> install, schedules that respect battery and heat, and a bridge that gives agents the
> one thing no cloud VPS has — your phone's senses.

Three audiences, in order:

1. **Agent tinkerers** — people running coding agents, harnesses, MCP servers, cron-style
   research agents, who want an always-carried box to host them.
2. **Learners** — "I want a Linux box + AI playground and I only own a phone." Huge
   audience globally; a phone is many people's only computer.
3. **Self-hosters** — small always-on utilities (webhooks, bots, bridges) that tolerate
   phone-grade uptime.

## 2. Why the AI focus changes the technical calculus

The classic knock on rootless Linux-on-Android is proot's syscall-tracing overhead
(I/O-heavy work runs 2–10× slower). **AI agent workloads dodge this almost entirely**:

- An agent harness spends >95% of wall-clock time waiting on model APIs over the
  network. Syscall overhead is noise.
- Package installs and git operations feel the tax, but they're occasional, not the
  steady state.
- The genuinely CPU-hot path — local inference — should not run under proot at all
  (§6.3): llama.cpp runs as a native Android binary inside the app, exposed to
  containers as an OpenAI-compatible endpoint on localhost.

So the architecture puts the *tax where it's cheap* (agent orchestration under proot)
and the *heat where it's native* (inference, terminal, UI in the app process).

## 3. Engine choice

Four possible engines on Android; only one is viable everywhere today:

| Engine | Root? | Verdict |
|---|---|---|
| **proot** (ptrace fake-root) | no | ✅ **Core engine.** Works on every device; proven by UserLAnd & Termux. |
| QEMU full-system | no | Too slow without KVM. Maybe a later "compat mode" for odd workloads. |
| AVF / pKVM (real VMs — Google's Terminal app) | no, but device-gated | 🔭 **The future backend.** Watch the third-party API surface; adopt when open enough. Design the engine interface so a VM backend can slot in. |
| Real containers (LXC/Docker) | yes | Kills 95% of the audience. No. |

**"Containers" in Cellar** = one rootfs directory per machine (create / start / stop /
exec / export-as-tar / delete) — the proot-distro model with state and a UI. For real
OCI images, **udocker** (proot-backed Docker-image runner) is the honest path; it's a
catalog item, not the foundation.

**Engine language: Go.** One static arm64 binary, trivially shipped inside the APK as a
jniLib, no interpreter dependency, easy to unit test, cross-compiles from any dev
machine. CLI-first with `--json` output on every command so the app (and any agent!)
can drive it. A shell-script prototype is acceptable for week-one learning, but v1 is Go.

```
cellar create web1 --distro alpine       # download rootfs, sha256-verify, extract
cellar start web1                        # proot up, run machine's init
cellar exec web1 -- python agent.py
cellar apply web1 catalog/claude-harness # install a catalog stack into the machine
cellar ls --json                         # everything scriptable
cellar export web1 > web1.tar.zst
```

## 4. Constraints → solutions

Every known platform problem, with the chosen answer. This section is the product's
honesty contract — it also becomes the FAQ.

| # | Constraint | Solution |
|---|---|---|
| 1 | **No isolation** — everything shares one Android UID; machine A can read machine B. | Position as single-user lab, never multi-tenant. Real fix arrives with the AVF backend. Meanwhile: secrets never live in rootfs (§7), and the phone-bridge is permission-gated per machine. |
| 2 | **proot performance tax.** | AI focus makes it mostly moot (§2). Publish honest benchmarks in the README (agent latency, pip install, git clone) — credibility feature, not a bug to hide. Inference runs native (§6.3). |
| 3 | **W^X exec restrictions** (Android 10+: no exec from app data when targeting modern SDK). | Ship all our binaries (proot, busybox, engine, ttyd, llama.cpp) as `lib*.so` in the APK's native library dir — the jniLibs pattern UserLAnd proves viable on current Android. Guest binaries load via proot's own loader. Build **16 KB page-aligned** from day one (Android 15+ requirement). |
| 4 | **Play Store policy risk** ("downloads executable code" gray zone — Termux left Play; UserLAnd survives). | Decision made: **open source from day one** (this repo — it's also the portfolio). Try Play with targetSdk current + jniLibs packaging; if rejected, F-Droid + GitHub releases are the primary channel, not a fallback of shame. |
| 5 | **Phantom process killer** (Android 12+ kills app children >32 or on "excessive CPU"), Doze, OEM app-sleepers. | Foreground service (`specialUse`/`dataSync`) + wake lock + battery-optimization exemption flow. Keep the process tree tiny: **one proot per running machine, one tiny init inside** supervising the machine's services. Guided setup detects the phantom-killer setting and walks the user through disabling child-process restrictions (ADB or Shizuku), with a plain-English explanation. |
| 6 | **Thermals & battery** — a busy server cooks the phone. | Watt-awareness as a *feature*: ThermalManager + battery state on the dashboard; scheduler supports "only while charging" and "not above N °C"; auto-pause heavy machines on thermal throttle. Agents are bursty by nature — schedule the bursts for the charger. |
| 7 | **Uptime honesty** — phones reboot, update, leave the house. | Never say VPS-grade uptime. The scheduler is the answer: agents as **scheduled runs** (WorkManager wakes the service, service runs the machine, machine exits) rather than 24/7 daemons. Daemon mode exists but the UI is honest about what Android may do to it. |
| 8 | **Storage** — uninstall wipes app-private data; shared storage can't hold a rootfs (no unix perms, no exec). | Rootfs lives app-private, always. First-class **export/backup UX**: export machine → tar.zst via SAF; optional scheduled backup; "push work products to a git remote" as a catalog-level convention for agents. |
| 9 | **Architecture** — arm64 only; amd64 images need slow emulation. | Curate the catalog to arm64-native stacks (Python, Node, Go, llama.cpp all fine). udocker + qemu-user marked "slow lane". |
| 10 | **Licensing** — proot is GPL; Termux's terminal-view is GPLv3. | Cellar's own code is MIT. GPL binaries (proot, ttyd) are exec'd as separate processes, never linked. Terminal = bundled **ttyd rendered in a WebView**, avoiding the GPLv3 view library. |
| 11 | **Google ships AVF Terminal** — the platform is slowly absorbing "Linux on phone". | Differentiate on what Google's terminal will never do: agent catalog, schedules, key management, phone-senses bridge, homelab console UI. Treat AVF as our future backend, not our competitor. |
| 12 | **Entrenched free competition** (UserLAnd, Andronix, Termux). | Different story (§1). Cellar is to UserLAnd what a NAS UI is to a bare distro ISO. |

## 5. The phone bridge (the moat)

Key design insight: **Cellar doesn't need Termux:API — it *is* an Android app.** It can
request notification access, SMS, location, camera, and sensor permissions natively and
expose them to machines as a local HTTP API (localhost, per-machine token):

```
GET  /v1/battery                → { level, charging, temp }
GET  /v1/notifications?since=…  → what the phone has seen   [permission-gated]
POST /v1/notify                 → post a notification from an agent
POST /v1/sms/send               → guarded, per-machine toggle, rate-limited, audit-logged
GET  /v1/location               → coarse/fine, permission-gated
GET  /v1/sensors/…              → accelerometer, light, etc.
```

Rules: every capability is **off by default**, toggled per machine in the UI, and every
call lands in a visible audit log ("agent `research` read 3 notifications today").
This is the feature that makes a phone-hosted agent *better* than a cloud-hosted one,
and it's also the part reviewers will scrutinize — the audit-log-first design is both
ethics and pitch.

## 6. The app (full GUI)

**Stack: Kotlin + Jetpack Compose** (native, modern, and the right portfolio signal),
with two WebView islands: the ttyd terminal and (maybe) machine web-app previews.

### 6.1 Screens

- **Dashboard** — thermal/battery/RAM gauges, running machines, agent activity feed,
  scheduler's next runs. The "homelab console" feel.
- **Machines** — cards: status, distro, ports, CPU/RAM, buttons (start/stop/terminal/
  logs/export). Create-machine wizard: distro → size → catalog stacks → done.
- **Catalog** — one-tap stacks with descriptions and honest footprints: `python-uv`,
  `node`, `sshd`, `code-server`, `claude-code` (headless harness), `mcp-server-kit`,
  `llama-local`, `udocker`. Declarative YAML per entry (packages, files, ports, env,
  post-install script, required bridge scopes).
- **Schedules** — cron-with-conditions UI: *run machine X / command Y at TIME, only-if
  charging / wifi / temp < N*. Backed by WorkManager + the foreground service.
- **Keys** — API keys named and stored in **Android Keystore**, injected as env vars
  into a machine at start. Never written into a rootfs, never exported with a backup.
- **Bridge** — the per-machine permission matrix + audit log (§5).
- **Terminal** — full-screen ttyd WebView per machine, with a session picker.
- **Share** (later) — Tailscale/cloudflared integration for reachable URLs.

### 6.2 Service design

One foreground service owns everything long-running: engine invocations, running
proots, ttyd, the bridge server, llama.cpp. Notification shows live state (2 machines
up · 41 °C · agent `research` running). Process-tree budget stays far under the phantom
killer's 32.

### 6.3 Local models (optional, charging-gated)

llama.cpp built as a native arm64 binary in the APK, running *outside* proot, exposing
an OpenAI-compatible endpoint on localhost. Catalog ships 1–4 B quantized models with
honest speed/heat expectations. Default posture: cloud APIs for brains, local model as
the offline/private fallback — this is a phone, not a GPU rig.

## 7. Security model (one page, honest)

- Trust boundary = the app sandbox. Machines don't defend against each other (§4.1).
- Secrets: Android Keystore → env at start → gone at stop. Backups exclude them.
- Bridge: default-deny, per-machine scopes, rate limits on outbound actions (SMS,
  notifications), append-only audit log surfaced in the UI.
- Network: everything binds localhost by default; exposing a port is an explicit act.
- Threat we accept: user installs a hostile catalog stack → it can read other machines.
  Mitigation is curation + the audit log, until AVF gives us real walls.

## 8. Roadmap

| Milestone | Deliverable | Exit test |
|---|---|---|
| **M0 — repo & plan** | This document, repo, CI skeleton. | You're reading it. |
| **M1 — engine v0** | Go CLI: create/start/stop/exec/ls/export for Alpine + Debian, catalog `apply`, JSON everywhere. Developed and dogfooded under Termux on a real device. | A real coding-agent harness runs inside a Cellar machine on a phone for a week of daily use. |
| **M2 — app skeleton** | Compose app: machines list, create wizard, terminal, foreground service, engine embedded as jniLib. Built by GitHub Actions (never on-device). | Fresh phone, no Termux: install APK → running Debian machine with ssh in < 5 minutes. |
| **M3 — AI layer** | Keys (Keystore), catalog with agent stacks, schedules, bridge v1 (battery + notifications post/read) with audit log. | A scheduled agent runs at 02:00 while charging, reads nothing it wasn't granted, posts a summary notification. |
| **M4 — polish & ship** | Dashboard gauges, export/backup, llama-local, F-Droid metadata, Play submission attempt, README with benchmarks + demo GIFs. | Strangers install it from a store and file issues. |

Sequencing rule: **engine before app** (M1 gate). If the engine isn't pleasant to drive
from a terminal for a week, no amount of Compose fixes it.

## 9. Distribution & portfolio strategy

- Public repo from day one; the commit history *is* the portfolio.
- README gets: architecture diagram, honest benchmark table, demo GIFs, CI badge.
- Docs worth writing as standalone pieces (each one is a blog post): surviving the
  phantom process killer; the jniLibs W^X pattern; why proot is fine for agents;
  designing a permission-gated phone bridge.
- Play if they'll have us; F-Droid + GitHub releases regardless.

## 10. Open questions

1. AVF third-party API timeline — when can a normal app create VMs on non-Pixel
   hardware? (Determines when the isolation story gets real.)
2. Terminal: is ttyd-in-WebView good enough on soft keyboards, or does M2 need a native
   key-handling layer above it?
3. Catalog format: plain YAML vs. OCI-ish layers for machine stacks — start YAML, revisit
   if stacks start wanting composition.
4. Tailscale embed (tsnet/gomobile) vs. "install the Tailscale app and we detect it" —
   the lazy path may be the right v1.
