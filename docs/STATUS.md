# Qatar: The Peninsula — what is built, and what comes next

One page, two lists, kept up to date as the work moves. The long version of both is in
`docs/FINAL_REPORT.md` (what is built, install by install, with the evidence) and `docs/PLAN.md` (the
order of the work and the reasoning). This file is the summary.

**Current build:** stamp `0.1.0-m0+diag12`, run
[`38902085846`](https://github.com/shuvrajeetkamila/temporary5/actions/runs/38902085846) on commit
`1ff3192`, 239 tests, APK **19,232,606 bytes**, SHA-256 `1241cbf0…`.

## What is built

### The country (step 1)

* The whole State of Qatar at 1:1 scale, compiled from OpenStreetMap and SRTM into bundles carried inside
  the APK: 259 km × 268 km, 11,502 km² of land and 57 islands, EPSG:32639.
* **186,993 buildings** with their real OSM ring shapes, heights and roof shapes (flat, gable, dome,
  pyramid, shed); **179,943 road ways** with class, lanes, surface, bridges and tunnels; 5,784 land-use
  polygons; 1,130 water polygons carved into the terrain; 421 barriers; 2,254 points of interest
  (279 car, 51 boat, 30 aeroplane, 6 helicopter, 418 landmarks); 16,780 bilingual names.
* Terrain from SRTM at 100 m (level 1) and 300 m (level 0), plus a 300 m material grid, so ground reads
  as dune sand, sabkha, tidal mud, mangrove, gravel or urban pad.
* Everything drawn as real geometry: buildings extruded with levelled bases and shaped roofs, roads as
  ribbons with painted lane markings, barriers as walls and hedges, palms and bushes as vegetation.
* 500 m chunks streamed around the player with per-chunk level of detail, an LRU cache, frustum culling,
  a floating origin for metre precision across the country, and a frame-time-driven draw radius.

### Movement (step 2)

* Five bodies, each with its own physics: **human** (walk, run, 0.42 m step, jump, wade), **car**
  (bicycle model, tyre grip, power limit, bogging), **aeroplane** (trimmed angle of attack, stall that
  drops the nose and recovers in a dive, rotation, hard-landing scrub), **helicopter** (collective lag,
  cyclic tilt, rotor damping, tail-rotor yaw, altitude hold), **boat** (heave, buoyancy, planing lift,
  Froude resistance, beaching).
* The world resists movement: buildings and barriers are solid at the body's own altitude, cliffs and
  kerbs are steps, water that is too deep cannot be walked into, and terrain that has not streamed in is
  not walked onto.
* A body that has been placed badly (inside a footprint, out of its depth, on uncompiled ground) is
  stepped to the nearest spot that kind of body can use, and the message says how far.
* Switching locomotion parks the other machines in a line behind the player, and they are solid to the
  one being driven.

### Detail (step 3)

* Windows are procedural in the shader, banded in real metres from the compiled heights, so they do not
  swim with the camera and need no texture.
* Road markings, kerb-level detail on walls, roof plant and panel seams at real sizes, doorways along
  street level, arcades on mosques, curtain walls on landmarks.
* A visible, part-animated body for every locomotion: the car's four wheels roll at v/r on the same
  wheelbase the physics steers on, the aeroplane's propeller turns from idle to 2,400 rpm, the
  helicopter's rotor head is governed near 330 rpm under a tail rotor turning 2.5× as fast.
* Text is treated as art: the HUD fonts are generated from the licensed Amiri typeface (663 glyphs per
  font), every character in the country's 17,718 names has a glyph, and Arabic is shaped and reordered
  correctly — every player-facing string exists in both languages.

### The map and the controls (step 4)

* A minimap in the controls that opens the whole country; drag to pan, pinch or `+`/`-` to zoom, `FIND
  ME` to recentre, `CLOSE` to return.
* Tapping a place teleports the player there — resolved to somewhere a person can actually stand
  (nearest named spawn, else nearest road within 900 m, else the nearest shore), never the middle of the
  sea, with the arrival reported in metres and heading.
* A button for every locomotion type, plus `WALK`/`DRIVE`, `RUN`, `BRAKE`, `UP`, `DOWN`, `VIEW`, `MAP`,
  labelled in English and Arabic.

### The far world (built in the last four installs)

* A horizon: the country's own terrain at 300 m a cell out to 11.4 km, so the world no longer ends in a
  wall of fog five kilometres out; the fog now reaches the horizon's own edge.
* A skyline: every building past the 2.5 km detail radius extruded as one coarse block, tallest per 90 m
  cell, out to 8 km, so the city carries on into the distance instead of stopping at a line.
* A diagnostics panel that fits on a phone: version stamp, position, speed, chunk streaming, frame time,
  fog, origin rebases, horizon, distant city — the numbers every fault report is diagnosed from.
* Every frame-visible path is guarded: a failure in a touch handler, a frame, or a lifecycle callback is
  reported on screen and the game carries on instead of closing.

### The APK (step 5)

* Debug-signed, three ABIs (arm64-v8a, armeabi-v7a, x86_64), no INTERNET permission, whole country
  bundled, published on every green CI run with its byte count and SHA-256.
* Validation is byte-level in CI: entry count, dex files, native libraries per ABI, world bundles and
  their manifest checksums, licence files, fonts, signature, zip integrity.

## What comes next, in order

1. **Roads and palms at distance.** The buildings now carry on past the detail radius; roads and
   vegetation still stop at 2.5 km, so a road visibly ends while the blocks beside it continue. The same
   trick as the skyline: the compiled ways thinned by class, drawn as one coarse ribbon mesh.
2. **A profile read off a phone.** The panel reports frame time, chunks, triangles, draws, the horizon
   and the block count; nobody has read it on a handset yet. The distant city's own cost is the one
   number this machine cannot measure.
3. **The rescue's inverse: water.** A body driven into deep water with the shore more than 160 m away
   cannot be rescued anywhere, and the message says so. Swimming, or a "call the boat" button, is the
   honest fix.
4. **Barrier gaps.** Barriers collide as lines of a fixed half-width and one height, so a mapped gap in a
   fence is not a way through unless it is a gate.
5. **Then, as far as the list reaches:** doors and interiors, a walking animation for the person, curved
   silhouettes for the bodies, sound, and traffic that moves on the roads.
