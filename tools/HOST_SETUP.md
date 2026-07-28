# Host setup — the Emerge dev cycle

The development lifecycle across the fleet, and how to reproduce the host after a
hardware failure. Committed to the repo on purpose: this file *is* the backup.

## Fleet

| Machine | Role |
|---|---|
| **former** (modern i7, 64 GB, RTX 4060) | **Host.** LLM (sporadic), build/test gate, dedicated game server, phone deploy. Linux, always-on. |
| **latitude** (i7-1165G7, 31 GB, MX450) | **Coding client.** IntelliJ / claude / opencode. Portable. Pushes to GitHub. |
| **Pixel 8a** | Lockstep thin client + APK target. Reached over tailscale. |
| Windows box / old AMD | Offline. Only worth waking if former's LLM-vs-build contention becomes real pain (then the i5 box becomes a dedicated game+build host and former goes LLM-only). |

GitHub (`git@github.com:stuartorchard8/Emerge.git`) is the sync hub — no single
"home" checkout. latitude pushes; former pulls and rebuilds. Because it's off-site
it doubles as the hardware-failure backstop.

## The loop

```
edit on latitude  →  git push  →  ssh former '~/emerge/tools/dev-cycle.sh [app]'
```

`dev-cycle.sh` (default app: `scavengers`) does: pull → gate → rebuild+restart the
dedicated server (scavengers) → install the APK on the attached phone. See the
script header for details.

### The gate (what "no regressions" means today)

- **scavengers** — `:apps:scavengers:core:jvmTest` (reducer determinism + wire-codec
  stability — the lockstep floor) + `:engine:sim:core:jvmTest`. ~6 s.
- **cyto** — `:apps:cyto:core:jvmTest` + the `agent-scripts/campaign-*.txt` playthroughs.
  ⚠️ **RED on main** right now (unfinished mitosis→divide rename + an un-rebaselined
  golden). `dev-cycle.sh cyto` will fail until that baseline is greened.

Perf and fun-factor are **not** gated — perf stays manual (`CytoBench`), fun stays
human playtest.

## One-time former setup

### 1. Toolchain
- JDK 17+ (whatever the Gradle build targets) and git.
- Clone the repo to `~/emerge` (the paths in `dev-cycle.sh` and the systemd unit
  assume `%h/emerge`).

### 2. tailscale (remote reach to former + the phone)
- `tailscale up` on former and on the phone; both on the same tailnet.
- This is what lets `ssh former` and the game server work from outside the home LAN.

### 3. adb wireless pairing to the phone  — ALREADY WORKING per Stu
- Documented here for rebuild-from-scratch. On the phone: Developer options →
  Wireless debugging → pair. On former: `adb pair <host:port>` then
  `adb connect <host:port>`. `adb devices` should list it as `device`.
- `dev-cycle.sh` skips the deploy step cleanly when no device is attached.

### 4. The dedicated server unit
See `emerge-scavengers-host.service` for the install commands. In short:
```
ln -sf ~/emerge/tools/emerge-scavengers-host.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now emerge-scavengers-host.service
loginctl enable-linger $USER
```
The server listens **:7777 (tcp)** and **:7778 (ws)**. Clients join with
`-Demerge.mode=join-local` (same host) or point `hostIp` at former's tailscale IP.
Make sure those ports are reachable over your tailnet.

## VERIFY-ON-FORMER checklist

Things proven on latitude but not yet on former (I can't reach former from that
session):

- [ ] `~/emerge/tools/dev-cycle.sh` runs clean end-to-end (pull → gate → server → phone).
- [ ] `adb devices` shows the Pixel; `installDebug` lands the APK.
- [ ] The systemd unit starts and survives a reboot (`loginctl enable-linger`).
- [ ] A `join` client on latitude/phone actually connects to former's server over tailscale.
- [ ] The `.build/scavengers-desktop/install/desktop/bin/desktop` path matches former's
      build dir (it's a custom `.build/` layout — confirmed on latitude).

## Known follow-ups

- **Green cyto's baseline** before `dev-cycle.sh cyto` is usable: finish the
  mitosis→divide rename (vocabulary + chapter copy) and re-baseline `CytoGoldenTest`
  after verifying the population trajectory.
- **Headless host config**: port/game-mode are fixed in `Main.jvm.kt`'s headless-host
  branch. Make them read `emerge.port` / `emerge.gamemode` if you want per-unit config.
- **Wider net coverage**: the scavengers gate covers the playerless server trajectory +
  wire codec. Player/crash-path determinism needs seedable spawns (currently
  `Random.Default`).
