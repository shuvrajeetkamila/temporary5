#!/usr/bin/env python3
"""Validate the compiled game world before it is allowed into an APK.

Stage 1's validator proves the terrain and the land mask describe the real country. This one proves
the *game* bundles (buildings, land use, water, barriers, points) are complete, self-consistent and
inside the grid, because a bundle that parses is not the same as a bundle that is right.

Checks are deliberately about the things that would be ship-breaking:
  * manifest format, CRS, digests of every file it names (a digest that does not match is fatal),
  * record counts consistent with the manifest and with the bundle headers,
  * every coordinate inside the national grid (a coordinate outside it would place geometry in the sea
    or in Saudi Arabia),
  * building sizes and heights in a plausible range, and footprints big enough to be buildings,
  * water in range, barriers with a height, spawn points of every vehicle type present.

Usage: python3 tools/validate_game_world.py [--world assets/world] [--report validation/GAME_WORLD.txt]
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import struct
from pathlib import Path

FAILURES: list[str] = []
CHECKS = 0


def check(condition: bool, message: str) -> None:
    global CHECKS
    CHECKS += 1
    if condition:
        print(f"PASS  {message}")
    else:
        print(f"FAIL  {message}")
        FAILURES.append(message)


def load(world: Path, name: str) -> bytes:
    path = world / name
    if not path.exists():
        check(False, f"{name} exists")
        return b""
    return gzip.decompress(path.read_bytes())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", default="assets/world")
    parser.add_argument("--report", default=None)
    parser.add_argument("--synthetic", action="store_true",
                        help="relax country-scale minimums so the 1.6 km test fixture can be validated")
    args = parser.parse_args()
    world = Path(args.world)

    manifest_path = world / "manifest.json"
    if not manifest_path.exists():
        print(f"missing {manifest_path}")
        return 2
    manifest = json.loads(manifest_path.read_text())

    check(manifest.get("format") == 2, f"manifest is game format 2 (found {manifest.get('format')})")
    check(manifest.get("crs") == "EPSG:32639", "manifest declares the UTM 39N master CRS")
    check(bool(manifest.get("attribution")), "manifest carries the OSM attribution")
    origin_e = float(manifest["originEast"])
    origin_n = float(manifest["originNorth"])
    max_e = origin_e + float(manifest["widthM"])
    max_n = origin_n + float(manifest["heightM"])
    check(float(manifest["widthM"]) > (1_000 if args.synthetic else 100_000),
          "grid covers the country (wider than 100 km)")
    # Country-scale floors: a real Qatar compile must clear these by a wide margin, while the synthetic
    # fixture (used by the JVM unit tests) is deliberately tiny.
    # Country-scale floors. Land use is plentiful in Qatar; inland water is sparse (a desert country
    # has few lakes), so its floor is deliberately low -- the check is "is there any at all", not a
    # quota that could force the compiler to invent polygons.
    min_buildings = 4 if args.synthetic else 10_000
    min_polygons = {"landuse": 1 if args.synthetic else 500, "water": 1 if args.synthetic else 5}

    # --- every file the manifest names must exist and match its digest -----------------------
    named: list[tuple[str, dict]] = []
    for key in ("terrain_l0", "terrain_l1", "materials", "roads", "strings"):
        if isinstance(manifest.get(key), dict) and manifest[key].get("file"):
            named.append((key, manifest[key]))
    for key, entry in (manifest.get("gameBundles") or {}).items():
        named.append((key, entry))
    for key, entry in named:
        path = world / entry["file"]
        if not path.exists():
            check(False, f"{key}: {entry['file']} exists")
            continue
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        check(digest == entry.get("sha256"), f"{key}: {entry['file']} matches its manifest digest")

    # --- buildings ---------------------------------------------------------------------------
    payload = load(world, "buildings.bin.gz")
    if payload:
        magic, version, count, length = struct.unpack_from("<4sIIQ", payload, 0)
        check(magic == b"QBG1", "buildings bundle has the QBG1 magic")
        check(version == 1, "buildings bundle version is 1")
        check(length == len(payload) - 20, "buildings payload length matches its header")
        expected = manifest["gameBundles"]["buildings"]["records"]
        check(count == expected, f"buildings count matches the manifest ({count})")
        check(count > min_buildings, f"the country has a real building stock ({count:,} footprints)")

        cursor = 20
        min_points = 1 << 30
        max_points = 0
        min_height = 1e9
        max_height = -1e9
        outside = 0
        tiny = 0
        for _ in range(count):
            east_cm, north_cm, points, height_dm, _min_dm, _levels, _type, _roof, _flags, _name = \
                struct.unpack_from("<iiHHHBBBBH", payload, cursor)
            if not (origin_e <= east_cm / 100.0 <= max_e and origin_n <= north_cm / 100.0 <= max_n):
                outside += 1
            min_points = min(min_points, points)
            max_points = max(max_points, points)
            if height_dm:
                min_height = min(min_height, height_dm / 10.0)
                max_height = max(max_height, height_dm / 10.0)
            if points < 4:
                tiny += 1
            cursor += 20 + (points - 1) * 8
        check(cursor == len(payload), "building records consume the payload exactly")
        check(outside == 0, f"every building lies inside the national grid ({outside} outside)")
        check(tiny == 0, f"every footprint is a closed ring of at least 4 points ({tiny} too small)")
        check(min_points >= 4 and max_points <= 2000, f"footprint vertex counts are sane (min {min_points}, max {max_points})")
        check(1.5 <= min_height, f"no building is shorter than 1.5 m ({min_height:.1f} m)")
        check(max_height <= 700.0, f"no building is absurdly tall ({max_height:.1f} m)")
        check(max_height > 50.0, f"towers are present, so heights are real ({max_height:.1f} m)")

    # --- land use and water ------------------------------------------------------------------
    for key, magic_expected in (("landuse", b"QLU1"), ("water", b"QWA1")):
        entry = (manifest.get("gameBundles") or {}).get(key)
        if not entry:
            continue
        payload = load(world, entry["file"])
        if not payload:
            continue
        magic, version, count, length = struct.unpack_from("<4sIIQ", payload, 0)
        check(magic == magic_expected, f"{key} bundle has the {magic_expected.decode()} magic")
        check(length == len(payload) - 20, f"{key} payload length matches its header")
        check(count == entry["records"], f"{key} count matches the manifest ({count})")
        check(count >= min_polygons[key], f"{key} covers the country with real polygons ({count:,})")
        cursor = 20
        outside = 0
        for _ in range(count):
            east_cm, north_cm, points = struct.unpack_from("<iiH", payload, cursor)
            if not (origin_e <= east_cm / 100.0 <= max_e and origin_n <= north_cm / 100.0 <= max_n):
                outside += 1
            cursor += 16 + (points - 1) * 8
        check(cursor == len(payload), f"{key} records consume the payload exactly")
        check(outside == 0, f"{key} geometry is inside the grid ({outside} outside)")

    # --- barriers and points ------------------------------------------------------------------
    payload = load(world, "barriers.bin.gz")
    if payload:
        magic, version, count, length = struct.unpack_from("<4sIIQ", payload, 0)
        check(magic == b"QBA1", "barriers bundle has the QBA1 magic")
        check(length == len(payload) - 20, "barriers payload length matches its header")
        cursor = 20
        zero_height = 0
        for _ in range(count):
            _e, _n, points, _kind, _flags, height_dm, _name = struct.unpack_from("<iiHBBHH", payload, cursor)
            if height_dm == 0:
                zero_height += 1
            cursor += 16 + (points - 1) * 8
        check(cursor == len(payload), "barrier records consume the payload exactly")
        check(zero_height == 0, f"every barrier has a height ({zero_height} without)")

    payload = load(world, "pois.bin.gz")
    if payload:
        magic, version, count, length = struct.unpack_from("<4sIIQ", payload, 0)
        check(magic == b"QPO1", "points bundle has the QPO1 magic")
        check(length == count * 16, "point records are 16 bytes each")
        kinds: dict[int, int] = {}
        outside = 0
        for index in range(count):
            east_cm, north_cm, kind, _class, _flags, _pad, _name, _angle = \
                struct.unpack_from("<iiBBBBHH", payload, 20 + index * 16)
            kinds[kind] = kinds.get(kind, 0) + 1
            if not (origin_e <= east_cm / 100.0 <= max_e and origin_n <= north_cm / 100.0 <= max_n):
                outside += 1
        check(outside == 0, f"every point of interest is inside the grid ({outside} outside)")
        for kind, label in ((0, "car"), (1, "helicopter"), (2, "boat"), (3, "aeroplane")):
            check(kinds.get(kind, 0) > 0, f"{label} spawn points exist ({kinds.get(kind, 0)})")
        spawns = manifest.get("stats", {}).get("spawnPoints", {})
        check(spawns.get("car", 0) > 0 and spawns.get("aeroplane", 0) > 0,
              f"manifest reports spawn points ({spawns})")

    print()
    print(f"{CHECKS - len(FAILURES)}/{CHECKS} checks passed")
    if args.report:
        report = Path(args.report)
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text("\n".join([
            f"checks={CHECKS}",
            f"failures={len(FAILURES)}",
            f"status={'PASSED' if not FAILURES else 'FAILED'}",
        ]) + "\n")
    if FAILURES:
        print(f"GAME WORLD VALIDATION FAILED ({len(FAILURES)} checks)")
        return 1
    print("GAME WORLD VALIDATION PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
