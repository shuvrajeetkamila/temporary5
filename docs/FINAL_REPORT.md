# Qatar: The Peninsula — the report

Everything the five steps asked for, what the evidence for each part is, and the parts that are honestly
not done. Written to be checked rather than believed: every number here is either a file in this
repository, a CI run, or a byte count that can be re-derived.

## The deliverable

| | |
|---|---|
| **APK** | [`Qatar-The-Peninsula-v0.1.0-m0.apk`](https://github.com/shuvrajeetkamila/temporary5/releases/download/v0.1.0-m0/Qatar-The-Peninsula-v0.1.0-m0.apk) |
| **Bytes** | 18,994,222 |
| **SHA-256** | `340d8eb46260c6f63ecf49cea9fcf178ab87d6c47303163d5936eac586809dc7` |
| **Sidecar** | [`…apk.sha256`](https://github.com/shuvrajeetkamila/temporary5/releases/download/v0.1.0-m0/Qatar-The-Peninsula-v0.1.0-m0.apk.sha256) |
| **Release** | https://github.com/shuvrajeetkamila/temporary5/releases/tag/v0.1.0-m0 (rolling: each green run republishes it) |
| **Built from** | commit `ab7698c`, branch `arena/4eb37624-temporary5` |
| **CI runs for that commit** | Build APK [`38442359734`](https://github.com/shuvrajeetkamila/temporary5/actions/runs/38442359734) success · Compile gate [`38442359802`](https://github.com/shuvrajeetkamila/temporary5/actions/runs/38442359802) success · Protect the project [`38442359832`](https://github.com/shuvrajeetkamila/temporary5/actions/runs/38442359832) success |
| **Tests at that commit** | 94 tests, 0 skipped, 0 failures, 0 errors (`artifacts/UNIT_TEST_STATUS.txt`, evidence commit `2210b8b`) |
| **Package** | `ai.arena.qatar.peninsula`, versionName `0.1.0-m0`, debug-signed, `minSdk 26`, `targetSdk 34` |
| **ABIs** | `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| **Permissions** | none: no `INTERNET`, nothing to phone home to. The country is inside the APK. |
| **APK validation** | entry list, dex count, ABI libs, world bundles, licence files, manifest, signature, zip integrity: `validation=PASSED` (`artifacts/APK_VALIDATION.txt`) |

### Installing it

1. Download the APK on the phone (or copy it over).
2. Allow installation from this source when Android asks (it is a debug-signed build, not a Play build).
3. Install and open. First launch streams the world around the player; a black screen with a status line
   for a second or two is the world arriving, not a crash.

### Controls

| | |
|---|---|
| Look | drag anywhere that is not a control |
| Move / throttle | drag on the lower-left of the screen (a stick appears where the thumb lands) |
| `HUMAN` `CAR` `PLANE` `HELI` `BOAT` | switch locomotion: each has its own body and its own physics |
| `WALK`/`DRIVE` | between walking and driving |
| `RUN`, `BRAKE`, `UP`, `DOWN` | sprint, brake/astern, climb and descend while held |
| `VIEW` | first person ⇄ behind the body (the boom camera that shows the machine you are in) |
| `MAP` | opens the big map; drag to pan, pinch to zoom, `+`/`-` to zoom, **tap a place to be put there**; `FIND ME` re-centres; `CLOSE` returns |
| labels | English and Arabic on every control |

## 1. The whole country, in 3D

The world is compiled from open data, in CI, into bundles that the game ships:

| | |
|---|---|
| Buildings | 186,993 footprints, real ring shapes, heights, roof shapes, 2,137 named |
| Roads | 179,943 ways with class, lanes, surface, bridges and tunnels |
| Land use | 5,784 polygons (that is where sand, rock, farmland and urban pad differ) |
| Water | 1,130 polygons, carved into the terrain and filled into the map raster |
| Barriers | 421 walls, fences, hedges and retaining walls |
| Points of interest | 2,254, including 279 car, 6 helicopter, 51 boat and 30 aeroplane spawn points |
| Strings | 16,780 names, Arabic and English |
| Terrain | SRTM elevation, level 1 at 100 m and level 0 at 300 m, plus a 300 m material grid |
| Land | 11,502 km² and 57 islands, projected to EPSG:32639 (UTM 39N) |

Evidence: the data run ([`38416103761`](https://github.com/shuvrajeetkamila/temporary5/actions/runs/38416103761),
`status=PASSED`) and `artifacts/data/DATA_LATEST.txt`; the compiler counts are committed at
`data/geodata/GAME_WORLD_REPORT.json`; `tools/validate_game_world.py --world android/assets/world`
passes 57/57 against the shipped bundles.

On screen: 500 m chunks streamed around the player, nearest first, with per-chunk level of detail,
an LRU cache, and a floating origin so a country 259 km across keeps metre precision. Terrain, water,
building extrusions, road ribbons with painted lane markings and barrier walls are all meshed by classes
with no OpenGL in them, which is why their geometry is unit-tested (triangle counts, winding, widths,
heights) in CI.

## 2. Five ways to move, with their physics

| Body | Models | Built from |
|---|---|---|
| Human | walking, running, a 0.42 m step, jumping, wading, and buildings that will not be walked through | 82 kg, 1.5 m/s walk, 6.6 m/s run |
| Car | bicycle model, tyre grip budget, power limit, bogging on loose ground, walls | 1,620 kg, 92 kW, 2.79 m wheelbase, 0.86 m² CdA |
| Aeroplane | trimmed angle of attack, stalled wing that drops its nose and recovers in a dive, rotation on the runway, hard-landing scrub | 1,043 kg, 16.2 m² wing, CL_max 1.51, 2.25 kN thrust |
| Helicopter | collective lag, cyclic tilt, rotor damping, tail-rotor yaw, altitude hold | 2,200 kg, 5.6 m rotor, 1.9 × weight of thrust |
| Boat | heave with buoyancy and planing lift, Froude-number resistance, beaching | 1,850 kg, 6.5 m hull, 3 kN propeller |

The map is interactable: buildings and barriers are solid at the altitude the body is at (a walker at
chest height is stopped by a wall; an aircraft above its top is not), terrain that has not streamed in
is not walked onto, and taps on the map move the player.

Evidence: `VehicleDynamicsTest`, `BuildingCollisionTest`, `BarrierCollisionTest` — part of the 94 passing
tests.

## 3. Detail assets

* Windows are procedural in the shader, banded in real metres from the compiled heights, so they do not
  swim when the camera moves and they need no texture at all.
* Roads carry painted lane markings; barriers extrude as walls, fences and hedges.
* The player has a body: a person, a car, an aeroplane, a helicopter and a boat, in metres, from the
  published dimensions of the real machines, painted per part from a palette in the shader (car paint,
  glass, tyre, aircraft white, helicopter green, boat hull, teak deck, cloth, skin, steel, rotor).
* Land is painted from its compiled material id — sabkha, mangrove, tidal mud, dune sand, urban pad —
  with per-vertex shading from a tint the meshers compute.

What is not there: doors, roof clutter, kerbs, palms, and moving parts (the wheels do not turn, the
propeller and rotor do not spin). The bodies are boxy by construction: silhouettes and dimensions are
right, folds and curves are not.

## 4. Minimap, buttons, zoomable map, tap to travel

The minimap sits in the top-right, drawn from the same raster as the big map, so the two can never
disagree. The big map zooms about the fingers (1× to 16× of the country), pans with clamping, and a tap
resolves to a place that can actually be stood on: the nearest named spawn point, else the nearest road
within 900 m, else the nearest shore — never the middle of the sea. A tap in a lake with no road or
harbour in reach is moved to the shore. `MapTeleportTest` holds all of that against the compiled
geometry, and it is what caught the two bugs in this area: inland water was outlined but never filled,
and a tap in the sea resolved to the tapped point.

## 5. The APK

Built and published on every green run: debug-signed, all three ABIs, no network permission, the whole
country inside it (16 MB of compiled bundles in a 19 MB package). Validation is byte-level: entry count,
dex files, native libraries per ABI, world bundle presence and manifest checksums, licence files,
signature, zip integrity.

## Honest limitations

1. **Nothing has ever run on a device.** There is no GPU and no Android emulator in the build
   environment, so the GLSL in this project has never been compiled by a real driver. The first launch on
   a phone is the first test of it. This is the largest single risk in the project, and everything that
   *can* be checked without a GPU is checked — including one bug that only a device would have shown
   (the vertex stride and the shader's decoding of surface types and material ids were both wrong, and
   are both fixed and now asserted by `VertexLayoutTest`).
2. No sound.
3. Buildings collide as their bounding box, not as their drawn ring: a courtyard is walkable through.
4. The world is drawn only within the streaming radius: beyond roughly 6 km it fades into fog and stops.
5. Vehicles do not collide with each other.
6. Performance has never been profiled. Dense urban chunks are the heaviest, the warm-up builds up to
   24 chunks per frame in the first seconds, and the resident window is sized for a 6 km view.
7. Terrain is SRTM-derived and smoothed at 100 m: the shapes of dunes are right, individual dune crests
   are not. Building heights are OSM's, which are sometimes a floor count converted to metres.
8. The bodies are boxes, and locomotion is discrete: the player is one kind of thing at a time.

## Data and attribution

* Map data **© OpenStreetMap contributors**, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/).
* Elevation from **SRTM**, public domain (NASA/USGS).
* Country boundary fallback: **Natural Earth 10m** (public domain).
* Typeface: **Amiri** (SIL OFL 1.1, © Khaled Hosny and the Amiri project contributors). The HUD fonts are
  rasterised from `assets/source/fonts/Amiri-Regular.ttf` by `tools/make_font.py`, and the OFL text is
  shipped inside the APK at `assets/licenses/Amiri-OFL.txt`. Arabic reordering and shaping is done in
  `ArabicReshaper`, which emits the presentation forms the font carries.
* Full details, per-input URLs, sizes and hashes: `DATA_SOURCES.md`, `ASSET_LICENSES.md`,
  `android/assets/DATA_SOURCES.md`, `data/geodata/SOURCES.json`.

## How to reproduce

```bash
git clone https://github.com/shuvrajeetkamila/temporary5 && cd temporary5
git checkout ab7698c
# world data (downloads OSM + SRTM, compiles the country, validates it):
python3 tools/fetch_sources.py && python3 tools/build_world.py && python3 tools/game_world.py
python3 tools/validate_game_world.py --world android/assets/world     # 57/57
# engine tests (no device, no GPU needed):
cd core && ../gradlew test
# the APK:
cd ../android && ../gradlew assembleDebug && python3 ../tools/validate_apk.sh \
    build/outputs/apk/debug/android-debug.apk
```

The three checks CI runs on every push are `compile.yml` (the compile gate), `build.yml` (unit tests,
APK build and validation, release publication) and `archive.yml` (the repository archive). `data.yml`
recompiles the country from source.
