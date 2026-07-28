# Host setup — the Emerge dev cycle

The development lifecycle across the fleet, and how to reproduce the host after a
hardware failure. Committed to the repo on purpose: this file *is* the backup.

## Fleet

| Machine | Role |
|---|---|
| **former** (modern i7, 64 GB, RTX 4060) | **Host.** LLM (sporadic), build/test gate, dedicated game server, phone deploy. Linux, always-on. |
| **latitude** (i7-1165G7, 31 GB, MX450) | **Coding client.** IntelliJ / claude / opencode. Portable. Pushes to GitHub. |
| **Pixel 8a** | Lockstep thin client + APK target. Reached over **wireless adb on the LAN** (tailscale is the out-of-home path, and is currently down — see Known follow-ups). |
| Windows box / old AMD | Offline. Only worth waking if former's LLM-vs-build contention becomes real pain (then the i5 box becomes a dedicated game+build host and former goes LLM-only). |

GitHub (`git@github.com:stuartorchard8/Emerge.git`) is the sync hub — no single
"home" checkout. latitude pushes; former pulls and rebuilds. Because it's off-site
it doubles as the hardware-failure backstop.

## The loop

```
edit on latitude  →  git push  →  ssh former '~/emerge/tools/dev-cycle.sh [app]'
```

`dev-cycle.sh [app] [--launch]` (default app: `scavengers`) does:

1. **pull** — and if that pull changed `dev-cycle.sh` itself, re-exec so the new
   version is what actually runs. Without this, bash keeps executing the stale
   bytes it already buffered, so the first run after every edit to this script
   silently tested the *previous* version.
2. **gate** — see below. Aborts the run on failure.
3. **server** (scavengers) — rebuild the distribution, restart the unit, then poll
   `:7777` until it genuinely accepts a connection. `systemctl restart` succeeding
   proves nothing on its own: `Restart=on-failure` means a crash-looping server
   still looks alive. On timeout it dumps `journalctl` and fails.
4. **phone** — `installDebug` onto the attached device, then probe `:7777` *from the
   phone* with `nc -z`. That is the only check that catches the ufw trap below.
   `--launch` additionally starts the app pointed at the host, presses START (reading
   the button out of the view hierarchy, since `am start` only lands on the launcher
   with the fields pre-filled), and waits until the client logs `conn=CONNECTED` —
   a hands-off smoke test of the whole path. Opt-in, because an automated start can
   leave the GLSurfaceView paused (black, stuck HANDSHAKING).

Every stage names itself, so a failure reports which one broke instead of leaving
you to read it out of a wall of Gradle output. Legs skip cleanly when their
dependency is absent (no phone, no systemd unit).

### The gate (what "no regressions" means today)

- **scavengers** — `:apps:scavengers:core:jvmTest` (reducer determinism + wire-codec
  stability — the lockstep floor) + `:engine:sim:core:jvmTest`. ~6 s.
- **cyto** — `:apps:cyto:core:jvmTest` + the `agent-scripts/campaign-*.txt` playthroughs.
  The playthroughs drive the real game and need a **GL context**: over ssh there is no
  `DISPLAY` and GLFW init fails outright, so the script borrows former's logged-in
  session on `:0` (which puts a window on that screen). With no display at all it skips
  them loudly rather than reporting a clean pass. `xvfb` would give a headless context
  but is not installed.

Both gates **GREEN on main, verified end-to-end on former 2026-07-28.**

A re-run with no source change is a Gradle **cache hit** — the gate reports success in
~300 ms without executing a single test. That is sound when the inputs Gradle tracks
are the only ones that matter, and it is the reason the loop is fast. It is *not* sound
for a test that reads something outside the build graph: `WeldInspect` failed on former
and passed on latitude purely on whether a local save file existed, and a cached pass
would have hidden that. Use `--rerun-tasks` when you want the gate to actually run.

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
The server listens **:7777 (tcp)** and **:7778 (ws)**. Desktop clients join with
`-Demerge.mode=join-local` (same host); otherwise point `hostIp` at former
(`192.168.1.141` on the LAN, or its tailscale IP from outside).

