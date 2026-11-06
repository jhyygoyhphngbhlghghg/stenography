#!/usr/bin/env bash
# Inspect sherpa-onnx Android AAR JNI on Linux x86_64 without an emulator.
# Does NOT replace on-device UnsatisfiedLinkError (needs Android bionic).
#
# Default AAR: JitPack v2.0.1 (current Gradle coordinate). After Origin PR #2
# lands, overnight's GitHub Releases v1.13.5 becomes canonical — override with:
#   SHERPA_AAR_URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/sherpa-onnx-1.13.5.aar
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EVIDENCE="${EVIDENCE_DIR:-$ROOT/artifacts/test-results}"
WORKDIR="${HOST_JNI_WORKDIR:-/tmp/neurosteno-host-jni-smoke}"
COORD="com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1"
JITPACK_URL="https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/v2.0.1/sherpa-onnx-v2.0.1.aar"
GH_1135_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.5/sherpa-onnx-1.13.5.aar"
mkdir -p "$EVIDENCE" "$WORKDIR"

START=$(date +%s%N)
JSON="$EVIDENCE/host-jni-smoke.json"
TXT="$EVIDENCE/host-jni-smoke.txt"

GRADLE_AAR=""
if [[ -d "$HOME/.gradle/caches" ]]; then
  GRADLE_AAR="$(find "$HOME/.gradle/caches" -path '*sherpa-onnx/v2.0.1*' -name 'sherpa-onnx-v2.0.1.aar' 2>/dev/null | head -n 1 || true)"
fi
TMP_AAR="/tmp/neurosteno-sherpa-aar/sherpa-onnx-v2.0.1.aar"

AAR=""
SOURCE=""
if [[ -n "${SHERPA_AAR:-}" && -f "${SHERPA_AAR}" ]]; then
  AAR="$SHERPA_AAR"
  SOURCE="SHERPA_AAR env"
elif [[ -n "${SHERPA_AAR_URL:-}" ]]; then
  AAR="$WORKDIR/downloaded.aar"
  SOURCE="curl ${SHERPA_AAR_URL}"
  echo "GET $SHERPA_AAR_URL"
  curl -L --retry 3 --fail -o "$AAR" -- "$SHERPA_AAR_URL"
elif [[ -n "$GRADLE_AAR" && -f "$GRADLE_AAR" ]]; then
  AAR="$GRADLE_AAR"
  SOURCE="gradle-cache JitPack $COORD"
elif [[ -f "$TMP_AAR" ]]; then
  AAR="$TMP_AAR"
  SOURCE="inspect-sherpa-aar.sh cache $TMP_AAR"
else
  AAR="$WORKDIR/sherpa-onnx-v2.0.1.aar"
  SOURCE="curl JitPack $JITPACK_URL"
  echo "GET $JITPACK_URL"
  curl -L --retry 3 --fail -o "$AAR" -- "$JITPACK_URL"
fi

BYTES=$(wc -c < "$AAR" | tr -d ' ')
SHA=$(sha256sum "$AAR" | awk '{print $1}')
echo "aar=$AAR"
echo "source=$SOURCE"
echo "bytes=$BYTES sha256=$SHA"
echo "post-#2 canonical (not used unless SHERPA_AAR_URL set): $GH_1135_URL"

EXTRACT="$WORKDIR/aar"
rm -rf "$EXTRACT"
mkdir -p "$EXTRACT"
unzip -qo "$AAR" -d "$EXTRACT"
mapfile -t SOS < <(find "$EXTRACT/jni" -name 'libsherpa-onnx-jni.so' | sort)
if [[ "${#SOS[@]}" -eq 0 ]]; then
  echo "FAIL: no libsherpa-onnx-jni.so in AAR" >&2
  exit 1
fi

SO=""
for cand in "${SOS[@]}"; do
  if [[ "$cand" == *"/x86_64/"* ]]; then
    SO="$cand"
    break
  fi
done
SO="${SO:-${SOS[0]}}"

FILE_OUT="$(file "$SO")"
NEEDED="$(readelf -d "$SO" | awk '/NEEDED/{gsub(/[\[\]]/,"",$NF); print $NF}' | paste -sd, -)"
SONAME="$(readelf -d "$SO" | awk '/SONAME/{gsub(/[\[\]]/,"",$NF); print $NF; exit}')"
JNI_SYMS="$(nm -D --defined-only "$SO" 2>/dev/null | awk '/ JNI_OnLoad$| Java_com_k2fsa_sherpa_onnx/{print $3}' | head -n 40)"
JNI_COUNT="$(printf '%s\n' "$JNI_SYMS" | grep -c . || true)"
HAS_ONLOAD=0
printf '%s\n' "$JNI_SYMS" | grep -q 'JNI_OnLoad' && HAS_ONLOAD=1 || true

DLOPEN_STATUS="skipped"
DLOPEN_MSG=""
DLOPEN_RC=""
CC_BIN=""
if command -v gcc >/dev/null 2>&1; then
  CC_BIN=gcc
elif command -v cc >/dev/null 2>&1; then
  CC_BIN=cc
fi
if [[ -n "$CC_BIN" ]]; then
  cat >"$WORKDIR/dlopen.c" <<'C'
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
int main(int argc, char **argv) {
  if (argc < 2) return 1;
  void *h = dlopen(argv[1], RTLD_NOW);
  if (!h) {
    fprintf(stderr, "%s\n", dlerror());
    return 2;
  }
  void *p = dlsym(h, "JNI_OnLoad");
  printf("dlopen_ok JNI_OnLoad=%p\n", p);
  dlclose(h);
  return 0;
}
C
  "$CC_BIN" -o "$WORKDIR/dlopen" "$WORKDIR/dlopen.c" -ldl
  set +e
  DLOPEN_MSG="$(
    LD_LIBRARY_PATH="$(dirname "$SO")${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
      "$WORKDIR/dlopen" "$SO" 2>&1
  )"
  DLOPEN_RC=$?
  set -e
  if [[ "$DLOPEN_RC" -eq 0 ]]; then
    DLOPEN_STATUS="ok"
  else
    DLOPEN_STATUS="fail_android_ndk_or_bionic"
  fi
