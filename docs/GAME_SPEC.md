# Qatar: The Peninsula — the brief this repository is answering

This is the request, kept in the repository so it cannot drift. Everything below is binding; `docs/PLAN.md`
says how far along each part is, and every claim of "done" in these documents is meant to point at a
green CI run, a byte-level test, or a file that can be inspected.

## The five steps, in the order they were asked for

1. **The whole country, in 3D.** Download the entire Qatar OpenStreetMap extract and turn it into 3D:
   every street, every building footprint with its real shape preserved. Everything becomes 3D.
2. **Five ways to move, with their physics.** Add a human, a car, an aeroplane, a helicopter and a boat,
   each with its own physics, and make the map interactable.
3. **Detail assets as needed.** Windows, building and road detail, a visible body for the human and for
   each of the four vehicles, and whatever else the world needs to stop looking like a block model.
4. **A minimap in the controls, with a button for every locomotion type.** The map is interactable:
   pressing the minimap opens a big map; the big map zooms in and out; touching a place on it teleports
   the player there.
5. **Build the APK.** An installable Android package of the result.

## Standing requirements

- **Deliverable**: a directly installable, debug-signed Android APK, published where it can be downloaded
  without an account, with its byte count and SHA-256 published next to it.
- **Scale and coverage**: the entire State of Qatar at 1:1 scale — land, islands, the water around it and
  the airspace above it.
- **Bilingual**: every player-facing string exists in Arabic and English, with Arabic drawn correctly
  (joined, right to left), not stored as disconnected forms.
- **Attribution**: OpenStreetMap (ODbL) and SRTM credits appear in the app and in the documentation.
- **Simulation contracts**: a floating origin that keeps metres small at the far end of a 259 km country,
  and fixed-step physics that does not depend on the frame rate.
- **Honesty**: the final report states the APK link, its bytes, its SHA-256, the CI run id and
  conclusion, and the limitations that are actually true of the build.
- **Size and time are not constraints.** Large bundles and long builds are acceptable; doing it properly
  is the priority.

## What "interactable" means here

- The map is live: terrain, roads, buildings, water and land use are drawn from the compiled country, and
  the player can walk, drive, fly and sail through it.
- Terrain, buildings and barriers resist movement: bodies collide with the world rather than passing
  through it.
- Tapping the big map travels to that place, resolved to somewhere a person can actually stand.

## Non-goals, stated so they do not become surprises

- Not a store release: the APK is debug-signed, which is what "directly installable for testing" needs.
- No network at run time: the app declares no INTERNET permission and bundles everything.
- The vehicle bodies are reduced-order models, not a full rigid-body simulation; `docs/PLAN.md` and the
  class comments say which effects are modelled and which are not.
