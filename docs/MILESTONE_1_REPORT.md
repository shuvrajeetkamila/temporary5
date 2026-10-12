# QATAR: THE PENINSULA — milestone 1 delivery report

**Deliverable: a directly installable Android APK containing the real country of Qatar.**

## Download

| | |
|---|---|
| **APK link** | https://github.com/shuvrajeetkamila/temporary5/releases/download/v0.1.0-m0/Qatar-The-Peninsula-v0.1.0-m0.apk |
| **File name** | `Qatar-The-Peninsula-v0.1.0-m0.apk` (with sidecar `…apk.sha256`) |
| **Bytes** | 14,148,317 |
| **SHA-256** | `701c7ffe767c3d723db829bc4331f8bee17267ef67847e90c9fa07c58eb5ef00` |
| **Release page** | https://github.com/shuvrajeetkamila/temporary5/releases/tag/v0.1.0-m0 |

Install with `adb install -r Qatar-The-Peninsula-v0.1.0-m0.apk` or by copying it to the device and
tapping it (allow installs from unknown sources). It is **debug-signed**: installable today, not a
store artefact.

## Build identity

| Field | Value |
|---|---|
| Package | `ai.arena.qatar.peninsula` |
| Version | `0.1.0-m0`, versionCode 1 |
| minSdk / targetSdk / compileSdk | 26 / 34 / 34 |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64` (universal, one APK) |
| Native engine | libGDX 1.12.1 backends present in `lib/`, `libgdx_native=present` |
| Signing | debug key, `signature=present` |
| Network | none requested — `offline_first=yes (no INTERNET permission requested)` |
| Package contents | `apk_entry_count=45`, `dex_files=10`, `world_assets=9`, `world_manifest=present`, `licenses_packaged=yes` |
| Zip integrity | `zip_integrity=ok` |

## CI evidence

| Run | Conclusion | Result |
|---|---|---|
| Build APK **38271736697** (commit `e5c7789`) | **success** | `UNIT_TEST_STATUS.txt status=PASSED` (`tests=33 skipped=0 failures=0 errors=0`), `APK_VALIDATION.txt validation=PASSED`, `QA_STATUS.txt status=PASSED`, release published |
| Build world data **38267878702** | **success** | `WORLD COVERAGE VALIDATION PASSED` |

Machine-readable evidence is committed on the branch: `artifacts/APK_VALIDATION.txt`,
`artifacts/UNIT_TEST_SUMMARY.txt`, `artifacts/UNIT_TEST_LOG.txt`, `artifacts/QA_STATUS.txt`,
`artifacts/BUILD_META.txt`, `artifacts/WORLD_COMPILE_LOG.txt`, and per-run
`artifacts/data/DATA_STATUS_<runid>.txt` / `DATA_RUN_LOG_<runid>.txt`.

The QA gate is fail-closed: a build that does not pass validation publishes nothing, leaves
`status=FAILED` for its own commit, and the release step only runs when the gate passes.

## Coverage confirmation (the whole country, not a scene)

The packaged bundle is validated against the packaged-country gate:

* **Land**: 11,502 km² total across 68 components; largest component **11,418 km²** (the peninsula
  itself), largest secondary component **70.7 km²** (Al Safliya), which also proves the regression
  that once leaked 7,175 km² of open Gulf into the mask cannot come back.
* **Islands**: **57** island records from the authoritative coastline, 55 components ≥ 2 ha.
* **Municipalities**: all 8 (Ad Dawhah, Al Rayyan, Al Wakrah, Al Daayen, Umm Salal, Al Khor &
  Al Thakhira, Al Shamal, Al Shahaniya) with Arabic and English names and in-grid hubs.
* **Extent**: 259 × 268 km grid at L0 300 m and L1 100 m (EPSG:32639 / UTM 39N), Doha at
  (553459.107, 2796656.102), land ≈ 157 km N–S × 86 km W–E, plus the water and airspace buffer.
* **Content**: 179,943 roads across 23 classes, 186,993 buildings, 28/32 checkpoints, land mask
  1,150,250 cells, SRTM heights 0–103 m.
* **Attribution**: `© OpenStreetMap contributors, ODbL 1.0` plus SRTM is carried in
  `manifest.json`, shown on the loading screen, and shipped in `assets/licenses/`.

## What is verified, and how

* Unit tests (33, green): geography (EPSG:32639 fixtures), physics (free fall, resting contact,
  friction, sleeping contract, CCD against a wall, determinism, configuration validation, material
  registry), text (Arabic reshaping), streaming (chunk activation, LOD budget, terrain sampling
  through the floating origin, origin rebase).
* The APK is opened as a zip and inspected: manifest, SDK levels, ABIs, native libraries, packaged
  world bundle with per-file SHA-256 digests, licences, signature.
* The world pipeline is gated twice: `tools/selftest_world.py` (fast local gate, also run in CI) and
  `tools/validate_world.py` (packaged-country gate on the compiled bundle).

## Honest limitations

1. **No physical-device test has been run.** Everything above was verified head-lessly in CI and by
   static inspection of the APK. Nobody has yet seen the app render on hardware; treat the first
   install as a smoke test, and expect the loading screen and the sky/ground renderer to be the parts
   most likely to need device-specific tuning.
2. **The render path is one opaque sky-and-ground pass.** Terrain materials are compiled and
   shipped, but vegetation, buildings, props, signage and traffic are not drawn yet.
3. **Vehicle, human, traffic, pedestrian and metro systems from the brief are not implemented.** The
   engine contracts they will use (force/torque bodies, fixed 60 Hz step, collision layers, streaming
   residency, floating origin) are in place and tested.
4. **Terrain resolution is 100 m at its finest level** from SRTM, so fine limestone detail is carried
   by material class and gradient rather than surveyed geometry.
5. **Streaming chunk payloads are not yet populated from the bundles at runtime** — the loader and
   its SHA-256 verification are the next engineering step, together with loading the shipped bitmap
   fonts (`assets/fonts/hud_small`, `hud_large`) so the HUD renders text.
6. **The world bundle is regenerated when the compiler changes** (fingerprinted in
   `android/assets/world/COMPILER_SHA256`), so a bundle can never silently come from older code.

## Reproducing this build

```bash
gh workflow run data.yml --repo shuvrajeetkamila/temporary5   # compile + validate the country, dispatch the APK build
# or, for a build on an existing bundle:
gh workflow run build.yml -f publish_release=true            # test, assemble, validate, publish
```

Locally: `python3 tools/selftest_world.py` (world gate), `./gradlew :core:test` (JVM tests),
`bash tools/validate_apk.sh <apk>` (APK gate). A local Android SDK and JDK 17 are required for the
Gradle build; CI is the authoritative build environment.
