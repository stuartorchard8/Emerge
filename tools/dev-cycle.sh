#!/usr/bin/env bash
# dev-cycle.sh — the edit→test→deploy loop, run on the host (former).
#
#   1. pull the latest code (GitHub is the sync hub; former is just another clone)
#   2. run the app's regression gate (fast, deterministic; aborts on any failure)
#   3. rebuild the dedicated server, restart it, and wait for it to actually
#      accept connections (scavengers only)
#   4. build + install the APK on the attached phone (wireless adb / tailscale),
#      then probe from the phone that the host is genuinely reachable
#
# Every stage names itself, so a failure reports which one broke.
#
# Usage:  tools/dev-cycle.sh [scavengers|cyto] [--launch]   (default: scavengers)
#
#   --launch   also start the app on the phone, pointed at this host. Off by
#              default: an automated relaunch can leave the GLSurfaceView paused
#              (black screen, stuck HANDSHAKING), and a manual join is more
#              reliable. Use it when you want a hands-off smoke test.
#
# Latitude side is just:  git push  &&  ssh former '~/emerge/tools/dev-cycle.sh'
#
# See tools/HOST_SETUP.md for the one-time former setup (tailscale, adb pairing,
# JDK, and the systemd user unit this script restarts).
set -euo pipefail

APP="scavengers"
LAUNCH=0
for arg in "$@"; do
  case "$arg" in
    --launch) LAUNCH=1 ;;
    scavengers|cyto) APP="$arg" ;;
    *) echo "unknown argument '$arg' (expected: scavengers | cyto | --launch)" >&2; exit 2 ;;
  esac
done

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"
GRADLE="./gradlew"

HOST_PORT=7777          # tcp, must match Main.jvm.kt's headless-host branch
HOST_WS_PORT=7778       # websocket

log() { printf '\n\033[1;36m[dev-cycle:%s]\033[0m %s\n' "$APP" "$*"; }
warn() { printf '\033[1;33m  ! %s\033[0m\n' "$*"; }

# Name each stage as we enter it, so a failure says *what* broke rather than
# leaving you to reverse-engineer it from a wall of gradle output.
STAGE="startup"
stage() { STAGE="$1"; }
on_exit() {
  local code=$?
  [ $code -eq 0 ] && return 0
  printf '\n\033[1;31m[dev-cycle:%s] FAILED during: %s (exit %d)\033[0m\n' "$APP" "$STAGE" "$code"
  return $code
}
trap on_exit EXIT

# 1 ─ pull ────────────────────────────────────────────────────────────────────
stage "git pull"
log "pulling latest"
git pull --ff-only

# 2 ─ gate ────────────────────────────────────────────────────────────────────
# The gate is the contract: "no regressions in covered features." It must stay
# fast (<1 min) and deterministic. Anything slow or flaky does not belong here.
stage "gate ($APP)"
case "$APP" in
  scavengers)
    log "gate: reducer determinism + wire-codec stability"
    $GRADLE :apps:scavengers:core:jvmTest :engine:sim:core:jvmTest
    ;;
  cyto)
    # NB: cyto's gate is currently RED on main (unfinished mitosis→divide rename +
    # an un-rebaselined golden). This will fail until that baseline is greened —
    # which is exactly the signal you want, not something to suppress.
    log "gate: unit/campaign/golden suite + scripted playthroughs"
    $GRADLE :apps:cyto:core:jvmTest
    for script in apps/cyto/agent-scripts/campaign-*.txt; do
      log "playthrough: $script"
      $GRADLE :apps:cyto:desktop:cytoAgent --args="$script"
    done
    ;;
esac

# 3 ─ rebuild + restart the dedicated server (scavengers only) ─────────────────
if [ "$APP" = "scavengers" ]; then
  stage "build dedicated server"
  log "building dedicated-server distribution"
  $GRADLE :apps:scavengers:desktop:installDist

  if systemctl --user list-unit-files emerge-scavengers-host.service >/dev/null 2>&1; then
    stage "restart + health-check headless host"
    log "restarting headless host"
    systemctl --user restart emerge-scavengers-host.service

    # A restart that "succeeds" proves nothing — the unit has Restart=on-failure,
    # so a crash-looping server still looks alive for a moment. Wait for the thing
    # that actually matters: the port accepting connections.
    listening=0
    for _ in $(seq 1 20); do
      if (exec 3<>"/dev/tcp/127.0.0.1/$HOST_PORT") 2>/dev/null; then
        exec 3>&- 3<&-
        listening=1
        break
      fi
      sleep 0.5
    done
    if [ "$listening" = 1 ]; then
      log "host is accepting connections on :$HOST_PORT"
    else
      warn "host is NOT listening on :$HOST_PORT after 10s — recent log:"
      journalctl --user -u emerge-scavengers-host.service -n 20 --no-pager || true
      exit 1
    fi

    if command -v tailscale >/dev/null 2>&1 && ! tailscale status >/dev/null 2>&1; then
      warn "tailscale is installed but down — only the LAN path will work."
    fi
  else
    log "SKIP server restart — emerge-scavengers-host.service not installed (see tools/HOST_SETUP.md)"
  fi
fi

# 4 ─ deploy to the phone ──────────────────────────────────────────────────────
# installDebug pushes to whatever adb device is attached (the phone, over
# wireless/tailscale). Skip cleanly when no device is connected.
if command -v adb >/dev/null 2>&1 && [ -n "$(adb devices | sed '1d' | grep -w device || true)" ]; then
  stage "install APK on phone"
  log "installing APK on phone"
  $GRADLE ":apps:$APP:android:installDebug"

  if [ "$APP" = "scavengers" ]; then
    stage "phone→host reachability"
    LAN_IP="$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") {print $(i+1); exit}}')"
    if [ -z "${LAN_IP:-}" ]; then
      warn "could not determine this host's LAN IP — skipping reachability probe"
    elif ! adb shell 'command -v nc' >/dev/null 2>&1; then
      warn "no nc on the phone — skipping reachability probe (join manually to verify)"
    else
      # The end-to-end question no local check can answer: can the *phone* open a
      # socket to the host? This is what ufw silently breaks.
      log "probing $LAN_IP:$HOST_PORT from the phone"
      if adb shell "nc -z -w 3 $LAN_IP $HOST_PORT" >/dev/null 2>&1; then
        log "phone can reach the host on :$HOST_PORT"
      else
        warn "phone CANNOT reach $LAN_IP:$HOST_PORT, though the host is listening locally."
        warn "Prime suspect is ufw. Run this on former yourself (this script never sudoes):"
        warn "  sudo ufw allow from 192.168.1.0/24 to any port $HOST_PORT,$HOST_WS_PORT proto tcp"
      fi
    fi

    if [ "$LAUNCH" = 1 ] && [ -n "${LAN_IP:-}" ]; then
      stage "launch app on phone"
      log "launching scavengers on the phone → $LAN_IP:$HOST_PORT"
      # "join" resolves to JOIN_IMPULSE — the only join mode that stays in sync on
      # Android (see LaunchMode's docs). Do not switch this to join-full.
      adb shell am start -n org.emerge.scavengers/.MainActivity \
        -e mode join -e hostIp "$LAN_IP" --ei port "$HOST_PORT" >/dev/null
      warn "if the screen stays black or stuck HANDSHAKING, relaunch by hand — an"
      warn "automated start can leave the GLSurfaceView paused."
    fi
  fi
else
  log "SKIP phone deploy — no adb device attached"
fi

log "done"
