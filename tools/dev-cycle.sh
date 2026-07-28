#!/usr/bin/env bash
# dev-cycle.sh — the edit→test→deploy loop, run on the host (former).
#
#   1. pull the latest code (GitHub is the sync hub; former is just another clone)
#   2. run the app's regression gate (fast, deterministic; aborts on any failure)
#   3. rebuild the dedicated server and restart it (scavengers only)
#   4. build + install the APK on the attached phone (wireless adb / tailscale)
#
# Usage:  tools/dev-cycle.sh [scavengers|cyto]      (default: scavengers)
#
# Latitude side is just:  git push  &&  ssh former '~/emerge/tools/dev-cycle.sh'
#
# See tools/HOST_SETUP.md for the one-time former setup (tailscale, adb pairing,
# JDK, and the systemd user unit this script restarts).
set -euo pipefail

APP="${1:-scavengers}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"
GRADLE="./gradlew"

log() { printf '\n\033[1;36m[dev-cycle:%s]\033[0m %s\n' "$APP" "$*"; }

# 1 ─ pull ────────────────────────────────────────────────────────────────────
log "pulling latest"
git pull --ff-only

# 2 ─ gate ────────────────────────────────────────────────────────────────────
# The gate is the contract: "no regressions in covered features." It must stay
# fast (<1 min) and deterministic. Anything slow or flaky does not belong here.
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
  *)
    echo "unknown app '$APP' (expected: scavengers | cyto)" >&2
    exit 2
    ;;
esac

# 3 ─ rebuild + restart the dedicated server (scavengers only) ─────────────────
if [ "$APP" = "scavengers" ]; then
  log "building dedicated-server distribution"
  $GRADLE :apps:scavengers:desktop:installDist
  if systemctl --user list-unit-files emerge-scavengers-host.service >/dev/null 2>&1; then
    log "restarting headless host"
    systemctl --user restart emerge-scavengers-host.service
    systemctl --user --no-pager status emerge-scavengers-host.service | head -5 || true
  else
    log "SKIP server restart — emerge-scavengers-host.service not installed (see tools/HOST_SETUP.md)"
  fi
fi

# 4 ─ deploy to the phone ──────────────────────────────────────────────────────
# installDebug pushes to whatever adb device is attached (the phone, over
# wireless/tailscale). Skip cleanly when no device is connected.
if command -v adb >/dev/null 2>&1 && [ -n "$(adb devices | sed '1d' | grep -w device || true)" ]; then
  log "installing APK on phone"
  $GRADLE ":apps:$APP:android:installDebug"
else
  log "SKIP phone deploy — no adb device attached"
fi

log "done"
