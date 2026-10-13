#!/usr/bin/env python3
"""Build a tiny synthetic world bundle and prove the byte contracts the Java loaders depend on.

Two jobs in one file, because they must never drift apart:

  1. Deterministic fixture: writes `core/src/test/resources/synthetic_world/` (terrain, materials,
     roads, buildings, land use, water, barriers, points, strings, manifest). Committed to the
     repository, so the JVM tests exercise the real bundle formats without needing the 12 MB country
     compile or a network fetch.
  2. Contract test: decodes every bundle back to coordinates and compares against the geometry that
     went in, asserts the magic numbers, version, record counts and payload lengths, and asserts the
     manifest digests match the files on disk.

Run locally and in CI:  python3 tools/selftest_game_world.py
"""

from __future__ import annotations

import gzip
import hashlib
import json
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import numpy as np  # noqa: E402
from shapely.geometry import LineString, Polygon  # noqa: E402

import build_world as bw  # noqa: E402
import game_world as gw  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
FIXTURE = REPO / "core/src/test/resources/synthetic_world"

# A 1.6 km square anchored 500 m south-west of Doha city centre, on real Qatar ground.
ORIGIN_E = 553000.0
ORIGIN_N = 2796000.0
CELL_M = 100.0
COLS = ROWS = 16

FAILURES: list[str] = []


def check(condition: bool, message: str) -> None:
    if condition:
        print(f"PASS  {message}")
    else:
        print(f"FAIL  {message}")
        FAILURES.append(message)


def rect(cx: float, cy: float, width: float, depth: float) -> Polygon:
    hw, hd = width / 2.0, depth / 2.0
    return Polygon([(cx - hw, cy - hd), (cx + hw, cy - hd), (cx + hw, cy + hd), (cx - hw, cy + hd),
                    (cx - hw, cy - hd)])