### ⚠️ The phone must join with JOIN_IMPULSE, not JOIN

This was the whole of the long-running "mobile can't join" symptom, and it was never
a network problem. `LaunchMode.JOIN` re-simulates forces on the client via the full
reducer, so phone and host must agree bit-for-bit — which holds JVM-to-JVM but not on
Android. `JOIN_IMPULSE` computes forces host-side and ships impulses, so the client
never has to reproduce that math.

`JOIN_IMPULSE` is now the default everywhere that picks for you (`LaunchSettings.mode`,
and the `mode=join` intent extra), so this mostly matters when choosing by hand in the
launcher spinner. `MODE_JOIN_FULL` ("join-full") selects the full-reducer path
deliberately, for comparisons.

### ⚠️ ufw blocks the game ports

ufw is **active** on former. The server binds `*:7777/*:7778` and localhost works fine,
so everything looks healthy from the host — while the phone's packets are dropped and
it simply never connects. The rule:

```
sudo ufw allow from 192.168.1.0/24 to any port 7777,7778 proto tcp
sudo ufw allow in on tailscale0 to any port 7777,7778 proto tcp   # out-of-home
```

Run it on former yourself — `dev-cycle.sh` never sudoes; it only diagnoses and prints
the command. Currently **in place and verified**: both ports were reachable from
latitude over the LAN on 2026-07-28.

## VERIFY-ON-FORMER checklist

Checked on former 2026-07-28 — each box records what was actually observed, not what
was assumed.

- [x] `dev-cycle.sh` runs clean end-to-end for **both** apps (pull → gate → server →
      phone-leg skip). Both gates green.
- [x] The systemd unit starts and survives a reboot — `Linger=yes`, unit `enabled` +
      `active`.
- [x] The `.build/scavengers-desktop/install/desktop/bin/desktop` path matches former's
      build dir.
- [x] The server is listening: `ss -ltn` shows `*:7777` and `*:7778` bound by the unit's
      JVM.
- [x] A LAN client reaches the game ports through ufw — both confirmed open from
      latitude (`192.168.1.164` → `192.168.1.141`).
- [x] The phone joins former's dedicated host and plays (2026-07-28, via `JOIN_IMPULSE`).
- [x] `adb devices` shows the Pixel; `installDebug` lands the APK ("Installed on 1
      device").
- [x] The `nc` reachability probe works *and discriminates* — reachable on `:7777`,
      correctly NOT reachable for a closed port and an unroutable host. A probe that
      only ever passes would be worse than none.
- [x] `--launch` runs the whole path hands-off from a force-stopped app: install →
      probe → start → press START → `conn=CONNECTED pid=PlayerId(value=1)` with the
      tick advancing.
- [x] The launcher opens on **JOIN_IMPULSE**, host `192.168.1.141`, port 7777 —
      confirming the mode default and the LAN-IP derivation land on-device.
- [ ] Anything works from **outside** the LAN. tailscale is down (below), so the
      out-of-home path is currently unverified.

## Known follow-ups

- **tailscale is down on former** — `tailscaled` is running but `tailscale status`
  reports stopped, and there is no `tailscale0` interface. Only the LAN path works;
  `tailscale up` on former (and the phone) restores the out-of-home path.
- **The golden digest is coupled to action *spellings*.** `biology` hashes
  `GeneCodec.serialize(genome)`, so the mitosis→divide rename masqueraded as sim drift
  with the trajectory byte-identical. Hashing ordinals instead of names would end that
  class of false alarm for good; until then, suspect a rename first when only `biology`
  moves. See the re-baseline notes in `CytoGoldenTest.kt`.
- **No headless GL on former** — the cyto playthroughs borrow the logged-in `:0`
  session, so a run paints a window on former's screen and depends on someone being
  logged in. Installing `xvfb` would make that leg properly headless.
- **Headless host config**: port/game-mode are fixed in `Main.jvm.kt`'s headless-host
  branch. Make them read `emerge.port` / `emerge.gamemode` if you want per-unit config.
- **Wider net coverage**: the scavengers gate covers the playerless server trajectory +
  wire codec. Player/crash-path determinism needs seedable spawns (currently
  `Random.Default`).
