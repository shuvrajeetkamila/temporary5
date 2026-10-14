# Qatar: The Peninsula — the report

Everything the five steps asked for, what the evidence for each part is, and the parts that are honestly
not done. Written to be checked rather than believed: every number here is either a file in this
repository, a CI run, or a byte count that can be re-derived.

## The deliverable

| | |
|---|---|
| **APK** | [`Qatar-The-Peninsula-v0.1.0-m0.apk`](https://github.com/shuvrajeetkamila/temporary5/releases/download/v0.1.0-m0/Qatar-The-Peninsula-v0.1.0-m0.apk) |
| **Bytes** | 19,083,818 |
| **SHA-256** | `0e77c063fe4d7ba5ec7038ae5303a43b568a4a574296d55a6030a5fdb5a70ceb` |
| **Sidecar** | [`…apk.sha256`](https://github.com/shuvrajeetkamila/temporary5/releases/download/v0.1.0-m0/Qatar-The-Peninsula-v0.1.0-m0.apk.sha256) |
| **Release** | https://github.com/shuvrajeetkamila/temporary5/releases/tag/v0.1.0-m0 (rolling: each green run republishes it) |
| **Built from** | commit `a5418c8`, branch `arena/4eb37624-temporary5` |
| **CI runs for that commit** | Build APK [`38542401098`](https://github.com/shuvrajeetkamila/temporary5/actions/runs/38542401098) success · Compile gate [`38542400815`](https://github.com/shuvrajeetkamila/temporary5/actions/runs/38542400815) success · Protect the project [`38542400940`](https://github.com/shuvrajeetkamila/temporary5/actions/runs/38542400940) success |
| **Tests at that commit** | 108 tests, 0 skipped, 0 failures, 0 errors (`artifacts/UNIT_TEST_STATUS.txt`, evidence commit `112cc2a`) |
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

Evidence: `VehicleDynamicsTest`, `BuildingCollisionTest`, `BarrierCollisionTest` — part of the 108 passing
tests.

## 3. Detail assets

* Windows are procedural in the shader, banded in real metres from the compiled heights, so they do not
  swim when the camera moves and they need no texture at all.
* Roads carry painted lane markings; barriers extrude as walls, fences and hedges.
* The player has a body: a person, a car, an aeroplane, a helicopter and a boat, in metres, from the
  published dimensions of the real machines, painted per part from a palette in the shader (car paint,
  glass, tyre, aircraft white, helicopter green, boat hull, teak deck, cloth, skin, steel, rotor).
* The bodies move. The parts that should turn do, and at the rates the machines turn at: the car's four
  wheels roll at v/r on the same 0.33 m radius, 1.57 m track and 2.79 m wheelbase the physics steers on,
  the aeroplane's propeller idles at 700 rpm and turns 2,400 at full power, and the helicopter's rotor
  head is governed near 330 rpm under a tail rotor turning 2.5 times as fast. Every part is built centred
  on its own pivot and placed by translating to that pivot *before* rotating, so a wheel rolls on its
  axle instead of orbiting the car. The angle is visual only: nothing in the simulation reads it.
* Land is painted from its compiled material id — sabkha, mangrove, tidal mud, dune sand, urban pad —
  with per-vertex shading from a tint the meshers compute.

* The text is part of the art, so it is treated as one: the HUD fonts are generated from the licensed
  Amiri typeface by `tools/make_font.py`, and every character the game can print has a glyph in them --
  the 17,718 names in the compiled country, shaped Arabic and all. `FontCoverageTest` reads the shipped
  atlases and the world's own string table and fails on a character that would print as a blank. It found
  four on its first run -- 'é', 'ā', 'ū' and 'ی', in nine names out of the country's 17,718: Ladurée,
  Lycée Franco Qatari Voltaire, Bū Sadād, ایران, Villa D Esté -- and the generator now covers every
  character class Amiri has in the ranges the data uses: 663 glyphs per font.

What is not there: doors, roof clutter, kerbs and palms; a walking animation (the person's legs are
static boxes, and the body is drawn only in the third-person view); and curves -- the bodies are boxes by
construction, so silhouettes and dimensions are right and folds are not.

## 4. Minimap, buttons, zoomable map, tap to travel

The minimap sits in the top-right, drawn from the same raster as the big map, so the two can never
disagree. The big map zooms about the fingers (1× to 16× of the country), pans with clamping, and a tap
resolves to a place that can actually be stood on: the nearest named spawn point, else the nearest road
within 900 m, else the nearest shore — never the middle of the sea. A tap in a lake with no road or
harbour in reach is moved to the shore. `MapTeleportTest` holds all of that against the compiled
geometry, and it is what caught the two bugs in this area: inland water was outlined but never filled,
and a tap in the sea resolved to the tapped point.

The controls are bilingual and so is the readout: the status line prints the locomotion, whether the
player is on the ground, in the air or in the water, the speed, the altitude and the heading in Arabic as
well as English, and the Arabic is shaped and put in visual order for the renderer rather than drawn as
the disconnected letters a left-to-right font would produce. The map's hint is two lines for the same
reason. `BilingualStatusTest` holds the Arabic line to being Arabic, to carrying the same numbers, and to
shaping into forms the font carries.

## 5. The APK

Built and published on every green run: debug-signed, all three ABIs, no network permission, the whole
country inside it (16 MB of compiled bundles in a 19 MB package). Validation is byte-level: entry count,
dex files, native libraries per ABI, world bundle presence and manifest checksums, licence files,
signature, zip integrity.

## The first run on a phone, and what it found

The APK was installed on an Android phone, and it showed a blue screen with nothing on it. That is worth
writing down properly, because every part of it was avoidable in a different way:

1. **A reserved word.** The world fragment shader declared a variable called `half`. It is an ordinary
   English word and an ordinary identifier in desktop GLSL, and it is **reserved in GLSL ES 3.00**, which
   keeps it for a future 16-bit float. A driver that follows the spec rejects the whole shader for it; the
   world program then never links, and the country cannot be drawn by anything. It is now `halfway`, and
   `ShaderSourcesTest` reads every shader in the project and fails on a reserved word, on 1.00 syntax in a
   300 es shader, on a fragment shader without a float precision, on a call to a function that is neither
   built in nor declared, and on a vertex shader whose outputs do not match what the fragment shader
   reads. Reintroducing `half` fails that test with the line that matters.
2. **Nobody could see the failure.** The renderer logged "shader compilation failed" with the driver's
   own message and then returned false, and the caller ignored it: the next frame dereferenced a null
   program and killed the GL thread. The screen said nothing, and the reason -- the only sentence that
   mattered -- was in logcat, which the person holding the phone cannot read.
3. **Launch was one long silent block.** The map raster was built inside the first frame at 2048 x 2048
   (4.2 million pixels covering the whole country), and the spawn selection built a second raster at 512
   to look at. On a desktop that is a pause; on a phone it is a long block with nothing on the screen to
   say a launch is happening at all.

All three are fixed. The launch now runs as stages, one per frame, each of them printed on the screen as
it starts -- GL context, crash file, manifest, bundles, map raster, game screen -- and any failure prints
the exception, its first stack frames and the GL strings and stays there. `ScreenLog` draws that console
with its own batch and its own font (the HUD font, then libGDX's built-in one, then coloured bars if no
font can be loaded at all), so it works when the thing that is broken is the thing it is reporting on. A
crash handler writes the throwable into the app's own storage and the next launch prints it, so a crash
comes back as a stack trace instead of "it closed itself". If the world's shaders fail again, the screen
shows the driver's message and the game keeps running with a flat grey world rather than nothing.

The honest position on the device: the first install failed, the reason is now known and fixed, and the
second install is the first real test of the fix. Nothing in this repository can compile GLSL, so the
shader lint and the on-screen reporting are what stand in for a GPU until then.

## Honest limitations

1. **The build has run on a device once, and that run failed.** There is no GPU and no Android emulator
   in the build environment, so the GLSL in this project is compiled for the first time on a phone, and
   the first time it was, the world shader was rejected for using a reserved word (section above). The
   fix is in `a5418c8`, and the launch now reports what it is doing and what breaks, on the screen, which
   is what the next install is for. Two earlier bugs of the same family were found without a device: the
   vertex stride and the shader's decoding of surface types and material ids were both wrong, and are both
   fixed and asserted by `VertexLayoutTest`.
2. No sound.
3. Buildings collide as their bounding box, not as their drawn ring: a courtyard is walkable through.
4. The world is drawn only within the streaming radius: beyond roughly 6 km it fades into fog and stops.
5. Vehicles do not collide with each other.
6. Performance has never been profiled. Dense urban chunks are the heaviest, the warm-up builds up to
   24 chunks per frame in the first seconds, and the resident window is sized for a 6 km view.
7. Terrain is SRTM-derived and smoothed at 100 m: the shapes of dunes are right, individual dune crests
   are not. Building heights are OSM's, which are sometimes a floor count converted to metres.
8. The bodies are boxes, the person's legs do not swing, the body is drawn only in the third-person
   view, and locomotion is discrete: the player is one kind of thing at a time.
9. The Arabic in the interface is Modern Standard Arabic written for this project. No native speaker
   has reviewed it, and a translated phrase is not the same thing as a phrase somebody would actually
   say -- the same honesty that applies to the code applies to the language.
10. Aeroplane spawn points are the weakest of the compiled picks: they are real mapped aprons, but the
   first one sits 640 m from the nearest road, so the game does not *start* there — the start uses a car
   spawn and the aeroplane is reached from the HUD, which places it correctly on land every time. Heading
   after a spawn is the last leg of the road it came from, so a vehicle can point away from where it
   should go and be turned by hand.

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
* **In the game**: the credit is printed in both languages on the map screen, which is the picture made
  from that data, and for the first twelve seconds of every launch. It comes from the compiled world's
  own manifest (`Credits.english`), so a world that names its sources prints exactly those words. For a
  long time the manifest was the only place this text existed -- in the APK, never on the screen -- and
  the licence asks for more than that.

## How to reproduce

```bash
git clone https://github.com/shuvrajeetkamila/temporary5 && cd temporary5
git checkout a5418c8
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
recompiles the country from source, and is the one workflow not to trigger on a path it also writes to:
it publishes its own provenance into `data/geodata/**`, and while that directory was one of its push
triggers every run started the next one -- five full country compiles were queued that way before it was
noticed, each of them re-downloading the same sources to rewrite the same evidence.
