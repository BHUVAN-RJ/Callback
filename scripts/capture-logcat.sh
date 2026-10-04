#!/usr/bin/env bash
#
# Capture Android logcat for analyzing Callback + ARCore native logs on the host.
# Requires: adb in PATH, USB debugging enabled.
#
# Usage:
#   ./scripts/capture-logcat.sh           # dump current ring buffer once → logs/logcat-<stamp>.txt
#   ./scripts/capture-logcat.sh --follow  # stream until Ctrl+C (also tee’s to file)
#   ./scripts/capture-logcat.sh --clear   # clear ring buffer before follow/dump (next flag)
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT/logs"
mkdir -p "$OUT_DIR"
STAMP="$(date +"%Y%m%d_%H%M%S")"
OUT="$OUT_DIR/logcat-${STAMP}.txt"
PKG="com.bhuvan.callback"

CLEAR=false
FOLLOW=false

for arg in "$@"; do
  case "$arg" in
    --clear|-c) CLEAR=true ;;
    --follow|-f) FOLLOW=true ;;
    -h|--help)
      echo "Usage: $0 [--clear] [--follow]"
      echo "  Writes under $OUT_DIR"
      exit 0
      ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found in PATH." >&2
  exit 1
fi

adb get-state >/dev/null 2>&1 || {
  echo "No adb device ready (adb devices)." >&2
  exit 1
}

if [[ "$CLEAR" == true ]]; then
  adb logcat -c || true
  echo "Logcat ring buffer cleared."
fi

if [[ "$FOLLOW" == true ]]; then
  echo "Streaming logcat → $OUT"
  echo "(Ctrl+C to stop)"
  adb logcat -v threadtime | tee "$OUT"
else
  # Prefer this process only when app is running (includes JNI/native threads).
  PID="$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  if [[ -n "${PID:-}" ]]; then
    echo "Dumping logcat for pid=$PID ($PKG) → $OUT"
    adb logcat -v threadtime -d --pid="$PID" >"$OUT"
  else
    echo "App not running; dumping full ring buffer → $OUT"
    adb logcat -v threadtime -d >"$OUT"
  fi
  echo "Done: $OUT"
fi
