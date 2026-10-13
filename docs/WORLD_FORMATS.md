# Compiled world formats

Every byte that ends up on the phone is described here. The compiler (`tools/`) writes these files and
the game (`core/`) reads them; when the two disagree, nothing else in the project matters, so the format
is written down in one place and both sides are tested against a fixture built by the same packers
(`tools/selftest_game_world.py`).

Three rules hold for every bundle:

1. **Little-endian**, everywhere, no padding except where a record says `pad`.
2. **Record-stream bundles** start with `<magic char[4]><version uint32><count uint32><payload bytes uint64>`,
   followed by `count` variable-length records. The bytes after the header are exactly the payload size.
3. **Files on disk are gzip members** (`.bin.gz`, mtime 0, level 6, so a rebuild is byte-identical). The
   manifest's `sha256` is the digest of the **file on disk**, not of the inflated payload: a checksum
   must be verifiable without knowing the format.

## Coordinates

| Space | Unit | Where |
| --- | --- | --- |
| Packed geometry (roads, buildings, land use, water, barriers, points) | int32 **centimetres**, absolute EPSG:32639 | in the bundles |
| Terrain and material grids | int16 metres / uint8 id, **grid-relative** | indexed by `(east - originEast) / cellM` |
| Runtime geometry | float metres, national frame relative to the grid origin | chunks, meshes, maps |
| Simulation and camera | double metres, relative to the floating origin | physics, movement |

Packed geometry is delta-encoded: the first vertex of a record is absolute (`int32` centimetres), every
following vertex is a delta from the previous one. A ring that ends where it began repeats its first
vertex, so a closed ring always has `points >= 4` and its first and last vertices are equal.

## Stage 1 bundles

### `QTT1` terrain (`terrain_l0.bin.gz`, `terrain_l1.bin.gz`)

Header is **not** the record-stream header: `magic char[4]`, `cols uint32`, `rows uint32`,
`cellM float32`, `reserved uint32` (20 bytes), then:

* `int16 heights[cols * rows]`, row-major, **row 0 is the south edge**, metres above sea level;
* `uint8 landMask[cols * rows]`, `1` = land, `0` = water, same order.

### `QTM1` materials (`material_l1.bin.gz`)

Header as `QTT1`, then `uint8 ids[cols * rows]`: the compiled surface material of each cell
(`0` unknown, `1`–`16` see `Palette` in the game). There is **no version field**: the second `uint32` of
the header is `rows`.

### `QTR1` roads (`roads.bin.gz`)

Per record: `roadClass uint8`, `flags uint8` (1 bridge, 2 tunnel, 4 one-way), `lanes uint8`,
`speedKph/5 uint8`, `widthM*10 uint16`, `nameIndex uint16`, `nameArIndex uint16`, `points uint16`,
then the delta-encoded vertices. Road classes are OSM `highway` values, ranked: 3 = primary, 7 =
residential, 8 = service (see `ROAD_CLASS` in `tools/build_world.py`).

### `QTC1` coastline (`coast.bin.gz`)

Per ring: `islandId uint32`, `points uint16`, then delta-encoded vertices.

### `QTS1` strings (`strings.bin.gz`)

`magic char[4]`, `version uint32`, `count uint32`, then per string `length uint16` + UTF-8 bytes.
Index `0xFFFF` means "no name" and is never a valid index. Stage 2 appends to this table and rewrites
it: existing indices never move, so everything already packed stays valid.

## Stage 2 bundles

Stage 2 (`tools/game_world.py`) replaces the stage-1 box approximation of buildings with real footprints
and adds the features a playable world needs.

### `QBG1` buildings (`buildings.bin.gz`)

Per record: `east_cm int32`, `north_cm int32`, `points uint16`, `height_dm uint16` (roof above local
ground; 0 = unknown), `minHeight_dm uint16` (0 = sits on the ground), `levels uint8`, `type uint8`,
`roof uint8`, `flags uint8` (1 = one part of a multi-part footprint, 2 = named, 4 = landmark),
`nameIndex uint16`, then `points - 1` delta pairs.

`roof` selects the shape the mesher extrudes: 0 flat, 1 gable, 2 hipped, 3 pyramid, 4 dome, 5 conical.
`type` is the compiled building class (villa, apartment, mosque, tower, industrial, …).

### `QLU1` land use (`landuse.bin.gz`) and `QWA1` water (`water.bin.gz`)

Both use the area record: `east_cm int32`, `north_cm int32`, `points uint16`, `class uint8`,
`kind uint8`, `flags uint8`, `pad uint8`, `nameIndex uint16`, then the delta pairs. For `QLU1`,
`kind` is `0` surface / `1` vegetation; for `QWA1` it is `0` sea / `1` inland / `2` wadi (dry).

### `QBA1` barriers (`barriers.bin.gz`)

Per record: `east_cm int32`, `north_cm int32`, `points uint16`, `kind uint8` (0 wall, 1 fence,
2 hedge, 3 retaining wall, 4 gate), `flags uint8`, `height_dm uint16`, `nameIndex uint16`, then the
delta pairs. These become the low walls, fences and hedges the player walks around.

### `QPO1` points (`pois.bin.gz`)

Per record: `east_cm int32`, `north_cm int32`, `kind uint8`, `poiClass uint8`, `flags uint8`,
`pad uint8`, `nameIndex uint16`, `facingDeg*10 uint16`. `kind` is what the game spawns there:
0 car, 1 helicopter, 2 boat, 3 aeroplane, 8 landmark.

## The manifest (`manifest.json`)

Stage 1 writes format 1; stage 2 rewrites the same file as **format 2**, keeping every stage-1 field and
adding the game bundles, so a reader that only knows format 1 still finds the terrain where it expects
it.

```json
{
  "format": 2,
  "worldEpoch": 20261012,
  "crs": "EPSG:32639",
  "grid": { "originEast": 431000.0, "originNorth": 2681000.0, "widthM": 259000.0, "heightM": 268000.0 },
  "levels": [ { "name": "l0", "cellM": 300.0, "cols": 864, "rows": 894, "file": "terrain_l0.bin.gz", "sha256": "…" } ],
  "materials": { "file": "material_l1.bin.gz", "cellM": 100.0, "cols": 2590, "rows": 2680, "sha256": "…" },
  "roads": { "file": "roads.bin.gz", "count": 179943, "sha256": "…" },
  "buildings": { "file": "buildings.bin.gz", "count": 186993, "sha256": "…" },
  "coast": { "file": "coast.bin.gz", "rings": 320, "sha256": "…" },
  "strings": { "file": "strings.bin.gz", "count": 16780, "sha256": "…" },
  "gameBundles": {
    "buildings": { "file": "buildings.bin.gz", "records": 186993, "sha256": "…" },
    "landuse": { "file": "landuse.bin.gz", "records": 0, "sha256": "…" },
    "water": { "file": "water.bin.gz", "records": 0, "sha256": "…" },
    "barriers": { "file": "barriers.bin.gz", "records": 0, "sha256": "…" },
    "pois": { "file": "pois.bin.gz", "records": 0, "sha256": "…" }
  },
  "stats": { "landAreaKm2": 11502.5, "buildings": 186993, "spawnPoints": { "car": 0, "helicopter": 0, "boat": 0, "aeroplane": 0 } },
  "attribution": [ "…" ]
}
```

The digests are recomputed from the files on disk while the manifest is written, so a manifest cannot
describe a bundle that is not there, or a stale one.
