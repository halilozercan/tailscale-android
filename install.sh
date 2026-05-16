#!/usr/bin/env bash
# Build the per-app-exit-node debug APK and install it on the connected
# adb device. Forces a clean rebuild of the libtailscale AAR so Go source
# changes are picked up (the Makefile doesn't track .go timestamps for the
# stripped .so).
set -euo pipefail

cd "$(dirname "$0")"

# Resolve ANDROID_HOME if the user hasn't set it
if [[ -z "${ANDROID_HOME:-}" ]]; then
  for d in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [[ -d "$d" ]]; then export ANDROID_HOME="$d"; break; fi
  done
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "ANDROID_HOME is not set and could not be auto-detected." >&2
  exit 1
fi

# Resolve adb
ADB="${ADB:-$(command -v adb || true)}"
if [[ -z "$ADB" ]]; then ADB="$ANDROID_HOME/platform-tools/adb"; fi
if [[ ! -x "$ADB" ]]; then
  echo "adb not found (tried PATH and $ANDROID_HOME/platform-tools/adb)" >&2
  exit 1
fi

# Fail fast if no device is attached
devices=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ -z "$devices" ]]; then
  echo "No adb device attached. Connect a device (or start an emulator) and retry." >&2
  "$ADB" devices >&2
  exit 1
fi

echo "==> Cleaning stripped artifacts to force gomobile rebuild"
rm -f libgojni.so.stripped libgojni.so.unstripped libgojni.so.debug \
      android/libs/libtailscale.aar android/libs/libtailscale_unstripped.aar

echo "==> Building debug APK"
ANDROID_HOME="$ANDROID_HOME" make tailscale-debug

echo "==> Installing on $(echo "$devices" | tr '\n' ' ')"
for d in $devices; do
  "$ADB" -s "$d" install -r -d tailscale-debug.apk
  # adb install swaps the APK but doesn't kill the running process; force-stop
  # so the next launch picks up the new code.
  "$ADB" -s "$d" shell am force-stop com.tailscale.ipn
done

echo "==> Done."
