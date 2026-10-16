# Qatar: The Peninsula — the report

Everything the five steps asked for, what the evidence for each part is, and the parts that are honestly
not done. Written to be checked rather than believed: every number here is either a file in this
repository, a CI run, or a byte count that can be re-derived.

## The deliverable

| | |
|---|---|
| **APK** | [`Qatar-The-Peninsula-v0.1.0-m0.apk`](https://github.com/shuvrajeetkamila/temporary5/releases/download/v0.1.0-m0/Qatar-The-Peninsula-v0.1.0-m0.apk) |
| **Bytes** | 19,226,838 — and the release is rolling: every green build replaces the file, so these numbers hold for the run named below, and `artifacts/APK_VALIDATION.txt` in that run's evidence commit is the authority for the current asset. Android builds are not bit-reproducible, so two builds of the same commit differ by a few bytes and by their digest; the bytes and the digest here are the ones in the release as published by the run named below |
| **SHA-256** | `8efb1d0b81ebd341104359dd2a19cdfc55517193df0d12c3acbd7c26fdb61871` — the digest in the run's `artifacts/APK_VALIDATION.txt`, in the release notes, and in the 110-byte `.sha256` sidecar the same step wrote |
| **Sidecar** | [`…apk.sha256`](https://github.com/shuvrajeetkamila/temporary5/releases/download/v0.1.0-m0/Qatar-The-Peninsula-v0.1.0-m0.apk.sha256) |
| **Release** | https://github.com/shuvrajeetkamila/temporary5/releases/tag/v0.1.0-m0 (rolling: each green run republishes it) |
| **Built from** | commit `ebfa20b`, branch `arena/4eb37624-temporary5` |
| **CI runs for that commit** | Build APK [`38894342258`](https://github.com/shuvrajeetkamila/temporary5/actions/runs/38894342258) success (including publish) · Compile gate [`38894342296`](https://github.com/shuvrajeetkamila/temporary5/actions/runs/38894342296) success · Protect the project [`38894342292`](https://github.com/shuvrajeetkamila/temporary5/actions/runs/38894342292) success. No world-data run this time: the country was not recompiled, so the build compiled the bundle inside its own job |
| **Tests at that commit** | 225 tests, 0 skipped, 0 failures, 0 errors (`artifacts/UNIT_TEST_SUMMARY.txt`) |
| **Build stamp on screen** | `0.1.0-m0+diag11` — the release asset keeps its name, so this is how two builds are told apart on a phone: the diagnostics panel prints the stamp on its first line |
| **Package** | `ai.arena.qatar.peninsula`, versionName `0.1.0-m0`, debug-signed, `minSdk 26`, `targetSdk 34` |
| **ABIs** | `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| **Permissions** | none: no `INTERNET`, nothing to phone home to. The country is inside the APK. |
| **APK validation** | entry list, dex count, ABI libs, world bundles, licence files, manifest, signature, zip integrity: `validation=PASSED` (`artifacts/APK_VALIDATION.txt`) |

**Which evidence file belongs to which build.** A run triggered by a push is the one that publishes;
the world-data run then dispatches a second APK build of the same tree to verify it, with
`publish_release=false`, and that one publishes nothing. Both write `artifacts/`, so the branch keeps the
**newest** evidence, which may describe the non-publishing build -- and that build differs from the
published APK by a few bytes and by its digest, because Android builds are not bit-reproducible. The
published numbers are therefore the ones in the release notes of the tag, in the release API's `digest`
for the asset, and in the table above; the run named above is the one that put them there.

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

### Sixth install: "Failed the controls", and a format string was the whole story

The panel reported `Failed the controls` with `IllegalFormatConversionException: f != java.lang.Integer`
under it, six frames of `java.util.Formatter`, a healthy heap and a launch that had reached the game
screen. The world kept drawing behind it; the controls half of every frame did not.

The cause was one specifier in the HUD's status line: `"%.0f fps"` against
`Gdx.graphics.getFramesPerSecond()`, which libGDX declares as `int`. Java's formatter refuses to print an
Integer fractionally, so the line threw every frame, and after 120 failed frames the game stopped drawing
the screen and left the panel up -- which is also almost certainly what the fourth report was, with the
panel then staying for the whole session rather than leaving after thirty seconds.

Four things were done, and the first is the one that lasts:

* **The line moved out of the frame loop** into `hud/StatusText.english(...)`: a pure function, text in
  and text out, no GL and no `Gdx`. That is what makes it testable, and `StatusTextTest` now holds the
  whole line, the frame count as a count, metre rounding, negative coordinates, and -- deliberately --
  that `%.0f` against an `int` throws the exact exception the phone showed. The bug existed because the
  only way to try that line was to run the game on a phone.
* **The alert now shows the game's own frames first.** `boot/StackTraceText` prefers `ai.arena.qatar`
  frames, then the framework's, and the JVM's only as a last resort, with a count of what was left out.
  The report of this failure spent all six of its lines on `java.util.Formatter`, so the frame naming the
  cause was off the end of the list: the exception was reported perfectly and the cause was invisible.
* **A fifth source gate**, `tools/check_format_specifiers.py`: it reads every `String.format` whose
  pattern is a literal in the call and reports a specifier that cannot print the value it is given --
  only when that value's kind is provable. Thirteen self-tests run before it reads the tree, and it
  catches the real line when it is put back.
* **`VERSION` became `0.1.0-m0+diag7` for this build**, so the phone could tell it from the one that
  failed.

### Seventh install: the country, and nothing else

The report came with a screenshot this time: the world drawing perfectly, and no controls on it. No
exception, no panel, no buttons, no minimap -- the app was silent because its diagnostics were being
discarded by the GPU along with everything else.

The world renderer enables `GL_CULL_FACE` and `GL_DEPTH_TEST` at the start of every frame and never
turns them off. libGDX's `SpriteBatch.begin()` sets `glDepthMask(false)` and nothing else — it leaves
both of them on — and SpriteBatch's sprites are wound clockwise in a y-up system while the world
renderer leaves a counter-clockwise front face behind. Every quad the HUD drew was a back face, and the
GPU threw all of them away. It survived six rounds because it is *correct code*: nothing throws, nothing
is missing, and on a desktop the state is never left in that condition.

* `render/GlState2D.beginUi()` is the fix: the 2D entry points — `HudRenderer.begin()` and the panel's
  draw — disable depth testing and culling for themselves instead of inheriting 3D state.
* `WorldRenderer.endFrame()` hands the state back when a 3D pass ends, and `drawActor` sets its own 3D
  state first, since the player's body is drawn after the chunks.
* `tools/check_ui_state.py` is the sixth source gate, and the one that encodes the lesson: no
  `SpriteBatch` may be opened in a method that has not called `GlState2D.beginUi()` first.
* In the same round, at the request that arrived with the report: the map's **tap marker** — a crosshair
  on the *place* that was travelled to, asked for again every frame through the new
  `MapView.screenXAt/screenYAt`, so it stays on the destination while the map is panned or zoomed
  (`drawTeleportMarker` had never been called at all) — the Android **BACK key closes the open map**
  instead of leaving the app, and `HudLayoutTest` (six tests) now asserts the whole control set as
  geometry: the minimap is in the top-right, no button overlaps it (so a tap meant for it cannot be
  swallowed), every control is on the screen at six resolutions, none overlaps another, and the open map
  always has a CLOSE. `MapTeleportTest` gained the round trip that keeps the marker honest.

**Verified on this build, not argued**: compile gate `38768362154` — six source gates, all green on the
first push — build world data `38768362094`, and build APK `38768362060` with every one of its 18 steps
success: 153 tests, 0 failures, 0 errors (`artifacts/UNIT_TEST_SUMMARY.txt`), `validation=PASSED` and
`status=PASSED` (`artifacts/APK_VALIDATION.txt`, `artifacts/QA_STATUS.txt`), APK **19,200,526 bytes**,
SHA-256 `593f4f2c…`, and the release API's computed digest for the uploaded asset is that same value.
The APK pays 872 bytes over the previous one, all of it new code and its documentation.

### Eighth install: the map closed the app, and the buildings had no roofs

The report: "OPENING MINIMAP CUSES IT TO CRASH. ASLO STILL HAS A LOT OF PROBLEMS, CHECK ALL LOCOMOTION
PHYISCS PROPERLY AND ALSO MAKE BUILDINGS CORECTLY, I MEAN FILL THEM, THEY LOOK LIKE WALLS, ALSO MANY THER
THINGS PLEASE FIND AND FIX". Three complaints, three real faults, and the audit that the third one asked
for found two more.

**Opening the map did not crash the app; the cleanup did.** The map was drawn, and then the frame guard
gave up: after 120 frames it disposes the game screen so that one broken subsystem cannot pin the whole
app, and disposal touches GL. `SpriteBatch.end()` -- the first thing a HUD teardown does -- calls
straight through to `glDepthMask`, and with no current context that is
`NullPointerException: com.badlogic.gdx.Gdx.gl`, thrown on the GL thread inside the very block meant to
contain the damage. So the app died of its own cleanup, and it did so *because* something else on that
path had failed first. The fix is the distinction the code did not have: **is there a context**
(`QatarGame.glAvailable()`, which is `Gdx.gl != null` and nothing more speculative), and **is this the GL
thread** (answered by *where* the teardown runs: the queued teardown happens inside `renderFrame()`, which
is the GL thread by definition). `HudRenderer.dispose(boolean)`, `GameScreen.dispose(boolean glReady)` and
`QatarGame.disposeScreen(boolean)` now leak GL objects when there is no context to release them in,
release everything else either way, and log and journal the reason. A leak in that situation is invisible;
an exception is not.

**"They look like walls" was exact.** Buildings were walls with roofs wound face-down into the
building. This is a right-handed system -- x east, y up, z north -- so x * z = -y: the (east, north) plane
is *left*-handed, counter-clockwise there faces down, and the renderer culls back faces. **185,551 of the
country's 186,993 building rings were affected** (the other 1,442 are counter-clockwise in OSM, and those
had the mirror-image bug of showing roof and no wall). The winding rule now lives in one place
(`BuildingMesher.ringInOrder`) and covers all five roof shapes; the cross product decides, and the ground
plane `addQuad(a, d, c, b)` is the control that says which way up is.

The same pass found the second half of that fault and it was worse than a wrong sign: the shaped roofs
**had holes**, because each eave edge contributed one triangle towards the nearest ridge end and those
triangles do not tile a footprint. A dome or gable roof is now a complete ear-clipped deck at the eave line
with the shaped cap above it, for every building, and the vertex budget covers both (`10n + 32`) so a
building cannot run off the end of an index buffer the way the third install did.

**Two walker faults, in the locomotion audit the report demanded.** Pulling the stick back walked the
player *forwards*: the direction already carried the sign of the input and the speed was negative in the
same case, so the second negation cancelled the first -- a one-character bug that a phone found before any
test did. And **a jump could not be asked for on foot at all**: the jump flag was the UP button's held
value, and UP is disabled while walking, so the button that should jump was grey next to the stick. The
jump is now a discrete buffered request (`PlayerController.requestJump()`, 50 ms, consumed once) from its
own bilingual **JUMP** button, which cannot bunny-hop from a resting finger. The other three bodies were
audited against the same rubric -- ground contact, gravity, drag, steering, takeoff and landing -- and found
sound; `BodyFacingTest` now holds all five to their heading, and `HumanWalkTest` holds the walker.

**Also fixed, found by looking rather than by being told.** The minimap's tap target *was* the minimap's
small square, which is a target of a few millimetres on a phone; the drawn square is unchanged and the
target is now the whole top-right corner of the screen, with no button inside it and never reaching the
movement stick. And the map's own close button still exists, so the map that now opens can be closed by the
BACK key or by CLOSE / إغلاق.

**Two faults were the project's own, and CI caught both.** `Gdx.graphics.isGLThread()` does not exist in
libGDX 1.12.1 -- or in 1.13.0; it was written from memory and javac refused it in run `38788120436`, whose
error was readable from the repository because the compile gate writes its errors into `artifacts/`. Then
run `38789154142` failed a test that had hard-coded the *old* roof arithmetic (64 vertices) instead of the
bound it existed to enforce; the mesher was right and the assertion was wrong. It now meshes a five-sided
ring once per roof shape and asserts the reported cost covers every vertex the mesher then writes. The
local-name gate caught two shadowed locals in the same new code, which is the gate doing exactly what it
was built for: javac is not available here, so the gates are the compiler.

**Verified on this build, not argued**: compile gate `38791617643` (six source gates green), Build APK
`38791617660` with `tests=166 skipped=0 failures=0 errors=0` (`artifacts/UNIT_TEST_SUMMARY.txt`),
`validation=PASSED` (`artifacts/APK_VALIDATION.txt`) and `status=PASSED` (`artifacts/QA_STATUS.txt`), APK
**19,201,474 bytes**, SHA-256 `09cdf423…`. The APK paid 948 bytes over the one before it.

### Ninth install: the phone got hot, and the human and the car would not move

The report, word for word: **"problem with human car physics, cant move"**, **"heliopter flies but not
perfectly"**, **"most importantly mobile becomes very hot after playing for a few seconds"**, **"also
minmap crashes now also"**, and a request to keep building. Four faults, and the heat was the one that had
been making the game unplayable on a phone.

**The phone got hot in seconds, and there was no single expensive thing to find.** There were four:

* **Nothing capped the frame rate.** libGDX renders as fast as the device allows, and every frame here
  meshes, culls, draws a country and updates a HUD, so an idle phone was working at 120 frames a second on
  a game designed for 60. `QatarGame` now calls `setForegroundFPS(FOREGROUND_FPS)` with `FOREGROUND_FPS =
  60`: sixteen milliseconds of work a frame, and not a millisecond more.
* **Every resident chunk was drawn, including the ones behind the camera.** `ChunkCulling` tests a chunk's
  bounding sphere against the camera's own `Frustum`, and against the top of the chunk's box. A chunk
  behind the camera is still transformed and set up by the GPU before the clipper discards it, and with a
  72-degree vertical field of view on a 20:9 phone screen most of a six-kilometre disc is outside the
  frustum. This is the largest single saving in the renderer, and it costs one sphere test per chunk.
* **The streaming cache rebuilt the country for ever.** It meshed up to three chunks a frame and then
  evicted the farthest chunks to stay inside its memory budget -- and the chunks it had just evicted were
  still near the player, so they returned to the build list on the next frame. Build, evict, rebuild: three
  chunk meshes a frame, sixty frames a second, six hundred country meshes a minute on the GL thread, for
  geometry the frame never kept. The cache now **stops building while its window is full** (the ground
  under the player's feet is the one exception, because a hole where the player is standing is not a
  performance trade), keeps its candidates in parallel arrays instead of allocating a few hundred objects a
  frame, and evicts the chunk **farthest from the player** rather than the one inserted first -- which was
  wherever the player came from, not what they cannot see.
* **The geometry budget was already there; the work that filled it was not bounded.** With the window full,
  the steady state is a few hundred distance checks a frame and no meshing at all, which is what makes
  standing still cheap. A test walks the real cache for fifteen hundred frames of driving and ten seconds
  of standing still and counts the meshes (`StreamingWorkTest`).

And the phone can now say so itself: the diagnostics panel prints the measured frame time in milliseconds
(next to chunks resident, drawn and culled, draw calls, triangles, vertices against the budget, and the fog
distance), and the renderer **shrinks its draw distance** when frames run long -- 45 m a frame above
20.8 ms, recovering 15 m a frame below 16.1 ms, down to a floor of 2.8 km.

**"The human and the car cannot move" was two faults, and neither was in a body.** The bodies were fine;
the *points* were wrong:

* `WorldSurface.solidAt` asked "am I inside a building?" with the axis-aligned rectangle around each
  footprint. A rectangle is not a building: OSM buildings are rotated, L-shaped and clustered, so the box
  around a tower turned thirty degrees covers a square of plaza, pavement, courtyard and road that no wall
  passes through -- and a body inside it is refused in every direction by its own collision rule, with a
  panel that reports a speed of zero and no reason. On the compiled country this was not a corner case:
  **52 of the 279 car spawn points (18.6%)** are inside a building's box and outside every wall, and of
  43,287 sampled road vertices **1,029 (2.4%)** are the same. `solidAt` now ray-casts the building's own
  ring -- the polygon that is drawn is the polygon that is solid -- and keeps the rectangle only as a cheap
  reject. `WorldSurfaceFootprintTest` holds it against the real bundles, and it is written so that it
  *fails* if the rectangle rule comes back.
* A compiled spawn point can still be inside a real wall (14 of those 279), and a teleport can land in a
  courtyard. `SpawnClearer` searches outward in rings for ground a person can stand on and is asked at
  every spawn, every teleport and every vehicle change, reporting how far it had to move the player. The
  controller also **re-seats every body** when the real terrain arrives (`setSurface`), which is the state
  the game starts in and the one place a body could be spawned on a flat fallback twelve metres below the
  ground it was standing on.

**The minimap's tap did not crash the app; the map did not have a texture.** `HudRenderer.drawBigMap`
cropped a `TextureRegion` that had never been given a texture and drew it, which is
`NullPointerException: Texture.getWidth()` on a null at the top of the draw call -- the stack in the
report. The region is textured when it is built, the draw is guarded (a map that cannot be drawn draws
nothing, and says so once, on the panel, in both languages), and `tools/check_ui_state.py` fails on that
shape of line, so it cannot return quietly.

**"The helicopter flies but not perfectly" was four faults:**

* The hover trim ignored the disc's tilt and the air's density, so a machine asked to hold 25 m coasted to
  43 m. The trim is now the machine's own arithmetic -- `collectiveForAccel(state, accel)` answers "what
  collective produces this vertical acceleration on *this* machine, at *this* height, speed and
  attitude?" -- which is the same number derived from the same thrust model that flies it.
* The altitude hold **released itself** within a metre of the commanded height, carrying whatever climb
  rate it had built: that is what the coasting was. It is a critically damped spring-damper now (a
  four-second period: kp 2.5, kd 3.2), it stays engaged until the pilot takes the lever, and the machine
  settles on the commanded height instead of sailing past it.
* The cyclic's sign was inverted, so pulling the stick forward flew the machine **backwards** at 29 km/h.
* The flying stick was a *nudge*, not a stick: the deflection decayed back to trim at 1.6 a second, so a
  thumb held still for two seconds flew a machine that had already levelled itself out. It is a held stick
  now -- a drag on the right-hand ring *is* the deflection, and it stays deflected until the finger lets go
  -- drawn at rest with its own word under it, for the same reason the movement stick is. And the ring the
  player sees is the ring the touch handler measures: the drag of one radius is full deflection, which it
  was not (the movement stick drew a 150-pixel ring on a phone and reached full speed at sixty pixels).

**Also delivered in this round**, the three items the last one carried forward: **palms and vegetation**
(a palm is 96 vertices, scrub is 24, scattered over the landuse areas that say they are vegetation, with
their own surface type and their own branch in the terrain shader); **vehicle-to-vehicle contact** (the
machines you are not in are solid -- a walker bumps off a car's flank, a car is stopped by a parked
aeroplane, and the panel says `BLOCKED` rather than showing a speed of zero -- and the five machines are
parked in a line behind you at a spawn, a teleport or a vehicle change, because an arc cannot hold an
11-metre aeroplane and a 9-metre helicopter without overlapping them); and **streaming profiling** (the
panel's numbers, above).

**What this round cost, and what its own tests found.** Three builds, and the first two were red -- for
reasons worth writing down, because every one of them was found by the round's own tests rather than by a
phone:

* Build one: two compile errors (below). Both are now gates.
* Build two: **eleven of 205 tests failed**, and two of the eleven were real defects that nothing had been
  able to see before -- the helicopter that rested 20 cm above its own skids, and (found by the *next*
  build) palms and scrub written with vertices and no triangles at all, so that the country's vegetation
  was in the vertex buffer, in the vertex budget, and nowhere on the screen: `WorldRenderer.upload` refuses
  a mesh with an empty index buffer. The other nine failures were the tests' own faults, and each one is
  worth knowing, because a test that lies costs a build every time it runs:
  a culling test that could not run at all (`Frustum.update` calls the native `Matrix4.prj`, so a JVM
  cannot construct a `PerspectiveCamera` -- the rounds' own fault for reading a library from memory, and
  the fix is that the frustum planes now come from this project's arithmetic, which the renderer also
  uses); an allocation check that was reading a *comment* for a `new Color(`; a vertex-budget assertion
  made after the one frame of overshoot the round-eight budget test deliberately documents; two car spawn
  points in the country that stand in water rather than in a wall, which the test reported as a wall; a
  street corner with a real wall in one of the eight directions a body can walk; and a car bound tighter
  than the car on the sand it was driving on.
* Build three: **green -- 206 tests, 0 failures, 0 errors**, and the one that published.

The two compile errors were worth their round trip because both are now gates:

1. **`Math.SQRT2` is not Java.** `java.lang.Math` declares exactly two constants, `E` and `PI`; `SQRT2` is
   JavaScript's. javac said "cannot find symbol" and pointed at the dot. The gate now reads every `Math.`
   member in the tree against the members the JDK actually declares and names the JavaScript roots
   (`SQRT2`, `SQRT1_2`, `LN2`, `LN10`, `LOG2E`, `LOG10E`) with what to write instead.
2. **An `@Override` had drifted off its method.** `WorldSurface.insideFootprint` was inserted between the
   annotation and `materialId`, so the annotation landed on a private method. The gate fails on an
   annotation whose next line is a comment, an annotation whose method has no parameter list, and an
   annotation on a `private` method -- which can never override anything.

Both rules were checked against the broken revision before being trusted, and both reproduce the CI errors
exactly. Eight new tests joined the suite (`WorldSurfaceFootprintTest`, `HelicopterFlightTest`,
`StreamingWorkTest`, `FlyStickTest`, `HumanCarMovementTest`, `SpawnClearerTest`, `ChunkCullingTest`,
`VegetationTest`), the suite grew from 166 tests to 206, and the flagship of the round is the one that
walks the real streaming cache for fifteen hundred frames of driving and counts what it builds.

**Verified on this build, not argued**: compile gate `38878207681` (six source gates green), Build APK
`38878207670` with `tests=206 skipped=0 failures=0 errors=0` (`artifacts/UNIT_TEST_SUMMARY.txt`),
`validation=PASSED` (`artifacts/APK_VALIDATION.txt`) and `status=PASSED` (`artifacts/QA_STATUS.txt`), APK
**19,216,978 bytes**, SHA-256 `bfef57c1…`, published as the release's `apk` asset at 09:14:48Z on
2026-10-16. The APK costs 15,504 bytes more than the eighth install's: the vegetation's index buffers,
the culling arithmetic and the diagnostics of a round that was mostly spent making the frame cheaper,
against a country whose bundles did not change at all.

### Tenth install: the map teleported, and the frame went with it

The report, word for word: **"minimap teleport may be broken, teelport happens abnormmally or crashes ,
fix that and jkeep building . helicopter worked , but cant walk , use car . fix them"**. Note what is
*not* in it: the helicopter. The ninth install's flight work landed, and the machine that had been at the
centre of two rounds now worked -- which turned out to be the clue to everything else.

**Same fingers, same stick, different result: the fault had to be in what the ground does.** The frame
that a player moves in is not global: the world is drawn around a floating origin, and every entity
stores its position relative to it (see `WorldOrigin`). When the player travels far enough, the origin
moves and *every local transform moves with it*. A map tap is the worst case -- a hundred kilometres in
one frame -- so the teleport path found the fault first: the rebase moved the origin and the camera rig,
and nothing else. The player's own body kept its old local coordinate, and since the rig is re-derived
from the body every frame (`PlayerController.syncRig`) the rig was put straight back where it had
been: the origin rebased again on the next frame, and the next. One rebase per frame, tens of kilometres
at a time, for as long as the app was running.

What that looked like from a phone is the whole report. A tap that arrived somewhere else, or nowhere: the
player was placed where they tapped and then carried away from it, frame by frame. A country streaming
past at an absurd speed. Ground that was no longer theirs -- under a different building, or under the sea.
And a walker and a car that would not move a metre: their step is refused when the ground under them moves
like that, while a helicopter simply flies. Four faults, one cause, and the cause was invisible from
inside the game because everything still *looked* like the world.

The fixes, all in the tenth install's build:

* **A rebase now shifts the bodies** (`PlayerController.shiftLocal`), not only the rig -- and
  `OriginRebaseTest` holds the invariant from both sides, including a control that shows the same tap
  arriving more than ten kilometres away when the shift is left out.
* **A tap that is not on the map is refused.** `MapView.containsPoint` says whether the finger landed on
  the raster itself -- the drawn rectangle inside the HUD region, which changes with the zoom and the
  letterbox -- and a tap in the margin answers "Tap on the map to travel" instead of resolving to a place
  nobody asked for.
* **An arrival says where it arrived**, in which form (a spawn, a road, open ground), how far it was and
  which way the body now faces: `TeleportService.Target.headingDegrees` (a runway's threshold heading, a
  road's own tangent) and a marker drawn on the map at the destination.
* **The streaming warms up after a jump** (`GameWorldRenderer.onPlayerJumped`), so a teleport does not land
  on an empty world waiting for three chunks a frame.
* **The chunk cache honours the radius it is asked for** (`ChunkMeshCache.update` evicts at the radius it
  was given, not at a constant), which was making a slow phone thrash.
* **And the game now says why a body will not move.** "Cant walk" is four words; the body's own state
  names the rule that refused it -- solid at the feet, water too deep, ground not compiled, not on the
  ground -- and the message is printed in English over the Arabic twin, on the panel and in the log, with
  the body's own numbers.

### Eleventh install: a horizon where the fog was, and three holes

The report asked for the teleport fault to be fixed, the walker and the car to work, and for the building
to continue. The eleventh install answers with three faults of one shape -- geometry, or a code path, that
nothing owned -- and the first piece of the country the player could not previously see.

**The coastline of the streamed world was a hole.** The land pass drew a cell only when all four of its
corners were land, and the water pass drew one only when all four were water, so every cell straddling the
shore was drawn by neither: a band up to two cells wide -- two hundred metres at the detailed resolution --
of nothing at all along every coast in the country, with the sky behind it. Out in a boat, looking back at
the beach, the beach was missing. A cell now belongs to the layer its own flag names, and both layers take
a lattice point's height from one rule, so the beach and the sea meet at a shared edge instead of nearly
touching.

**The world ended in fog five kilometres out.** Not a bug -- the streamed chunks end there and the fog was
pulled in to hide the edge -- but the complaint behind it is real: stand on the Corniche and Doha had no
far side, and the sea stopped. The country was already in the bundle at 300 m a cell (`terrain_l0`, 2.3 MB
of it), and at five to eleven kilometres that is a couple of pixels tall. `HorizonMesh` builds it into one
polar ring from 2.4 km to 11.4 km -- 48 rings, 128 divisions, 6,144 cells, about twelve thousand triangles,
two draw calls, resolution following distance for free -- and the fog now runs to the ring's own far edge,
where it is exactly the colour of the sky's haze. The streamed chunks overlap its inner rings, so on a slow
phone (where the chunk window shrinks to 2.8 km) there is still no gap to see through. It is rebuilt only
after the player has travelled a kilometre and a half, and dropped on a teleport, because a horizon of the
country a hundred kilometres away is worse than none.

**A tap could close the app, and the crash reporter had nothing to say about it.** This one is worth the
two lines of library source it was found in. In libGDX 1.12.1, `AndroidGraphics.onDrawFrame` reads:

    app.getInput().processEvents();                     // every touch and key handler runs here
    app.getApplicationListener().render();              // ... and this game's frame guard is in here

Only the second line is inside a guard. The handlers run on the GL thread, outside any try/catch this game
controls, so an exception from a touch handler killed the GL thread and the process with it -- silently,
with the game's own "a frame failed" reporter never reached. Every report of a tap closing the app,
"opening the minimap crashes it" and "teleport crashes" included, was that hole. `GuardedInput` wraps the
processor: a failed event is discarded, the panel names the handler, the crash record gets the stack, and
the game carries on. `resize`, `pause`, `resume` and `dispose` are wrapped for the same reason -- they are
outside the frame guard too, and rotating the phone calls one of them.

**And a body that cannot move is now moved.** Words were the tenth install's answer to "cant walk"; words
are not enough when the body has been *placed* badly -- inside the footprint of a building, in water deeper
than it can cross, or on ground whose collision was never compiled. After two seconds of asking and going
nowhere, the game searches outwards in 8 m rings for the nearest spot this kind of body could move from --
standable ground at the same height, outside anything solid, no more than a step away -- and steps it
there, with the distance in the message. `SpawnClearer.nearestUsable` carries the rules per kind: a walker
wades, a car does not swim, a boat needs water under its keel. Flying bodies are never moved (a helicopter
that will not fly is a different fault, and hiding it would be worse), and a rescue will not run twice in
six seconds, so leaning on a wall does not teleport the player anywhere.

**What the round taught about its own gates.** The compile gate caught the eleventh install's only compile
error -- `GameWorldRenderer` used `HorizonMesh` from another package without importing it, six javac errors
from one missing line -- and every local gate had passed, because every local gate checked *names* and this
was an *import*. `tools/check_java_symbols.py` now indexes every type the project declares and the package
that declares it, and fails on a capitalised name used in a file that is neither in that package, nor
imports it, nor declares it itself. Run against the broken revision it reproduces all six javac errors,
line for line, in under a second, which is the only reason to trust a new gate.

**The build that shipped was the second attempt, and both failures were in the tests.** The first run of
the round (commit `13b8eb7`) compiled, built the country, and ran 225 tests to two failures -- and neither
failure was the game. The horizon test checked every triangle's winding by reading an index out of the
mesh's `short[]` buffer, on a mesh the test had deliberately filled to 71,500 vertices: past vertex 32,767
the index reads back negative, so the check indexed the vertex array *backwards* and threw
`ArrayIndexOutOfBoundsException: Index -360437`. The index buffer is unsigned 16-bit -- which is exactly
why a mesh holds at most 65,000 vertices, the limit the compile gate's third rule came from -- so the check
now masks to `0xFFFF` before it looks anything up, which is the read a GPU does. The other failure was a
fixture that could never have passed: the cliff case was built from the shore helper, whose land side is
`depth 0.2`, so the body was wedged in deep water *and* a step from a cliff, and the rescue was right to
refuse every direction -- east was a fifty-metre drop, west was drowning. The case now builds its own
surface, dry on both sides of the line, with the body inside a compound's bounding box, so the step rule is
the only thing refusing the drop; it asserts the landing is one ring out, at the body's own height, outside
the box. `ebfa20b` is that fix and the build the release now carries: **225 tests, 0 skipped, 0 failures,
0 errors**, APK **19,226,838 bytes**, SHA-256 `8efb1d0b…`, `validation=PASSED`, stamp `0.1.0-m0+diag11`.

## Honest limitations

1. **This build has been on a phone eleven times, and every report found something real** — thirty
   faults
   in all, listed in the sections above, each one now fixed and, where a test can hold it, held. Two
   reports arrived with no symptom in them at all, so an audit was the diagnosis; one arrived as a stack
   trace of the JVM's own printer, so the frames had to be chosen better before the next one was useful.
   The seventh was the most instructive of all: a report with nothing in it, from a build whose
   diagnostics were being culled by the GPU, because two subsystems disagreed about GL state and neither
   of them owned the contract. The eighth confirmed that round seven's controls do come up -- the minimap
   was found and tapped -- and then broke its own app by trying to clean up after a failure, which is the
   kind of fault no amount of world-building finds. **Rounds six, seven and eight have not been confirmed
   on the device for their own fixes yet; the next report is the one that says whether the buildings are
   filled and the map opens and closes.** The reason to expect them now is not
   optimism: the state is set by the code that needs it, and a source gate fails the build if it stops
   being. There is still no GPU and no Android emulator in the build environment: the GLSL in this project
   compiles for the first time on a phone. Nothing here can compile GLSL; what stands in for a GPU is the
   shader lint, the on-screen diagnostics, and the fact that the panel has so far named its failures
   precisely enough to fix them — including the one it named with no words from the phone at all.
   **The tenth install's teleport fault and the eleventh's horizon have not been measured on a phone by
   anyone here either**: what can be said is what changed, and that the two faults the tenth and eleventh
   reports describe now have tests that fail without the fixes -- one rebase per frame is asserted against,
   and so is every cell of the ring and every cell of the coastline. **The ninth install's heat has not
   been measured on a phone by anyone here.** What can be said is what
   changed and how much of the work it removes: the frame rate is capped at 60, most of the disc is culled
   before it is drawn, the streaming cache stops meshing when its window is full rather than rebuilding the
   far ring every frame, and the draw distance shrinks when frames run long. The panel now prints the frame
   time, so the next report can say what the phone is actually doing instead of how warm it is.
2. No sound.
3. ~~Buildings collide as their bounding box, not as their drawn ring: a courtyard is walkable through.~~
   **Fixed this round**: the walls decide, the box is only a cheap reject, and a test over the real country
   fails if the box comes back. What remains is that barriers (fences, walls, hedges) collide as lines of a
   fixed half-width and a single height, so a gap in a fence that is not mapped as a gate is not a way
   through.
4. ~~The world is drawn only within the streaming radius: beyond roughly 6 km it fades into fog and
   stops.~~ **Fixed in the eleventh install**: the streamed chunks still end at roughly 6 km, but the
   country's own terrain at 300 m a cell carries the view on to 11.4 km, which is inside the camera's far
   plane, and the fog now ends there rather than hiding the boundary. What is still not drawn at distance
   is anything the detail radius owns -- buildings, roads and palms beyond 2.5 km -- so a skyline appears
   as the player approaches it rather than being there from the start.
5. ~~Vehicles do not collide with each other.~~ **Partly fixed this round**: the machines the player is
   not in are solid to the one they are, and are parked in a line behind them at a spawn, a teleport or a
   vehicle change. The parked machines themselves are not simulated -- an aeroplane parked in the sea stays
   in the sea -- and two of them do not push each other, because nothing is driving them.
6. ~~Performance has never been profiled.~~ **Partly addressed this round**: the panel reports chunks
   resident, drawn and culled, draw calls, triangles, vertices against the budget, the frame time in
   milliseconds and the fog distance, and the renderer acts on the frame time by shrinking the draw
   distance. What is still missing is a profile taken *on a phone*: the numbers here come from the code's
   own counters and from tests that walk the streaming cache, not from a thermal measurement of a
   handset.
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
git checkout ebfa20b            # the commit the published APK was built from
# world data (downloads OSM + SRTM, compiles the country, validates it):
python3 tools/fetch_sources.py && python3 tools/build_world.py && python3 tools/game_world.py
python3 tools/validate_game_world.py --world android/assets/world     # 57/57
# engine tests (no device, no GPU needed):
cd core && ../gradlew test
# the APK:
cd ../android && ../gradlew assembleDebug && python3 ../tools/validate_apk.sh \
    build/outputs/apk/debug/android-debug.apk
```

The checks CI runs on every push are `compile.yml` (the compile gate), `build.yml` (unit tests,
APK build and validation, release publication) and `archive.yml` (the repository archive). `data.yml`
recompiles the country from source, and is the one workflow not to trigger on a path it also writes to:
it publishes its own provenance into `data/geodata/**`, and while that directory was one of its push
triggers every run started the next one -- five full country compiles were queued that way before it was
noticed, each of them re-downloading the same sources to rewrite the same evidence.
