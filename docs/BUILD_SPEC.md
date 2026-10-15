# QATAR: THE PENINSULA — build specification and acceptance record

This file records the acceptance criteria of the master build prompt for this project and the state of
each criterion, so that the criteria survive outside the chat that produced them. It is a
reconstruction of the operating requirements, not a substitute for reading the code.

Milestone framing used throughout: **M1 = a directly installable APK containing the real Qatar world
and the core engine contracts**, with the remaining systems layered on top of that base.

## 1. Deliverable

| Requirement | State |
|---|---|
| Directly installable Android `.apk` (not a plan, viewer, video or skeleton) | Shipped: `artifacts/Qatar-The-Peninsula-v0.1.0-m0.apk`, validated in CI, installed on a real phone; the app's own diagnostics panel reports every launch stage and every failure on the screen |
| minSdk API 26+ | `minSdk 26`, `targetSdk 34`, `compileSdk 34` |
| Application id `ai.example.qatar.peninsula` (project uses its own id of the same form) | `ai.arena.qatar.peninsula` |
| Universal debug-signed APK, arm64-v8a + armeabi-v7a + x86_64 | All three ABIs, debug key, `signature=present` |
| Launcher, loading, error, settings, credits, attribution screens | Launcher + loading screen + settings/credits/attribution resources present; the loading screen reports real stage progress |
| No proprietary imagery, music or branding | `ASSET_LICENSES.md`, `DATA_SOURCES.md`; no audio shipped |

## 2. Coverage

* **Entire State of Qatar at true 1:1 metre scale** — the compiled world is a metre-accurate grid over
  the country (259 × 268 km grid at 300 m L0 and 100 m L1, EPSG:32639) and contains **11,502 km² of
  land** across 68 components, 55 islands ≥ 2 ha, largest island
  70.7 km², plus the water and airspace buffer required by the brief.
* **All eight municipalities** (Ad Dawhah, Al Rayyan, Al Wakrah, Al Daayen, Umm Salal, Al Khor &
  Al Thakhira, Al Shamal, Al Shahaniya) are compiled with Arabic and English names and in-grid hubs.
* **Saudi land border and coastline** are part of the land derivation: the coast linework is stitched
  to the border ring, so the peninsula is closed and no sea component can masquerade as land. CI
  fails the build if a secondary component exceeds 400 km² (the regression that produced a 7,175 km²
  phantom island is guarded by `tools/selftest_world.py` and `tools/validate_world.py`).
* **Islands** come from the authoritative boundary dataset (OpenStreetMap coastline ways plus tagged
  island polygons), not from a hand-written list.
* **No generic desert placeholder**: the material grid is derived from OSM land use, coastline and
  SRTM elevation, so hamada, sand sheets, sabkha, tidal flats, mangroves, reclaimed land and urban
  classes all come from the real country.

## 3. Engine contracts already enforced by tests

* Fixed 60 Hz physics: `FixedStepClock.FIXED_DT = 1/60`, `MAX_SUBSTEPS = 4`, `MAX_FRAME_DT = 0.10`.
* Force/torque based motion: `RigidBody.applyForce`, `applyForceAtPoint`, `applyTorque`,
  `applyImpulse`, `applyDisturbingImpulse`; no direct position setting anywhere in gameplay paths.
* Sequential-impulse solver with friction, restitution threshold, speculative contact margin, rolling
  resistance, penetration slop, positional correction, and a sleeping contract that only a real
  disturbance breaks.
* Floating origin: `WorldOrigin`/double-precision global coordinates; `ChunkManager` streams LOD
  rings and collision residency around a focus point, rebases the origin when it drifts far, and the
  terrain sampler converts local metres to global metres in exactly one place.
* Determinism: the physics scenario checksum is reproducible; there is no wall-clock or RNG input.
* Geography: EPSG:32639 forward/inverse with fixtures (Doha, Al Ruwais, Dukhan), distance, bearing,
  convergence.
* Text: Arabic reshaping to presentation forms with lam-alef ligatures and correct visual order.

## 4. Data pipeline

* Python 3 deterministic compiler: `tools/fetch_sources.py` (sources + provenance),
  `tools/build_world.py` (single projection step, land derivation, bundles), `tools/validate_world.py`
  (packaged-country gate), `tools/selftest_world.py` (fast local gate), `tools/make_font.py`,
  `tools/selftest_game_world.py` + `tools/validate_game_world.py` (stage-2 bundle contracts),
  `tools/validate_apk.sh` (checks the artefact itself: bundles under either packaged name, `.fnt`
  section order, ABIs, licences, signature).
* Source gates that run without a JVM, which this build environment has no Java compiler for:
  `tools/check_java_symbols.py` (an unqualified JUnit assertion with no import -- the compiler's own
  error, in a second), `tools/check_shaders.py` (reserved words, version directive first, and the
  precision of every varying and shared uniform across each stage boundary),
  `tools/check_local_names.py` (a local declared twice in one scope),
  `tools/check_float_narrowing.py` (a double-typed expression assigned to a float, which Java will not
  convert) and `tools/check_ui_state.py` (2D drawing that does not disable the 3D depth test and face
  culling, which is how the controls became invisible on a phone). Each runs its own failure samples
  before it looks at the tree, and all six run first in the compile job; four of them exist because a
  red run, or a phone, taught the project something no local check could.

  Winding and handedness are checked where they can be: `BuildingRoofTest` derives every roof normal from
  the actual vertex positions with a real cross product, checks the ground as its control, and covers both
  ring directions and all five roof shapes. It is the test that would have caught the roofless country.
* Bundle formats `QTT1` (terrain), `QTM1` (material), `QTR1` (roads), `QTB1` (buildings), `QTC1`
  (checkpoints), `QTS1` (strings), little-endian, deterministic gzip. Row 0 is **south**.
* `SHA256SUMS` for sources and per-file digests inside `manifest.json`; the compiler writes a
  `COMPILER_SHA256` fingerprint and CI rebuilds the bundle whenever the compiler changes.
* Licensing: only redistributable sources; visible "© OpenStreetMap contributors".

## 5. Known limitations (honest status)

* The APK is debug-signed and is a milestone build, not a store release.
* Two physical-device runs have been made and both failed, for four reasons that are now fixed: a
  reserved word in a 300 es shader, a world bundle the Android packager stores without its `.gz` suffix,
  a `.fnt` whose section order made libGDX parse zero glyphs, and an unqualified uniform whose precision
  disagreed across the two stages. The app reports what it is doing and what breaks on the screen itself;
  the third install is the test. There is no GPU and no emulator here, so a phone is the only thing that
  compiles this project's GLSL.
* Vehicle, human, traffic, pedestrian and metro systems described in the brief are not implemented
  yet; the engine and world they will sit on are.
* Terrain is 100 m resolution at its finest level and the height field is SRTM-derived, so fine
  limestone detail is represented by material class and gradient, not by surveyed geometry.
* The render path is a single opaque sky-and-ground pass; vegetation, buildings and props are not
  drawn yet.
