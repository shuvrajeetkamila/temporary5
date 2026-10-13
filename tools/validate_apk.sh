#!/usr/bin/env bash
# APK validation: structure, manifest, ABIs, packaged world data, checksums, signature.
# Fails (non-zero) on any missing required country resource, and prints a machine readable report so
# CI can persist evidence for the exact commit that produced this APK.
set -uo pipefail

APK="${1:?usage: validate_apk.sh <apk> [output-report]}"
REPORT="${2:-}"
FAILURES=0
LINES=()

add() { LINES+=("$1"); }
fail() { FAILURES=$((FAILURES + 1)); LINES+=("FAIL: $1"); }

if [[ ! -f "$APK" ]]; then
  echo "FAIL: APK not found: $APK" >&2
  exit 2
fi

BYTES=$(stat -c%s "$APK")
SHA=$(sha256sum "$APK" | awk '{print $1}')
add "apk_path=$APK"
add "apk_bytes=$BYTES"
add "apk_sha256=$SHA"

if unzip -tq "$APK" >/dev/null 2>&1; then
  add "zip_integrity=ok"
else
  fail "zip integrity check failed"
fi

if command -v aapt2 >/dev/null 2>&1; then
  BADGING=$(aapt2 dump badging "$APK" 2>/dev/null)
  add "manifest_package=$(echo "$BADGING" | grep -m1 '^package:')"
  add "manifest_sdk=$(echo "$BADGING" | grep -m1 'sdkVersion' | tr -d "'")"
  add "manifest_target_sdk=$(echo "$BADGING" | grep -m1 'targetSdkVersion' | tr -d "'")"
  echo "$BADGING" | grep -m1 "launchable-activity" | sed 's/^/  /'
  if ! echo "$BADGING" | grep -q "launchable-activity"; then
    fail "no launchable activity in manifest"
  fi
  if echo "$BADGING" | grep -q "uses-permission: name='android.permission.INTERNET'"; then
    fail "APK requests INTERNET permission (the game must be offline-first)"
  else
    add "offline_first=yes (no INTERNET permission requested)"
  fi
else
  add "warning: aapt2 unavailable, manifest check skipped"
fi

NATIVE_ABIS=$(unzip -l "$APK" | grep -o "lib/[a-z0-9_-]*/" | sort -u | sed 's|lib/||;s|/||' | tr '\n' ' ')
add "native_abis=$NATIVE_ABIS"
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

DEX_COUNT=$(unzip -l "$APK" | grep -cE "classes[0-9]*\.dex")
add "dex_files=$DEX_COUNT"
if [[ "$DEX_COUNT" -lt 1 ]]; then
  fail "no dex files in APK"
fi

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

# Signature: APK Signature Scheme v2/v3 lives in the signing block; v1 uses META-INF entries.
SIGNED=no
if command -v apksigner >/dev/null 2>&1; then
  if apksigner verify "$APK" >/dev/null 2>&1; then SIGNED=yes; fi
fi
if [[ "$SIGNED" == "no" ]] && unzip -l "$APK" | grep -qE "META-INF/.*\.(RSA|SF|DSA)"; then
  SIGNED=yes
fi
if [[ "$SIGNED" == "no" ]] && grep -aq "APK Sig Block 42" "$APK"; then
  SIGNED=yes
fi
if [[ "$SIGNED" == "yes" ]]; then
  add "signature=present"
else
  fail "APK is not signed"
fi

add "apk_entry_count=$(unzip -l "$APK" | tail -1 | awk '{print $2}')"
BIGGEST=$(unzip -l "$APK" | awk 'NR>3 && NF>=4 {printf "%s %s\n", $1, $4}' | sort -rn | head -5 | tr '\n' ';')
add "largest_entries=$BIGGEST"

echo "$SHA  $(basename "$APK")" > "${APK}.sha256"
add "checksum_sidecar=${APK}.sha256"

if [[ "$FAILURES" -eq 0 ]]; then
  add "validation=PASSED"
else
  add "validation=FAILED (${FAILURES} failure(s))"
fi

for line in "${LINES[@]}"; do
  echo "$line"
done

if [[ -n "$REPORT" ]]; then
  printf '%s\n' "${LINES[@]}" > "$REPORT"
fi

exit "$FAILURES"
