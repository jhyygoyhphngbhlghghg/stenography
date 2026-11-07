#!/usr/bin/env bash
# Sideload stenographer debug APK onto a phone (Nothing Phone 3a) and capture
# SherpaJni.probe() logcat. Requires adb + USB/wireless debugging.
# Does NOT run on Cursor cloud VMs (no device). Do not claim a probe from CI.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EVIDENCE="${EVIDENCE_DIR:-$ROOT/artifacts/test-results}"
LOG="$EVIDENCE/device-jni-probe-logcat.txt"
SUMMARY="$EVIDENCE/device-jni-probe-summary.txt"
PROBE_SEC="${PROBE_SEC:-12}"
STENO_PKG="team.mikcoders.neurosteno"
PRESENT_PKG="team.mikcoders.neurosteno.present"
ACTIVITY="team.mikcoders.neurosteno/.MainActivity"
PRESENT_ACTIVITY="team.mikcoders.neurosteno.present/team.mikcoders.neurosteno.MainActivity"

STENO_DEFAULT="$ROOT/src/android/app/build/outputs/apk/stenographer/debug/app-stenographer-debug.apk"
PRESENT_DEFAULT="$ROOT/src/android/app/build/outputs/apk/presentConnect/debug/app-presentConnect-debug.apk"

usage_build() {
  cat <<'EOF'
APKs not found. Build them first, then re-run this script:

  cd src/android
  export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
  ./gradlew --no-daemon :app:assembleStenographerDebug
  ./gradlew --no-daemon :app:assemblePresentConnectDebug

Or pass paths:
  ./scripts/device-sideload-probe.sh /path/to/stenographer.apk [/path/to/presentConnect.apk]

Env: STENO_APK, PRESENT_APK, DEVICE_SERIAL, PRESENT=1, PUSH_MODELS=1, PROBE_SEC=12
EOF
}

find_adb() {
  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return
  fi
  for c in \
    "${ANDROID_HOME:-}/platform-tools/adb" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
    "$HOME/android-sdk/platform-tools/adb"
  do
    if [[ -n "$c" && -x "$c" ]]; then
      echo "$c"
      return
    fi
  done
  echo "need adb (install Android platform-tools or put adb on PATH)" >&2
  exit 1
}

ADB_BIN="$(find_adb)"
adb() {
  if [[ -n "${DEVICE_SERIAL:-}" ]]; then
    "$ADB_BIN" -s "$DEVICE_SERIAL" "$@"
  else
    "$ADB_BIN" "$@"
  fi
}

