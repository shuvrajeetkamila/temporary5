#!/usr/bin/env python3
"""Self-tests for the world compiler that need no OSM extract.

The compiler is expensive to run (it needs a 24.5 MB extract and several minutes of CI time), so the
geometry derivation and every bundle writer are exercised here against synthetic inputs first. Every
test asserts a documented contract of the runtime format, so a regression fails in seconds instead of
after a five minute CI round trip.

Run: python3 tools/selftest_world.py
"""
from __future__ import annotations

import ast
import builtins
import json
import struct
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import build_world as bw  # noqa: E402

FAILURES: list[str] = []


def check(condition: bool, label: str) -> None:
    print(f"{'PASS' if condition else 'FAIL'}  {label}")
    if not condition:
        FAILURES.append(label)


def test_unresolved_names() -> None:
    """Static gate: a helper deleted by a refactor must not reach CI."""
    tree = ast.parse(Path(bw.__file__).read_text())
    defined = {n.name for n in ast.walk(tree) if isinstance(n, (ast.FunctionDef, ast.ClassDef))}
    imported = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported |= {(a.asname or a.name.split(".")[0]) for a in node.names}
        elif isinstance(node, ast.ImportFrom):
            imported |= {a.asname or a.name for a in node.names}
    called = {n.func.id for n in ast.walk(tree) if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)}
    missing = sorted(c for c in called
                     if c not in defined and c not in imported and not hasattr(builtins, c))
    check(not missing, f"no unresolved function names in the compiler (found: {missing})")


def test_projection_round_trip() -> None:
    lon, lat = 51.5310, 25.2854  # Doha
    east, north = bw.FWD.transform(lon, lat)
    back_lon, back_lat = bw.INV.transform(east, north)
    check(abs(back_lon - lon) < 1e-6 and abs(back_lat - lat) < 1e-6,
          "EPSG:32639 round trip for Doha")
    check(abs(east - 553459.1074) < 0.01 and abs(north - 2796656.1019) < 0.01,
          "Doha projects to the documented grid reference")


def test_land_derivation_synthetic() -> None:
    """A square island with a coastline ring and seeds inside must become land; outside must not."""
    centre_lon, centre_lat = 51.53, 25.29
    half = 0.02  # ~2.2 km
    ring = []
    steps = 40
    import math
    for i in range(steps + 1):
        angle = 2 * math.pi * i / steps
        ring.append((centre_lon + half * math.cos(angle), centre_lat + half * math.sin(angle)))
    cell = 100.0
    cols = rows = 40  # 4 km square centred on the synthetic island
    centre_east0, centre_north0 = bw.FWD.transform(centre_lon, centre_lat)
    east0 = centre_east0 - cols * cell / 2.0
    north0 = centre_north0 - rows * cell / 2.0
    xs = east0 + (np.arange(cols) + 0.5) * cell
    ys = north0 + (np.arange(rows) + 0.5) * cell
    centre_east, centre_north = centre_east0, centre_north0
    seeds = [(centre_lon, centre_lat), (centre_lon + 0.005, centre_lat - 0.005)]
    area_km2 = math.pi * (half * 111_000) ** 2 / 1e6
    land, diag = bw.derive_land_mask([ring], [], seeds, xs, ys, cell,
                                     min_area_km2=area_km2 * 0.3, max_area_km2=area_km2 * 3.0)
    derived_km2 = float(land.sum()) * cell * cell / 1e6
    check(diag["barrierCells"] > 0, f"coastline ribbon rasterises into a barrier ({diag})")
    check(diag["seedCells"] >= 1, f"land seeds land inside the ring ({diag})")
    check(0.3 * area_km2 <= derived_km2 <= 1.6 * area_km2,
          f"derived land area {derived_km2:.2f} km2 is within the ring ({area_km2:.2f} km2 analytic)")
    inside_row = int((centre_north - north0) / cell)
    inside_col = int((centre_east - east0) / cell)
    check(bool(land[inside_row, inside_col]), "the ring centre is land")
    check(not bool(land[0, 0]), "the far corner (outside the ring) is not land")


def test_terrain_bundle_contract() -> None:
    """QTT1: magic, cols, rows, cell, reserved int, int16 heights, then the land mask."""
    heights = np.array([[0, 5, 10], [20, 30, 40]], dtype="<i2")
    mask = np.array([[1, 1, 0], [1, 0, 0]], dtype="u1")
    grid = {"originEast": 431000.0, "originNorth": 2681000.0, "widthM": 300.0, "heightM": 200.0}
    payload = bw.pack_terrain(grid, 100.0, heights, mask)
    magic, cols, rows, cell, reserved = struct.unpack_from("<4sIIfI", payload, 0)
    check(magic == b"QTT1", "terrain bundle magic is QTT1")
    check((cols, rows) == (3, 2) and abs(cell - 100.0) < 1e-6 and reserved == 0,
          "terrain header carries cols/rows/cell/little-endian reserved")
    check(len(payload) == 20 + 3 * 2 * 2 + 3 * 2, "terrain payload length matches the documented layout")
    restored = np.frombuffer(payload[20:20 + 12], dtype="<i2").reshape(2, 3)
    check(np.array_equal(restored, heights), "terrain heights are int16 little-endian, row 0 first")
    check(payload[20 + 12:] == mask.tobytes(), "land mask follows the heights as one byte per cell")


def test_string_table_contract() -> None:
    table = bw.StringTable()
    idx_en = table.add("Doha")
    idx_ar = table.add("\u0627\u0644\u062f\u0648\u062d\u0629")  # الدوحة
    data = table.to_bytes()
    check(data[:4] == b"QTS1", "string table magic is QTS1")
    count = struct.unpack_from("<I", data, 8)[0]
    check(count == 2, "string table counts entries")
    length = struct.unpack_from("<H", data, 12)[0]
    value = data[14:14 + length].decode("utf-8")
    check(value == "Doha", "entries are length-prefixed UTF-8 in insertion order")
    check(idx_en == 0 and idx_ar == 1, "string indices are stable and start at 0")


def test_checkpoint_table() -> None:
    groups = {c[0] for c in bw.CHECKPOINTS}
    expected = {"SOUTH_AND_BORDER", "CENTRAL_AND_CAPITAL", "NORTH_AND_NORTHEAST",
                "WEST_AND_NORTHWEST", "ISLANDS_AND_WATER"}
    check(expected <= groups, f"all five mandated checkpoint groups are declared ({sorted(groups)})")
    check(len(bw.CHECKPOINTS) >= 30, f"checkpoint table covers the country ({len(bw.CHECKPOINTS)} entries)")


def test_road_class_table() -> None:
    check(len(bw.ROAD_CLASS) >= 15, f"road class table is rich enough ({len(bw.ROAD_CLASS)} classes)")
    check("residential" in bw.ROAD_CLASS and "motorway" in bw.ROAD_CLASS,
          "road classes include the urban and motorway ends of the range")
    check(all(isinstance(v, int) for v in bw.ROAD_CLASS.values()),
          "road classes map to integer material/geometry ids")


def main() -> int:
    test_unresolved_names()
    test_projection_round_trip()
    test_land_derivation_synthetic()
    test_terrain_bundle_contract()
    test_string_table_contract()
    test_checkpoint_table()
    test_road_class_table()
    print()
    if FAILURES:
        print(f"WORLD SELF-TEST FAILED ({len(FAILURES)} problem(s)):")
        for failure in FAILURES:
            print(f"  - {failure}")
        return 1
    print("WORLD SELF-TEST PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
