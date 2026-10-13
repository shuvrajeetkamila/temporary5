# QATAR: THE PENINSULA

A realistic 3D Android game built on the **real country of Qatar**: the whole peninsula, every street,
every mapped building, the islands, the coastline, the desert — compiled from open data into a world
you can walk, drive, fly and sail through.

## Status

| Milestone | State |
|---|---|
| **Step 1 — the whole country in 3D** | Done. The compiler builds the real country in CI — 186,993 building footprints, 179,943 road ways, 5,784 land-use polygons, 1,130 water polygons, 421 barriers, 2,254 points of interest, 11,502 km² of land — and the game streams it as 500 m chunks of 3D geometry with a floating origin. |
| **Step 2 — human, car, aeroplane, helicopter, boat, interactable map** | Done. Five bodies with real physics (a 1.62 t car on a 2.79 m wheelbase, an 11 m wing that stalls and recovers, rotor thrust with collective lag, Froude-number hull resistance), switched from the HUD and driven through the compiled terrain. Buildings are solid: a walker refuses the step, a car stops at the wall. |
| **Step 3 — windows, bodies, assets** | Partly done, and honest about the rest. Walls band their glazing procedurally in metres from the compiled heights, roads are drawn with painted lane markings, barriers extrude with the buildings — and the player now has a body: a person, a car, an aeroplane, a helicopter and a boat, built in metres from the dimensions of the real machines and painted per part from a palette in the shader. Still open: doors, roof clutter, kerbs, palms, and moving parts (wheels that turn, rotors that spin). |
| **Step 4 — minimap, buttons, press-to-teleport map** | Done. A minimap in the top-right, a button for every locomotion type, and the map is interactable: pressing it opens the big map, which zooms about the fingers, and touching a place teleports the whole stable of vehicles there — to a road or a named place, never into the sea. |
| **Step 5 — the installable APK** | Done and green in CI: debug-signed, `arm64-v8a`/`armeabi-v7a`/`x86_64`, no INTERNET permission, with the country inside it. Published on the rolling release `v0.1.0-m0` with its byte count and SHA-256. What remains is what only a device can answer: the shaders have never been compiled by a real driver. |

Honest limits, so nobody has to find them the hard way: the first on-device run is also the first time the
GLSL is compiled (there is no GPU in CI), there is no sound, buildings collide as boxes rather than as
their drawn ring, and the world stops being drawn beyond the streaming radius around the player. See
`docs/PLAN.md` for the running list.

## Install it

Download and install on an Android phone (6.0+):

**[Qatar-The-Peninsula-v0.1.0-m0.apk](https://github.com/shuvrajeetkamila/temporary5/releases/download/v0.1.0-m0/Qatar-The-Peninsula-v0.1.0-m0.apk)** —
19,073,854 bytes, SHA-256 `73e3f49da68a4ee80cea1c45d900cfe464995ac7ad33b727d425068c09956520`
(the sidecar `.sha256` is next to it). Debug-signed, so Android will ask you to allow installation from
this source. No network permission: the country is inside the package.

Controls: drag to look, drag on the lower-left to move, the buttons on the right open the map (tap a
place on it to be put there), and `VIEW` switches between the driver's eyes and a camera behind the
machine. The status line and every control are in English and Arabic, and the data credit
(© OpenStreetMap contributors, ODbL; elevation from SRTM) is on the map screen and at launch. The full report, including what is honestly not finished, is in
[docs/FINAL_REPORT.md](docs/FINAL_REPORT.md).

## How it works

* `tools/fetch_sources.py` — downloads the OpenStreetMap extract for Qatar and the SRTM elevation tiles,
  and records the URL, timing, size, licence and SHA-256 of each input in `data/geodata/SOURCES.json`.
* `tools/build_world.py` — stage 1: projects everything into EPSG:32639 (UTM 39N) in a single documented
  step, derives the land mask from the coastline sealed against the national border, and packs the
  terrain, surface materials, road network and string table.
* `tools/game_world.py` — stage 2: real building footprints with heights and roof shapes, land use,
  water to carve, barriers and points of interest (including a spawn point for every vehicle type).
* `tools/validate_world.py`, `tools/validate_game_world.py`, `tools/selftest_world.py`,
  `tools/selftest_game_world.py` — the gates: nothing reaches an APK that has not been proven to
  describe the real country.
* `core/` — the engine and the world runtime: bundle loaders with SHA-256 verification, spatial indexes,
  the mesh generators, the fixed-step physics, and the Arabic text shaper. Deliberately testable without
  a device: the mesh generators contain no GL calls, so triangle counts, winding, widths and heights are
  asserted in unit tests.
* `android/` — the libGDX Android application.

Everything the game ships is compiled from redistributable open data; see `DATA_SOURCES.md` and
`ASSET_LICENSES.md`. Attribution: **© OpenStreetMap contributors (ODbL 1.0)**, elevation from SRTM
(public domain).

## The previous project

This repository previously held a different Qatar build (milestone 1: an installable APK with a 3D
terrain proof of concept). It is archived, not deleted — see [ARCHIVE_NOTES.md](ARCHIVE_NOTES.md) — and
every revision of the repository is mirrored automatically into a verified git bundle by the
"Protect the project" workflow.

## Build

```bash
python3 tools/fetch_sources.py            # download OSM + SRTM (needs network)
python3 tools/build_world.py --stage all  # compile stage 1
python3 tools/game_world.py               # compile stage 2
python3 tools/selftest_game_world.py      # bundle contracts + synthetic fixture
gradle :core:test                         # JVM tests (meshers, loaders, physics, geography, text)
gradle :android:assembleDebug             # the APK
```

CI runs the same commands: `Compile gate` (fast type/compile check), `Protect the project` (history
archive), `Build world data` (country compile + validation), `Build APK` (tests, assemble, validate,
release).
