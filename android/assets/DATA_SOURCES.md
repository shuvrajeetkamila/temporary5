# Data sources and licences

Every byte of world data in this project comes from a redistributable open source. Nothing in the
compiled bundle is traced, derived, or approximated from proprietary imagery, proprietary terrain,
proprietary road data, or any service whose terms forbid redistribution.

| Data | Source | Licence | Retrieved by | Verified |
|---|---|---|---|---|
| Coastline, boundaries, roads, buildings, land use, place names, island names | OpenStreetMap Qatar extract, mirrored by [OpenStreetMap France](https://download.openstreetmap.fr/extracts/asia/qatar-latest.osm.pbf) (mirrors tried in order: OpenStreetMap France, BBBike, Geofabrik GCC states) | ODbL 1.0 (© OpenStreetMap contributors) | `tools/fetch_sources.py` | PBF container validated, relation 305095 checked for the country code `QA`, municipality count checked |
| Elevation | NASA/USGS SRTM 1 arc-second, packaged as AWS Open Data Terrain Tiles (`elevation-tiles-prod`), tiles N24–N26 / E50–E52 | Public domain | `tools/fetch_sources.py` | SHA-256 + size recorded per tile in `data/geodata/SOURCES.json` |
| Administrative fallback boundary | Natural Earth 10m admin-0 countries | Public domain | documented fallback (fetched through the GitHub API when the primary mirrors are unreachable) | not used in the current bundle |
| Arabic typeface | Amiri (Regular) by Khaled Hosny | SIL Open Font License 1.1 | `assets/source/fonts/Amiri-Regular.ttf` | cmap checked for the exact presentation-form codepoints the shaper emits; licence copy shipped in the APK under `assets/licenses/Amiri-OFL.txt` |
| Engine | libGDX 1.12.1 | Apache 2.0 | Gradle dependency | not redistributed as a modified artefact |

## Provenance recorded at build time

`tools/fetch_sources.py` writes `data/geodata/SOURCES.json`, which records for every input: the id, the
human readable name, the exact URL, the retrieval timestamp (UTC), the byte size, the SHA-256 digest
and the licence. `tools/build_world.py` writes `data/geodata/SHA256SUMS` plus per-file digests into
`android/assets/world/manifest.json`, so a packaged APK can be traced back to the exact inputs that
produced it.

## Attribution requirement

OpenStreetMap's licence requires visible attribution. The app ships it in three places:

* the loading screen and the credits screen both display **© OpenStreetMap contributors**;
* `android/assets/world/manifest.json` carries the attribution strings, so the runtime can always
  render them even if the UI is replaced;
* `assets/licenses/` ships the ODbL notice and the Amiri OFL text inside the APK.

## What is deliberately not used

* No proprietary satellite or aerial imagery.
* No proprietary elevation models (for example commercial lidar or photogrammetry).
* No ripped game assets, no brand logos, no proprietary vehicle models.
* No music or sound effects that are not either generated in this repository or licensed for
  redistribution; the current build ships no audio at all.
* No network access at runtime: the application does not request the `INTERNET` permission, and the
  APK validation gate fails the build if that permission ever appears.