def build_fixture() -> dict:
    """Deterministic synthetic geometry: known coordinates, known counts, known expected output."""
    # --- terrain: a ridge running north-south, water in the eastern third ---------------------
    heights = np.zeros((ROWS, COLS), dtype=np.int16)
    land = np.zeros((ROWS, COLS), dtype=np.uint8)
    material = np.full((ROWS, COLS), bw.MATERIAL["water_open"], dtype=np.uint8)
    for row in range(ROWS):
        for col in range(COLS):
            east_edge_water = col >= 11
            if east_edge_water:
                heights[row, col] = 0
                land[row, col] = 0
                material[row, col] = bw.MATERIAL["water_open"] if col > 13 else bw.MATERIAL["water_shallow"]
            else:
                height = 2.0 + (col * 1.5) + (3.0 if 6 <= row <= 9 else 0.0)
                heights[row, col] = int(round(height))
                land[row, col] = 1
                material[row, col] = bw.MATERIAL["sand_compact"] if col < 4 else bw.MATERIAL["gravel_rock"]
    terrain_payload = bw.pack_terrain({"originEast": ORIGIN_E, "originNorth": ORIGIN_N,
                                       "widthM": COLS * CELL_M, "heightM": ROWS * CELL_M},
                                      CELL_M, heights.astype("float64"), land)
    material_payload = bw.pack_materials({"originEast": ORIGIN_E, "originNorth": ORIGIN_N,
                                          "widthM": COLS * CELL_M, "heightM": ROWS * CELL_M},
                                         CELL_M, material)

    # --- roads --------------------------------------------------------------------------------
    # Row 8 east-west at exactly ORIGIN_N + 8.5*CELL (the terrain cell centre line), a north-south
    # street, and a curved road, so the mesher is tested on axis-aligned and oblique geometry.
    lane_north = ORIGIN_N + 8.5 * CELL_M
    lane_east = ORIGIN_E + 5.5 * CELL_M
    roads = [
        {"class": bw.ROAD_CLASS["primary"], "projected": [(ORIGIN_E + 5.0, lane_north),
                                                          (ORIGIN_E + 1500.0, lane_north)],
         "lanes": 3, "width": 14.0, "speed": 90, "oneway": False, "bridge": False, "tunnel": False,
         "name_en": "Corniche Street", "name_ar": "شارع الكورنيش"},
        {"class": bw.ROAD_CLASS["residential"], "projected": [(lane_east, ORIGIN_N + 5.0),
                                                              (lane_east, ORIGIN_N + 1100.0)],
         "lanes": 2, "width": 6.0, "speed": 50, "oneway": True, "bridge": False, "tunnel": False,
         "name_en": "Al Markhiya Street", "name_ar": "شارع المرخية"},
        {"class": bw.ROAD_CLASS["service"], "projected": [(lane_east - 200.0, ORIGIN_N + 200.0),
                                                          (lane_east - 60.0, ORIGIN_N + 260.0),
                                                          (lane_east + 60.0, ORIGIN_N + 300.0),
                                                          (lane_east + 200.0, ORIGIN_N + 200.0)],
         "lanes": 1, "width": 4.0, "speed": 30, "oneway": False, "bridge": False, "tunnel": False,
         "name_en": None, "name_ar": None},
    ]

    # --- buildings: shapes with known vertex counts --------------------------------------------
    def building(rings, height, levels, btype, name, roof=gw.ROOF_FLAT, landmark=False,
                 min_height=0.0) -> dict:
        return {"rings": rings, "height": height, "min_height": min_height, "levels": levels,
                "type": btype, "roof": roof, "name": name, "landmark": landmark}

    mosque = rect(ORIGIN_E + 260.0, ORIGIN_N + 300.0, 40.0, 30.0)
    l_shape = Polygon([(ORIGIN_E + 100.0, ORIGIN_N + 100.0), (ORIGIN_E + 160.0, ORIGIN_N + 100.0),
                       (ORIGIN_E + 160.0, ORIGIN_N + 130.0), (ORIGIN_E + 130.0, ORIGIN_N + 130.0),
                       (ORIGIN_E + 130.0, ORIGIN_N + 160.0), (ORIGIN_E + 100.0, ORIGIN_N + 160.0),
                       (ORIGIN_E + 100.0, ORIGIN_N + 100.0)])
    tower = rect(ORIGIN_E + 700.0, ORIGIN_N + 900.0, 30.0, 30.0)
    part_a = rect(ORIGIN_E + 420.0, ORIGIN_N + 700.0, 24.0, 24.0)
    part_b = rect(ORIGIN_E + 448.0, ORIGIN_N + 700.0, 24.0, 24.0)
    buildings = [
        building([mosque.exterior], 9.5, 2, bw.BUILDING_TYPE["mosque"], "Synthetic Grand Mosque",
                 gw.ROOF_DOME, landmark=True),
        building([l_shape.exterior], 7.0, 2, bw.BUILDING_TYPE["house"], None),
        building([tower.exterior], 120.0, 40, bw.BUILDING_TYPE["tower"], "Synthetic Tower",
                 gw.ROOF_FLAT, landmark=True),
        building([part_a.exterior, part_b.exterior], 12.0, 4, bw.BUILDING_TYPE["apartments"], None),
    ]

    # --- land use, water, barriers --------------------------------------------------------------
    landuse = [
        {"ring": rect(ORIGIN_E + 300.0, ORIGIN_N + 300.0, 600.0, 500.0).exterior,
         "class": bw.MATERIAL["urban_pad"], "kind": gw.LANDUSE_KIND_SURFACE, "name": None},
        {"ring": rect(ORIGIN_E + 900.0, ORIGIN_N + 1200.0, 400.0, 300.0).exterior,
         "class": bw.MATERIAL["sand_loose_dune"], "kind": gw.LANDUSE_KIND_SURFACE, "name": None},
        {"ring": rect(ORIGIN_E + 200.0, ORIGIN_N + 1000.0, 300.0, 200.0).exterior,
         "class": bw.MATERIAL["farm_soil"], "kind": gw.LANDUSE_KIND_VEGETATION, "name": None},
    ]
    water = [
        {"ring": rect(ORIGIN_E + 1000.0, ORIGIN_N + 400.0, 120.0, 90.0).exterior,
         "class": bw.MATERIAL["water_open"], "kind": 1, "name": "Synthetic Lake"},
    ]
    barriers = [
        {"line": LineString([(ORIGIN_E + 360.0, ORIGIN_N + 180.0), (ORIGIN_E + 700.0, ORIGIN_N + 180.0)]),
         "kind": 0, "name": None, "height": 2.4},
        {"line": LineString([(ORIGIN_E + 700.0, ORIGIN_N + 180.0), (ORIGIN_E + 700.0, ORIGIN_N + 420.0)]),
         "kind": 2, "name": None, "height": 1.8},
    ]
    pois = [
        {"east": ORIGIN_E + 150.0, "north": ORIGIN_N + 150.0, "kind": gw.SPAWN_CAR, "class": 1,
         "name": "Synthetic Parking"},
        {"east": ORIGIN_E + 640.0, "north": ORIGIN_N + 620.0, "kind": gw.SPAWN_HELI, "class": 3,
         "name": None},
        {"east": ORIGIN_E + 1150.0, "north": ORIGIN_N + 900.0, "kind": gw.SPAWN_BOAT, "class": 5,
         "name": "Synthetic Port"},
        {"east": ORIGIN_E + 50.0, "north": ORIGIN_N + 1450.0, "kind": gw.SPAWN_PLANE, "class": 4,
         "name": "16L", "angle": 900},
        {"east": ORIGIN_E + 300.0, "north": ORIGIN_N + 320.0, "kind": 4, "class": 6,
         "name": "Synthetic Metro"},
    ]

    strings = bw.StringTable()
    roads_payload, road_counts = bw.pack_roads(roads, strings)
    for road in roads:
        strings.add(road["name_en"])
        strings.add(road["name_ar"])
    buildings_payload, building_stats = gw.pack_buildings(buildings, strings)
    landuse_payload, landuse_stats = gw.pack_areas(landuse, strings, b"QLU1")
    water_payload, water_stats = gw.pack_areas(water, strings, b"QWA1")
    barriers_payload, barrier_stats = gw.pack_barriers(barriers, strings)
    pois_payload, poi_stats = gw.pack_pois(pois, strings)
    strings_payload = strings.to_bytes()

    bundles = {
        "terrain_l0.bin.gz": terrain_payload,
        "terrain_l1.bin.gz": terrain_payload,
        "material_l1.bin.gz": material_payload,
        "roads.bin.gz": roads_payload,
        "buildings.bin.gz": buildings_payload,
        "landuse.bin.gz": landuse_payload,
        "water.bin.gz": water_payload,
        "barriers.bin.gz": barriers_payload,
        "pois.bin.gz": pois_payload,
        "strings.bin.gz": strings_payload,
    }
    FIXTURE.mkdir(parents=True, exist_ok=True)
    digests = {}
    for name, payload in bundles.items():
        path = FIXTURE / name
        path.write_bytes(gw.gz(payload))
        digests[name] = hashlib.sha256(path.read_bytes()).hexdigest()

    manifest = {
        "format": gw.GAME_FORMAT,
        "worldEpoch": 20261013,
        "crs": "EPSG:32639",
        "crsName": "WGS 84 / UTM zone 39N",
        "originEast": ORIGIN_E, "originNorth": ORIGIN_N,
        "widthM": COLS * CELL_M, "heightM": ROWS * CELL_M,
        "levels": [{"name": "l1", "cellM": CELL_M, "cols": COLS, "rows": ROWS,
                    "file": "terrain_l1.bin.gz", "sha256": digests["terrain_l1.bin.gz"]}],
        "terrain_l0": {"file": "terrain_l0.bin.gz", "cellM": CELL_M, "cols": COLS, "rows": ROWS,
                       "sha256": digests["terrain_l0.bin.gz"]},
        "terrain_l1": {"file": "terrain_l1.bin.gz", "cellM": CELL_M, "cols": COLS, "rows": ROWS,
                       "sha256": digests["terrain_l1.bin.gz"]},
        "materials": {"file": "material_l1.bin.gz", "cellM": CELL_M, "cols": COLS, "rows": ROWS,
                      "sha256": digests["material_l1.bin.gz"]},
        "roads": {"file": "roads.bin.gz", "count": sum(road_counts.values()),
                  "sha256": digests["roads.bin.gz"]},
        "strings": {"file": "strings.bin.gz", "sha256": digests["strings.bin.gz"]},
        "gameBundles": {
            "buildings": {"file": "buildings.bin.gz", "records": building_stats["records"],
                          "sha256": digests["buildings.bin.gz"]},
            "landuse": {"file": "landuse.bin.gz", "records": landuse_stats["records"],
                        "sha256": digests["landuse.bin.gz"]},
            "water": {"file": "water.bin.gz", "records": water_stats["records"],
                      "sha256": digests["water.bin.gz"]},
            "barriers": {"file": "barriers.bin.gz", "records": barrier_stats["records"],
                         "sha256": digests["barriers.bin.gz"]},
            "pois": {"file": "pois.bin.gz", "records": poi_stats["records"],
                     "sha256": digests["pois.bin.gz"]},
        },
        "municipalities": [{"id": "syn-1", "name": "Synthetic Municipality", "nameAr": "بل",
                            "east": ORIGIN_E + 800.0, "north": ORIGIN_N + 800.0, "areaKm2": 2.56}],
        "islands": [],
        "attribution": "© OpenStreetMap contributors, ODbL 1.0 | SRTM elevation (public domain)",
        "stats": {"buildings": building_stats["records"], "landusePolygons": landuse_stats["records"],
                  "waterPolygons": water_stats["records"], "barriers": barrier_stats["records"],
                  "pois": poi_stats["records"], "roads": sum(road_counts.values()),
                  "spawnPoints": {"car": 1, "helicopter": 1, "boat": 1, "aeroplane": 1}},
    }
    (FIXTURE / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    return {"manifest": manifest, "expected": {
        "buildings": building_stats["records"], "landuse": landuse_stats["records"],
        "water": water_stats["records"], "barriers": barrier_stats["records"],
        "pois": poi_stats["records"], "roads": sum(road_counts.values()),
        "mosque_ring_points": len(mosque.exterior.coords),
        "l_shape_ring_points": len(l_shape.exterior.coords),
    }}


# ----------------------------------------------------------------------------------------------
# Contract checks: decode what was just written and compare with the source geometry
# ----------------------------------------------------------------------------------------------

def read_bundle(path: Path) -> bytes:
    return gzip.decompress(path.read_bytes())


def decode_points(payload: bytes, offset: int, count: int) -> list[tuple[float, float]]:
    east_cm, north_cm = struct.unpack_from("<ii", payload, offset)
    points = [(east_cm / 100.0, north_cm / 100.0)]
    cursor = offset + 8
    for _ in range(count - 1):
        de, dn = struct.unpack_from("<ii", payload, cursor)
        cursor += 8
        points.append((points[-1][0] + de / 100.0, points[-1][1] + dn / 100.0))
    return points


def check_buildings() -> None:
    payload = read_bundle(FIXTURE / "buildings.bin.gz")
    magic, version, count, length = struct.unpack_from("<4sIIQ", payload, 0)
    check(magic == b"QBG1", "buildings magic is QBG1")
    check(version == 1, "buildings version is 1")
    check(length == len(payload) - 20, "buildings payload length matches the header")
    check(count >= 4, f"buildings record count is plausible ({count})")

    cursor = 20
    rings: list[tuple[float, float, int, int, int]] = []
    for _ in range(count):
        east_cm, north_cm, points, height_dm, min_dm, levels, btype, roof, flags, name_idx = \
            struct.unpack_from("<iiHHHBBBBH", payload, cursor)
        cursor += 20 + (points - 1) * 8
        rings.append((east_cm / 100.0, north_cm / 100.0, points, height_dm, btype))
    check(cursor == len(payload), "building records consume the payload exactly")

    # The dome mosque: a 40 x 30 m rectangle -> closed ring of 5 points, 9.5 m tall, mosque type.
    mosque = [r for r in rings if r[2] == 5 and abs(r[3] - 95) < 1e-6]
    check(len(mosque) == 1, "found the mosque ring (5 points, 9.5 m)")
    if mosque:
        check(abs(mosque[0][0] - (ORIGIN_E + 240.0)) < 0.01, "mosque ring starts on its west edge")
        check(mosque[0][4] == bw.BUILDING_TYPE["mosque"], "mosque type id round-trips")

    tower = [r for r in rings if abs(r[3] - 1200) < 1e-6]
    check(len(tower) == 1, "found the 120 m tower")
    check(max(r[3] for r in rings) == 1200, "height field carries 120.0 m")


def check_roads() -> None:
    payload = read_bundle(FIXTURE / "roads.bin.gz")
    magic, version, count, length = struct.unpack_from("<4sIIQ", payload, 0)
    check(magic == b"QTR1", "roads magic is QTR1")
    check(length == len(payload) - 20, "roads payload length matches the header")
    check(count == 3, f"three roads are packed ({count})")

    cursor = 20
    ends: list[tuple[float, float]] = []
    for _ in range(count):
        road_class, flags, lanes, speed5, width_dm, name_idx, name_ar_idx, points = \
            struct.unpack_from("<BBBBHHHH", payload, cursor)
        cursor += 12
        decoded = decode_points(payload, cursor, points)
        cursor += points * 8
        ends.append(decoded[-1])
        if road_class == bw.ROAD_CLASS["primary"]:
            check(flags & 4 == 0, "primary road is not one-way")
            check(abs(decoded[0][0] - (ORIGIN_E + 5.0)) < 0.01, "primary road starts where it should")
            check(abs(decoded[0][1] - (ORIGIN_N + 850.0)) < 0.01, "primary road is on the cell centre line")
    check(cursor == len(payload), "road records consume the payload exactly")
    check(any(abs(point[0] - (ORIGIN_E + 1500.0)) < 0.01 for point in ends), "road endpoint round-trips")


def check_areas(name: str, magic_expected: bytes, expected_records: int) -> None:
    payload = read_bundle(FIXTURE / name)
    magic, version, count, length = struct.unpack_from("<4sIIQ", payload, 0)
    check(magic == magic_expected, f"{name} magic is {magic_expected.decode()}")
    check(length == len(payload) - 20, f"{name} payload length matches the header")
    check(count == expected_records, f"{name} contains the expected {expected_records} polygons")
    cursor = 20
    for _ in range(count):
        _, _, points, _, _, _, _, _ = struct.unpack_from("<iiHBBBBH", payload, cursor)
        cursor += 16 + (points - 1) * 8
    check(cursor == len(payload), f"{name} records consume the payload exactly")


def check_barriers() -> None:
    payload = read_bundle(FIXTURE / "barriers.bin.gz")
    magic, version, count, length = struct.unpack_from("<4sIIQ", payload, 0)
    check(magic == b"QBA1", "barriers magic is QBA1")
    check(count == 2, f"two barriers are packed ({count})")
    _, _, points, kind, _, height_dm, _ = struct.unpack_from("<iiHBBHH", payload, 20)
    check(points == 2, "first barrier is a two point line")
    check(kind == 0, "first barrier is a wall")
    check(abs(height_dm / 10.0 - 2.4) < 1e-6, "barrier height round-trips (2.4 m)")


def check_pois() -> None:
    payload = read_bundle(FIXTURE / "pois.bin.gz")
    magic, version, count, length = struct.unpack_from("<4sIIQ", payload, 0)
    check(magic == b"QPO1", "points magic is QPO1")
    check(count == 5, f"five points are packed ({count})")
    check(length == count * 16, "point records are 16 bytes each")
    kinds = {}
    for index in range(count):
        east_cm, north_cm, kind, poi_class, flags, _pad, name_idx, angle = \
            struct.unpack_from("<iiBBBBHH", payload, 20 + index * 16)
        kinds[kind] = kinds.get(kind, 0) + 1
    check(kinds.get(gw.SPAWN_CAR) == 1 and kinds.get(gw.SPAWN_PLANE) == 1,
          "car and aeroplane spawns are present")


def check_terrain() -> None:
    payload = read_bundle(FIXTURE / "terrain_l1.bin.gz")
    magic, cols, rows, cell, _pad = struct.unpack_from("<4sIIfI", payload, 0)
    check(magic == b"QTT1", "terrain magic is QTT1")
    check((cols, rows) == (COLS, ROWS), f"terrain grid is {COLS}x{ROWS}")
    check(abs(cell - CELL_M) < 1e-6, "terrain cell size is 100 m")
    check(len(payload) == 20 + cols * rows * 3, "terrain payload is header + int16 heights + byte mask")
    heights = np.frombuffer(payload, dtype="<i2", count=cols * rows, offset=20)
    mask = np.frombuffer(payload, dtype="u1", count=cols * rows, offset=20 + cols * rows * 2)
    check(int(heights.max()) == 20, f"terrain heights reach the ridge top ({int(heights.max())} m)")
    check(int(mask[13]) == 0 and int(mask[10]) == 1, "land mask puts water in the east, land in the west")
    check(int(heights[13 * cols + 15]) == 0, "water cells are at height zero")

    material = read_bundle(FIXTURE / "material_l1.bin.gz")
    magic_m, cols_m, rows_m, cell_m, _ = struct.unpack_from("<4sIIfI", material, 0)
    check(magic_m == b"QTM1" and (cols_m, rows_m) == (COLS, ROWS), "material grid matches the terrain")
    ids = np.frombuffer(material, dtype="u1", count=cols_m * rows_m, offset=20)
    check(int(ids[0]) == bw.MATERIAL["sand_compact"], "western cell is compact sand")
    check(int(ids[13 * cols_m + 15]) == bw.MATERIAL["water_open"], "eastern cell is open water")


def check_strings() -> None:
    payload = read_bundle(FIXTURE / "strings.bin.gz")
    magic, version, count = struct.unpack_from("<4sII", payload, 0)
    check(magic == b"QTS1", "strings magic is QTS1")
    check(count > 0, f"string table is populated ({count} entries)")
    cursor = 12
    values = []
    for _ in range(count):
        length = struct.unpack_from("<H", payload, cursor)[0]
        cursor += 2
        values.append(payload[cursor:cursor + length].decode("utf-8"))
        cursor += length
    check(cursor == len(payload), "string records consume the payload exactly")
    check("Corniche Street" in values, "English road name is in the table")
    check("شارع الكورنيش" in values, "Arabic road name is in the table")


def check_manifest() -> None:
    manifest = json.loads((FIXTURE / "manifest.json").read_text())
    check(manifest["format"] == gw.GAME_FORMAT, "manifest format is the game format")
    check(manifest["crs"] == "EPSG:32639", "manifest declares UTM 39N")
    for entry in list(manifest["gameBundles"].values()) + [manifest["roads"], manifest["strings"],
                                                           manifest["materials"],
                                                           manifest["terrain_l1"]]:
        path = FIXTURE / entry["file"]
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        check(digest == entry["sha256"], f"{entry['file']} digest matches the manifest")
    check("OpenStreetMap" in manifest["attribution"], "attribution is present")


def check_reproducible(first: dict) -> None:
    """The fixture must be byte-identical when rebuilt: no timestamps, no hash ordering."""
    digests_before = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in FIXTURE.glob("*")}
    build_fixture()
    digests_after = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in FIXTURE.glob("*")}
    check(digests_before == digests_after, "rebuilding the fixture produces byte-identical files")
    check(first["expected"]["buildings"] == sum(1 for _ in range(first["expected"]["buildings"])),
          "fixture expectations are self-consistent")


