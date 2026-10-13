# QATAR: THE PENINSULA

A realistic 3D Android game built on the **real country of Qatar**: the whole peninsula, every street,
every mapped building, the islands, the coastline, the desert — compiled from open data into a world
you can walk, drive, fly and sail through.

## Status

| Milestone | State |
|---|---|
| **Step 1 — the whole country in 3D** | World compiler done (OpenStreetMap + SRTM → terrain, land mask, surface materials, roads, buildings, land use, water, barriers, spawn points). Loaders, spatial indexes and the mesh generators (terrain, water, building extrusion with real footprints, road ribbons) are implemented and covered by JVM tests. In-progress: the GL renderer that draws those meshes on screen. |
| **Step 2 — human, car, aeroplane, helicopter, boat + interactable map** | Physics core (fixed 60 Hz stepping, force/torque rigid bodies, terrain collision, sleeping, CCD) is already in place from the foundation work; vehicles and the character controller are next. |
| **Step 3 — windows, bodies, assets** | Not started: the world currently draws as untextured but correctly-shaped geometry. |
| **Step 4 — minimap, HUD, press-to-teleport map** | Not started: the world data needed for it (road network + spawn points + islands + municipalities) is compiled and indexed. |
| **Step 5 — the installable APK** | Pipeline in place (compile gate → world data → unit tests → APK validation → release). The last published APK is the previous project's; the next one will be this game. |

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
