# Qatar: The Peninsula — the report

Everything the five steps asked for, what the evidence for each part is, and the parts that are honestly
not done. Written to be checked rather than believed: every number here is either a file in this
repository, a CI run, or a byte count that can be re-derived.

## The deliverable

| | |
|---|---|
| **APK** | [`Qatar-The-Peninsula-v0.1.0-m0.apk`](https://github.com/shuvrajeetkamila/temporary5/releases/download/v0.1.0-m0/Qatar-The-Peninsula-v0.1.0-m0.apk) |
| **Bytes** | 19,198,618 — and the release is rolling: every green build replaces the file, so these numbers hold for the run named below and `artifacts/APK_VALIDATION.txt` in that run's evidence commit is the authority for the current asset |
| **SHA-256** | `0a2731dbed707cd4d207a6517b935daa78c2f262c9b5a6dd97e69332bf466ff5` (the release API reports the same digest for the asset) |
| **Sidecar** | [`…apk.sha256`](https://github.com/shuvrajeetkamila/temporary5/releases/download/v0.1.0-m0/Qatar-The-Peninsula-v0.1.0-m0.apk.sha256) |
| **Release** | https://github.com/shuvrajeetkamila/temporary5/releases/tag/v0.1.0-m0 (rolling: each green run republishes it) |
| **Built from** | commit `cf5ac2c`, branch `arena/4eb37624-temporary5` |
| **CI runs for that commit** | Build APK [`38625231600`](https://github.com/shuvrajeetkamila/temporary5/actions/runs/38625231600) success · Compile gate [`38625231571`](https://github.com/shuvrajeetkamila/temporary5/actions/runs/38625231571) success · Protect the project [`38625231519`](https://github.com/shuvrajeetkamila/temporary5/actions/runs/38625231519) success |
| **Tests at that commit** | 136 tests, 0 skipped, 0 failures, 0 errors (`artifacts/UNIT_TEST_STATUS.txt`) |
| **Build stamp on screen** | `0.1.0-m0+diag5` — the release asset keeps its name, so this is how two builds are told apart on a phone: the diagnostics panel prints the stamp on its first line |
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

Evidence: `VehicleDynamicsTest`, `BuildingCollisionTest`, `BarrierCollisionTest` — part of the 117 passing
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

## On a phone, twice, and what those two runs found

There is no GPU and no Android emulator in the build environment, so the GLSL in this project is compiled
for the first time on a phone, and only the screen can say what a driver thinks. That is why the app
carries a diagnostics panel: its own console, drawn with its own batch and its own font, one line per
stage, and every failure printed on the screen in words and left there. Two installs have now been made.
Both found real bugs. Every one of them is fixed and, where a test can hold it, held.

### First install: a blue screen

1. **A reserved word.** The world fragment shader declared a variable called `half`: an ordinary English
   word, an ordinary identifier in desktop GLSL, and **reserved in GLSL ES 3.00**, which keeps it for a
   future 16-bit float. A driver that follows the spec rejects the whole shader, the world program never
   links, and nothing can be drawn. It is `halfway` now, and `ShaderSourcesTest` fails on a reserved word
   in any shader in the project.
2. **Nobody could see the failure.** The renderer logged "shader compilation failed" with the driver's own
   message and returned false; the caller ignored it and the next frame dereferenced a null program. The
   screen said nothing and the only sentence that mattered was in logcat, where the person holding the
   phone cannot read it.
3. **Launch was one long silent block**: the map raster was built inside the first frame at 2048 x 2048,
   which is a pause on a desktop and a long freeze on a phone.

The fix for the family, not just the instance: launch runs as stages with a name on the screen for each,
`ScreenLog` reports what broke even when the thing that is broken is what it reports on, a crash handler
writes the throwable to app storage and the next launch prints it, and a shader failure leaves a flat grey
world with the driver's message on top instead of a dead renderer.

### Second install: the panel worked, and reported three more

The panel from round one came back with the answers, in this order: the GL driver (Mali-G72, OpenGL ES
3.2), the manifest read correctly (world format 2, 259 x 268 km, 186,993 buildings, 179,943 roads), then
**three failures**, each of them in the way of the next step:

1. **`world bundle missing: strings.bin.gz`** — the whole country was in the APK, one extension away
   from being found. The Android asset tooling **unpacks a `.gz` asset at package time and drops the
   suffix**, so the package holds `assets/world/strings.bin` with the inflated bytes while the manifest,
   and the loader, both named `strings.bin.gz`. On a phone the game had never got past the loading stage;
   no previous test could have caught it, because every test read the repository's own form of the file.
   The loader now resolves a bundle by the name the manifest gives **or** by that name without its `.gz`
   suffix, and accepts either shape of contents: gzip (verify the digest, then inflate) or already
   inflated (use it, and say once, on the screen, that the digest cannot be checked here because the bytes
   that were hashed are not the bytes that were packaged). `BundlePackagingTest` loads the world four
   ways — repository names and shape, packaged names and shape, and both mixed — and requires the same
   country from all four; `tools/validate_apk.sh` now checks the shipped APK for every bundle under
   either name, which is the check that would have caught it in CI.
2. **`HUD font unavailable`** — and this is why the panel itself was in libGDX's built-in ASCII font.
   `tools/make_font.py` wrote the `.fnt` sections in the wrong order: `kernings count=0` came *before*
   the character lines. libGDX reads a `.fnt` as a stream and **stops at the first line beginning with
   `kernings`**, so it parsed zero glyphs, then looked up a glyph to measure a space, got null and threw.
   The file was syntactically perfect and wrong. Kerning now comes last, where libGDX's own font tool puts
   it, and the atlases are written RGBA (white pixels, coverage in alpha) — the shape a `BitmapFont` is
   built to consume — rather than 8-bit greyscale, which only works when the decoder happens to map it to
   an alpha pixmap. `FontCoverageTest` now parses each shipped `.fnt` **the way libGDX parses it**,
   stopping where libGDX stops, and fails if the glyph count disagrees, if the space glyph is missing or
   advances nothing, if the page image is missing, or if the page is not RGBA.
3. **`Demo shaders also failed: terrain: L0001. The fragment floating-point variable u_cameraLocal does
   not match the vertex variable u_cameraLocal. The precision does not match.`** — so the fallback scene,
   the thing that exists to prove a device's pipeline works, could not draw either. The terrain shader
   declared `uniform vec3 u_cameraLocal` with no qualifier: in a vertex stage float defaults to `highp`,
   in a fragment stage after `precision mediump float` it defaults to `mediump`, and one uniform with two
   precisions is a link error a driver is right to refuse. Every varying and every uniform that crosses a
   stage boundary in all ten shaders now carries an explicit precision, and two independent checks hold
   it: `ShaderSourcesTest.precisionsMatch` (JUnit) and `tools/check_shaders.py` (no JVM needed, which
   matters because this sandbox has no Java compiler). Re-inserting the bug makes both report the driver's
   own sentence.

Two further things were found while fixing those, and both were tests that had stopped looking at their
own subject — worse than no test, because they were believed:

* `VertexLayoutTest` matched varyings with a pattern that allowed no precision qualifier, so when the
  qualifiers were added it silently matched nothing and passed on an empty set. It reported a failure only
  after the fix that mattered was already in. The pattern now accepts the qualifier and both sets are
  asserted non-empty first.
* `BundlePackagingTest` asserted that a bundle keeps the manifest's name when that name exists — while
  handing the resolver a set in which it never existed. It was asserting the opposite of what it claimed.

Then a gate, because the first push of this round never reached the APK job: `FontCoverageTest` called
`assertEquals` seven times without importing it. The syntax was perfect (a tree-sitter parse of all 84
files reports zero errors) and no local check could see it, because name resolution needs a compiler and
there is none here. `tools/check_java_symbols.py` closes that hole: it strips comments, strings and text
blocks and fails on an unqualified assertion the file never imported — the compiler's own error, in a
second instead of ten minutes — and it reproduces all seven CI errors at the same seven line numbers.
Both new gates run first in the compile job, and both run their own failure samples before they look at
the tree.

The honest position now: two installs made, two failures, four causes — a reserved word, a bundle name,
a file section order and a missing precision — each one named by the app itself on the phone's screen and
each one now fixed where a test can hold it: 117 tests green, `validation=PASSED`. Two of the four are
checked in the shipped artefact by `validate_apk.sh`, and none of them is left to a promise.

### Third install: it closed immediately

The round-two build closed on launch. Nothing was on the screen and nothing was in the panel, because the
failure happened after the last frame that could have drawn anything: this is the crash the journalled
record exists for, and it is now visible from the phone itself.

Two things were wrong in the chunk mesher, and both are fixed:

1. **A primitive could be started on a full mesh.** A mesh holds 65,000 vertices; the meshers asked
   `isFull()` -- "do four more fit?" -- once before adding a whole primitive, and a building needs four
   vertices per wall edge plus a roof while a road ribbon needs eight per segment. A building could start
   with four vertices left, `addVertex` threw, the exception escaped the render loop, and the GL thread
   died with the process. Primitives are now sized before they are placed, a chunk's detail is split into
   up to three parts that roll over, and geometry that fits no part is dropped and flagged instead of
   throwing.
2. **The resident window was too large for a phone's memory.** Three 4,096-vertex arrays per chunk, 420
   chunks: about 220 MB of mostly empty geometry on top of 60 MB of compiled country. A phone kills an
   app that does that with no exception and nothing to read. Meshes now start small, grow, are trimmed,
   hand their CPU arrays to the GPU and forget them, and the window is bounded by a vertex budget
   (1,000,000) as well as by distance -- taking back the farthest chunk, never the one under the camera.

**If this build ever closes silently again, it will not be silent the next time.** A crash journal is
installed by the Android entry point before libGDX exists; it writes one breadcrumb line per launch stage
with the heap in use, and the next launch shows the previous record in a dialog, with a **Copy** button so
the text can be pasted into a message. The record is kept until someone clears it. That is what to send
back if anything goes wrong: the dialog's text.

`ChunkMeshBudgetTest` now meshes the densest 500 m chunk of the committed country at full detail -- found
by counting footprints in the real data, 40+ in a single chunk -- and fails if any mesh part leaves the
index limit; `ChunkMeshCacheBudgetTest` holds the resident window to its memory budget. Both fail against
the code that shipped in round two, which is the point of writing them.

### Fourth install: a beach, half a polygon, a wall of text, and no controls

Three faults, none of them in the drawing of anything:

1. **Every chunk was drawn one origin-distance from where it belongs.** A chunk's geometry is meshed in
   bundle space — metres from the compiled country's origin, up to 259 km — and the renderer placed it in
   the rebased frame next to the player. The two are 110 km apart at Doha, so the player stood on sand
   with a fragment of a building in it. Fixed centrally in `ChunkMesher`, which now rebases each chunk to
   its own origin before it is handed over, so no mesher has to know about the frame.
2. **The diagnostics panel covered the game.** 78% of the screen at 0.86 alpha, drawn on top of the
   controls: "no controls, nothing". It is now 42% at 0.62, at most ten lines, and it drops INFO lines
   before ERROR ones when it runs out of room.
3. **One failing subsystem could take the frame with it.** Every subsystem is now stepped and reported
   on individually, a missing map says so in a sentence instead of leaving a dead button, and no single
   failure disposes the screen.

### Fifth report: "having some problems", with no symptom in it

A report that says something is wrong and nothing else is still a complete report. The answer was an
audit of every place two coordinate systems meet, and it found three faults that no previous test could
see, because every previous test looked at one thing at a time:

1. **Every body was drawn at sea level.** `BodyMesher.transform` took a height and ignored it, and every
   mesh is modelled with y = 0 at whatever it stands on — a person's feet, a car's tyre patches, a boat's
   waterline. A player walking at 10 m of elevation was under the terrain and invisible; a car on the
   sabkha was nowhere near its own shadow. The height is now a **required argument** of every body
   transform, so it cannot be forgotten back to zero.
2. **A 16-bit depth buffer against a 0.25 m near plane and a 12 km far plane.** The renderer lifts roads,
   kerbs and roofs by centimetres to keep them out of the ground, and 16 bits cannot resolve a centimetre
   at 300 m: terrain, ribbons and roofs interleaved and shimmered as the camera moved. The context now
   asks for 24 bits.
3. **The diagnostics panel never left.** One logged error kept it on screen for the whole session, which
   makes it furniture rather than a report. It now stays 30 s after the last error and then gets out of
   the way; the record stays in the journal, and the next launch's dialog prints it in full.

Two things from step three were finished in the same pass, both of them the kind of detail that can be
done without adding a vertex to a country with 187,000 buildings:

* **Roof clutter**, drawn in the fragment shader on the roof's own metre coordinates (the roof's aux pair
  is now metres from the roof centre, not a 0..1 parameter): gravel and bitumen, 2.4 m plank seams, a 6 m
  hashed grid, plant housing and a water tank.
* **A person who walks**: hinged legs and arms built around their joints — hip at 0.85 m, shoulder at
  1.42 m — swinging 26° and 17° in opposite phase, driven by the same phase that turns a car's wheels, so
  the feet move faster when the person does and stand still when the person stops.

Three builds were lost to red gates in this pass, and every one of them was mine, not the game's: a wheel
assertion that expected the contact patch where the axle is, a local variable declared twice in one method
— a compile error only `javac` sees, which now has its own gate, `tools/check_local_names.py`, a
tree-sitter shadowing check — a narrowing conversion (`Math.max(float, double)` into a `float`), which now
has `tools/check_float_narrowing.py`, a gate that types expressions from the parse tree and refuses to
report anything it cannot prove — and a symmetry assertion made of a leg, which is not a wheel and was
never built to be one. The last one is why the mesher's own contract is now written down: a part is built
*around* its pivot, and `swingDegrees` is what tells a wheel that goes round from a limb that hangs.

## Honest limitations

1. **This build has been on a phone five times, and every report found something real** — twelve faults
   in all, listed in the sections above, each one now fixed and, where a test can hold it, held. The last
   two reports arrived with no symptom in them at all, so the audit was the diagnosis: that is what the
   on-screen panel and the crash journal are for, and it is why the panel and the diagnostics are worth
   more than their own size in the APK. **Rounds four and five have not been confirmed on the device
   yet.** There is still no GPU and no Android emulator in the build environment: the GLSL in this project
   compiles for the first time on a phone. Nothing here can compile GLSL; what stands in for a GPU is the
   shader lint, the on-screen diagnostics, and the fact that the panel has so far named its failures
   precisely enough to fix them — including the one it named with no words from the phone at all.
2. No sound.
3. Buildings collide as their bounding box, not as their drawn ring: a courtyard is walkable through.
4. The world is drawn only within the streaming radius: beyond roughly 6 km it fades into fog and stops.
5. Vehicles do not collide with each other.
6. Performance has never been profiled. Dense urban chunks are the heaviest, the warm-up builds up to
   24 chunks per frame in the first seconds, and the resident window is sized for a 6 km view.
7. Terrain is SRTM-derived and smoothed at 100 m: the shapes of dunes are right, individual dune crests
   are not. Building heights are OSM's, which are sometimes a floor count converted to metres.
8. The bodies are still boxes with a paint table rather than modelled assets, though they now have
   windows, tyres, a deck, a rotor and limbs that swing; the person is drawn only in the third-person
   view; and locomotion is discrete: the player is one kind of thing at a time.
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
git checkout cf5ac2c            # the commit the published APK was built from
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
