#!/usr/bin/env bash
# APK validation: structure, manifest, ABIs, packaged world data, checksums.
# Fails the build on any missing required country resource. Prints a report to stdout so CI can
# persist it as evidence for the current commit only (never a stale PASSED file).
set -uo pipefail

APK="${1:?usage: validate_apk.sh <apk> [output-report]}"
REPORT="${2:-/dev/stdout}"
FAILURES=0
declare -a LINES

log() { echo "$1"; }
add() { LINES+=("$1"); }
fail() { FAILURES=$((FAILURES + 1)); add "FAIL: $1"; }

if [[ ! -f "$APK" ]]; then
  echo "FAIL: APK not found: $APK" >&2
  exit 2
fi

BYTES=$(stat -c%s "$APK")
SHA=$(sha256sum "$APK" | awk '{print $1}')
add "apk_path=$APK"
add "apk_bytes=$BYTES"
add "apk_sha256=$SHA"

# --- ZIP integrity ---
if unzip -tq "$APK" >/dev/null 2>&1; then
  add "zip_integrity=ok"
else
  fail "zip integrity check failed"
fi

# --- manifest ---
if command -v aapt2 >/dev/null 2>&1; then
  AAPT_BADGING=$(aapt2 dump badging "$APK" 2>/dev/null)
  PKG_LINE=$(echo "$AAPT_BADGING" | grep -m1 "^package:")
  add "manifest_package=$PKG_LINE"
  echo "$AAPT_BADGING" | grep -m1 "sdkVersion" | sed 's/^/  /'
  echo "$AAPT_BADGING" | grep -m1 "targetSdkVersion" | sed 's/^/  /'
  if ! echo "$AAPT_BADGING" | grep -q "launchable-activity"; then
    fail "no launchable activity in manifest"
  fi
  if ! echo "$AAPT_BADGING" | grep -q "application-label-ar"; then
    add "warning: Arabic application label not found in manifest"
  fi
else
  add "warning: aapt2 unavailable, manifest check skipped"
fi

# --- native ABIs ---
NATIVE_ABIS=$(unzip -l "$APK" | grep -o "lib/[a-z0-9_-]*/" | sort -u | sed 's|lib/||;s|/||')
add "native_abis=$(echo "$NATIVE_ABIS" | tr '\n' ' ')"
for abi in arm64-v8a armeabi-v7a x86_64; do
  if ! echo "$NATIVE_ABIS" | grep -q "$abi"; then
    fail "missing native ABI: $abi"
  fi
done
if unzip -l "$APK" | grep -q "libgdx.so"; then
  add "libgdx_native=present"
else
  fail "libgdx native library missing from APK"
fi

# --- dex ---
DEX_COUNT=$(unzip -l "$APK" | grep -c "classes.*\.dex")
add "dex_files=$DEX_COUNT"
if [[ "$DEX_COUNT" -lt 1 ]]; then
  fail "no dex files in APK"
fi

# --- packaged world data ---
WORLD_ENTRIES=$(unzip -l "$APK" | grep -c "assets/world/" || true)
add "world_assets=$WORLD_ENTRIES"
if [[ "$WORLD_ENTRIES" -lt 1 ]]; then
  fail "no packaged world assets found"
fi
if unzip -l "$APK" | grep -q "assets/world/manifest.json"; then
  add "world_manifest=present"
else
  fail "world manifest missing from APK (packaged-country startup would fail)"
fi
if unzip -l "$APK" | grep -q "assets/licenses/"; then
  add "licenses_packaged=yes"
else
  add "warning: licenses not packaged"
fi

# --- signature ---
if unzip -l "$APK" | grep -q "META-INF/.*\.RSA\|META-INF/.*\.SF\|META-INF/.*\.DSA"; then
  add "signature=present"
else
  fail "APK is not signed"
fi

# --- checksum sidecar ---
echo "$SHA  $(basename "$APK")" > "${APK}.sha256"
add "checksum_sidecar=${APK}.sha256"

if [[ "$FAILURES" -eq 0 ]]; then
  add "validation=PASSED"
else
  add "validation=FAILED (${FAILURES} failure(s))"
fi

{
  for line in "${LINES[@]}"; do
    log "$line"
  done
} > "$REPORT" 2>/dev/null || true

if [[ "$REPORT" != "/dev/stdout" ]]; then
  cat "$REPORT"
fi

exit "$FAILURES"
