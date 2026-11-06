#!/usr/bin/env bash
# Resolve sherpa-onnx v2.0.1 AAR from JitPack and list packaged JNI .so files.
# Does not require Android SDK. Does not commit the AAR.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${SHERPA_AAR_DIR:-/tmp/neurosteno-sherpa-aar}"
EVIDENCE="${EVIDENCE_DIR:-$ROOT/artifacts/test-results}"
COORD="com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1"
AAR_URL="https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/v2.0.1/sherpa-onnx-v2.0.1.aar"
POM_URL="https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/v2.0.1/sherpa-onnx-v2.0.1.pom"
mkdir -p "$OUT" "$EVIDENCE"
AAR="$OUT/sherpa-onnx-v2.0.1.aar"

echo "coordinate: $COORD"
echo "GET $AAR_URL"
start=$(date +%s%N)
code=$(curl -L --retry 3 --fail -o "$AAR" -w "%{http_code}" -- "$AAR_URL")
end=$(date +%s%N)
wall=$(( (end - start) / 1000000 ))
bytes=$(wc -c < "$AAR" | tr -d ' ')
sha=$(sha256sum "$AAR" | awk '{print $1}')
echo "http=$code bytes=$bytes wall_ms=$wall sha256=$sha"

LISTING="$EVIDENCE/sherpa-aar-listing.txt"
{
  echo "coordinate: $COORD"
  echo "url: $AAR_URL"
  echo "pom: $POM_URL"
  echo "http_code: $code"
  echo "bytes: $bytes"
  echo "wall_ms: $wall"
  echo "sha256: $sha"
  echo
  echo "== unzip -l (jni/*.so and classes) =="
  unzip -l "$AAR" | sed -n '1,8p'
  echo "..."
  unzip -l "$AAR" | awk '/jni\/|\.so$|classes.jar|AndroidManifest|jni/{print}'
  echo
  echo "== ABI summary =="
  unzip -l "$AAR" | awk '/jni\/.*\.so$/ {print $4}' | awk -F/ '{print $2}' | sort | uniq -c
} | tee "$LISTING"

python3 - "$EVIDENCE/sherpa-aar-resolve.json" "$COORD" "$AAR_URL" "$code" "$bytes" "$wall" "$sha" "$AAR" <<'PY'
import json, subprocess, sys
out, coord, url, code, bytes_, wall, sha, aar = sys.argv[1:]
sos = subprocess.check_output(["unzip", "-Z", "-1", aar], text=True)
so_files = [l for l in sos.splitlines() if l.endswith(".so")]
payload = {
    "coordinate": coord,
    "url": url,
    "http_code": int(code),
    "bytes": int(bytes_),
    "wall_ms": int(wall),
    "sha256": sha,
    "result": "PASS" if code == "200" and so_files else "FAIL",
    "jni_so": so_files,
}
with open(out, "w") as f:
    json.dump(payload, f, indent=2)
    f.write("\n")
print("wrote", out)
print("jni count", len(so_files))
PY