else
  DLOPEN_STATUS="no_cc"
  DLOPEN_MSG="gcc/cc not found"
fi

# ctypes as a second opinion
PY_STATUS=""
PY_MSG=""
set +e
PY_MSG="$(
  python3 - "$SO" <<'PY' 2>&1
import ctypes, sys
path = sys.argv[1]
try:
    ctypes.CDLL(path)
    print("ctypes_ok")
except OSError as e:
    print("ctypes_fail:", e)
    sys.exit(2)
PY
)"
PY_RC=$?
set -e
if [[ "$PY_RC" -eq 0 ]]; then
  PY_STATUS="ok"
else
  PY_STATUS="fail"
fi

RESULT="PARTIAL"
if [[ "$HAS_ONLOAD" -eq 1 && "$JNI_COUNT" -gt 0 && "$DLOPEN_STATUS" == "ok" ]]; then
  RESULT="PASS"
elif [[ "$HAS_ONLOAD" -ne 1 || "$JNI_COUNT" -eq 0 ]]; then
  RESULT="FAIL"
else
  RESULT="PARTIAL"
fi

END=$(date +%s%N)
WALL_MS=$(( (END - START) / 1000000 ))

{
  echo "result=$RESULT"
  echo "command=./scripts/host-jni-smoke.sh"
  echo "wall_ms=$WALL_MS"
  echo "aar_source=$SOURCE"
  echo "aar=$AAR"
  echo "aar_bytes=$BYTES"
  echo "aar_sha256=$SHA"
  echo "coordinate=$COORD"
  echo "post_pr2_canonical_url=$GH_1135_URL"
  echo "so=$SO"
  echo "file=$FILE_OUT"
  echo "soname=$SONAME"
  echo "needed=$NEEDED"
  echo "jni_onload=$HAS_ONLOAD"
  echo "jni_symbol_count=$JNI_COUNT"
  echo "dlopen_status=$DLOPEN_STATUS"
  echo "dlopen_rc=${DLOPEN_RC:-}"
  echo "dlopen_msg=$DLOPEN_MSG"
  echo "ctypes_status=$PY_STATUS"
  echo "ctypes_msg=$PY_MSG"
  echo "note=Host dlopen of an Android NDK .so is expected to fail on glibc (bionic, liblog). This is not an on-device UnsatisfiedLinkError probe."
  echo
  echo "== JNI symbols (first 40) =="
  echo "$JNI_SYMS"
  echo
  echo "== all libsherpa-onnx-jni.so in AAR =="
  printf '%s\n' "${SOS[@]}"
} | tee "$TXT"

export HJNI_RESULT="$RESULT" HJNI_WALL="$WALL_MS" HJNI_SOURCE="$SOURCE" HJNI_AAR="$AAR" \
  HJNI_BYTES="$BYTES" HJNI_SHA="$SHA" HJNI_SO="$SO" HJNI_FILE="$FILE_OUT" \
  HJNI_SONAME="$SONAME" HJNI_NEEDED="$NEEDED" HJNI_ONLOAD="$HAS_ONLOAD" \
  HJNI_JNI_COUNT="$JNI_COUNT" HJNI_DL_ST="$DLOPEN_STATUS" HJNI_DL_RC="${DLOPEN_RC:-}" \
  HJNI_DL_MSG="$DLOPEN_MSG" HJNI_PY_ST="$PY_STATUS" HJNI_PY_MSG="$PY_MSG" \
  HJNI_COORD="$COORD" HJNI_GH="$GH_1135_URL" HJNI_JSON="$JSON"
python3 <<'PY'
import json, os
rc = os.environ.get("HJNI_DL_RC") or ""
payload = {
    "result": os.environ["HJNI_RESULT"],
    "command": "./scripts/host-jni-smoke.sh",
    "wall_ms": int(os.environ["HJNI_WALL"]),
    "aar_source": os.environ["HJNI_SOURCE"],
    "aar": os.environ["HJNI_AAR"],
    "aar_bytes": int(os.environ["HJNI_BYTES"]),
    "aar_sha256": os.environ["HJNI_SHA"],
    "gradle_coordinate": os.environ["HJNI_COORD"],
    "post_pr2_canonical_url": os.environ["HJNI_GH"],
    "so": os.environ["HJNI_SO"],
    "file": os.environ["HJNI_FILE"],
    "soname": os.environ["HJNI_SONAME"],
    "needed": [x for x in os.environ["HJNI_NEEDED"].split(",") if x],
    "jni_onload": os.environ["HJNI_ONLOAD"] == "1",
    "jni_symbol_count": int(os.environ["HJNI_JNI_COUNT"]),
    "dlopen_status": os.environ["HJNI_DL_ST"],
    "dlopen_rc": int(rc) if rc.lstrip("-").isdigit() else None,
    "dlopen_msg": os.environ.get("HJNI_DL_MSG", ""),
    "ctypes_status": os.environ["HJNI_PY_ST"],
    "ctypes_msg": os.environ.get("HJNI_PY_MSG", ""),
    "replaces_on_device_ule_probe": False,
}
out = os.environ["HJNI_JSON"]
with open(out, "w") as f:
    json.dump(payload, f, indent=2)
    f.write("\n")
print("wrote", out)
PY

echo "result=$RESULT wall_ms=$WALL_MS"
[[ "$RESULT" != "FAIL" ]]