def check_no_undefined_names() -> None:
    """Guard the tooling the way the stage 1 self-test does: a NameError must never reach CI late."""
    try:
        from pyflakes import api as pyflakes_api
        from pyflakes import reporter as pyflakes_reporter
    except ImportError:
        print("SKIP  pyflakes not installed, undefined-name gate skipped")
        return

    class Collector:
        def __init__(self) -> None:
            self.messages: list[str] = []

        def write(self, message: str) -> None:
            self.messages.append(message)

        def flush(self) -> None:
            pass

    collector = Collector()
    reporter = pyflakes_reporter.Reporter(collector, collector)
    for path in sorted((REPO / "tools").glob("*.py")):
        pyflakes_api.checkPath(str(path), reporter)
    problems = [m for m in collector.messages if "undefined name" in m]
    check(not problems, f"no undefined names in tools/ ({len(problems)} found)")
    for problem in problems:
        print("   ", problem.strip())


def main() -> int:
    print("== synthetic world fixture ==")
    result = build_fixture()
    print(f"wrote {FIXTURE} ({len(list(FIXTURE.glob('*')))} files)")
    print("== bundle contracts ==")
    check_terrain()
    check_roads()
    check_buildings()
    check_areas("landuse.bin.gz", b"QLU1", result["expected"]["landuse"])
    check_areas("water.bin.gz", b"QWA1", result["expected"]["water"])
    check_barriers()
    check_pois()
    check_strings()
    check_manifest()
    check_reproducible(result)
    check_no_undefined_names()
    print()
    if FAILURES:
        print(f"GAME WORLD CONTRACTS FAILED ({len(FAILURES)} checks)")
        return 1
    print("GAME WORLD CONTRACTS PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