echo "==> adb: $ADB_BIN"
"$ADB_BIN" start-server >/dev/null
mapfile -t DEVICES < <("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ -n "${DEVICE_SERIAL:-}" ]]; then
  ok=0
  for d in "${DEVICES[@]+"${DEVICES[@]}"}"; do
    [[ "$d" == "$DEVICE_SERIAL" ]] && ok=1
  done
  if [[ "$ok" != 1 ]]; then
    echo "DEVICE_SERIAL=$DEVICE_SERIAL not in adb devices (state=device):" >&2
    "$ADB_BIN" devices -l >&2
    exit 1
  fi
else
  n="${#DEVICES[@]}"
  if [[ "$n" -eq 0 ]]; then
    echo "adb devices: no device in 'device' state. Plug in the Nothing Phone (3a) or set DEVICE_SERIAL." >&2
    "$ADB_BIN" devices -l >&2
    exit 1
  fi
  if [[ "$n" -gt 1 ]]; then
    echo "adb devices: $n devices; set DEVICE_SERIAL to pick one:" >&2
    "$ADB_BIN" devices -l >&2
    exit 1
  fi
  DEVICE_SERIAL="${DEVICES[0]}"
fi
echo "==> device: $DEVICE_SERIAL"

STENO_APK="${1:-${STENO_APK:-$STENO_DEFAULT}}"
PRESENT_APK="${2:-${PRESENT_APK:-$PRESENT_DEFAULT}}"
INSTALL_PRESENT=0
if [[ "${PRESENT:-0}" == "1" ]]; then
  INSTALL_PRESENT=1
fi
if [[ -n "${2:-}" ]]; then
  INSTALL_PRESENT=1
fi
if [[ -f "$PRESENT_APK" && "${PRESENT:-}" == "1" ]]; then
  INSTALL_PRESENT=1
fi

if [[ ! -f "$STENO_APK" ]]; then
  echo "missing stenographer APK: $STENO_APK" >&2
  usage_build
  exit 1
fi
if [[ "$INSTALL_PRESENT" == "1" && ! -f "$PRESENT_APK" ]]; then
  echo "PRESENT=1 but missing presentConnect APK: $PRESENT_APK" >&2
  usage_build
  exit 1
fi

mkdir -p "$EVIDENCE"
echo "==> install -r $STENO_APK"
adb install -r "$STENO_APK"
if [[ "$INSTALL_PRESENT" == "1" ]]; then
  echo "==> install -r $PRESENT_APK"
  adb install -r "$PRESENT_APK"
fi

# Optional models push — not required for JNI probe.
# PUSH_MODELS=1 copies ./models into the stenographer app filesDir tree on /sdcard.
if [[ "${PUSH_MODELS:-0}" == "1" ]]; then
  if [[ ! -d "$ROOT/models/asr" ]]; then
    echo "PUSH_MODELS=1 but $ROOT/models has no asr/ tree" >&2
    exit 1
  fi
  echo "==> adb push models/ (optional; not part of JNI pass/fail)"
  adb push "$ROOT/models" "/sdcard/Android/data/${STENO_PKG}/files/models"
fi

echo "==> logcat clear + force-stop + start $ACTIVITY (SherpaJni.probe in NeuroStenoApp)"
adb logcat -c || true
adb shell am force-stop "$STENO_PKG" || true
# Give the process a clean start so Application.onCreate runs the probe.
adb shell am start -W -n "$ACTIVITY" || adb shell monkey -p "$STENO_PKG" -c android.intent.category.LAUNCHER 1

echo "==> capturing logcat ${PROBE_SEC}s -> $LOG"
set +e
if command -v timeout >/dev/null 2>&1; then
  timeout "${PROBE_SEC}s" adb logcat -v threadtime \
    SherpaJni:V NeuroStenoApp:V NeuroSteno:V AndroidRuntime:E libc:E *:S \
    >"$LOG" 2>&1
  # Also dump a wider buffer in case tags were missed.
  adb logcat -d -v threadtime | grep -E 'SherpaJni|NeuroStenoApp|NeuroSteno|UnsatisfiedLinkError|sherpa-onnx-jni' >>"$LOG" || true
else
  sleep "$PROBE_SEC"
  adb logcat -d -v threadtime | grep -E 'SherpaJni|NeuroStenoApp|NeuroSteno|UnsatisfiedLinkError|sherpa-onnx-jni' >"$LOG" || true
fi
set -e

python3 - "$LOG" "$SUMMARY" "$STENO_APK" "${DEVICE_SERIAL}" "$PROBE_SEC" <<'PY'
import re, sys, time
from pathlib import Path
log, summary, apk, serial, probe_sec = sys.argv[1:]
text = Path(log).read_text(errors="replace") if Path(log).exists() else ""
loaded = bool(re.search(r"loaded libsherpa-onnx-jni\.so|sherpa-onnx-jni loaded=true", text))
ule = bool(re.search(r"UnsatisfiedLinkError|sherpa-onnx-jni loaded=false", text))
if loaded and not ule:
    result = "PASS"
elif ule and not loaded:
    result = "FAIL"
elif loaded and ule:
    result = "FAIL"
else:
    result = "UNKNOWN"
lines = [
    f"result={result}",
    f"device_serial={serial}",
    f"apk={apk}",
    f"probe_sec={probe_sec}",
    f"log={log}",
    f"loaded_marker={loaded}",
    f"unsatisfied_link_error={ule}",
    "note=JNI probe only; models not required. Demo mic/ASR is a separate step.",
]
Path(summary).write_text("\n".join(lines) + "\n")
print("\n".join(lines))
sys.exit(0 if result == "PASS" else 1)
PY
