#!/usr/bin/env python3
"""Compile the game world for QATAR: THE PENINSULA from OpenStreetMap + SRTM.

Stage 2 of the world build. Stage 1 (`tools/build_world.py`) compiles the terrain, the land mask, the
surface materials, the road network and the string table, and is proven end to end on the real
country. This stage re-reads the same OpenStreetMap extract and adds the geometry a 3D game needs:

  * QBG1 buildings  -- real footprint rings (not bounding boxes), roof height, minimum height,
                       storey count, type and name, so every building in the country becomes a
                       textured 3D volume with the shape OSM actually records.
  * QLU1 land use   -- surface polygons (sand, rock, scrub, farmland, urban, ...) so the ground is
                       not one generic desert: they drive the terrain shader and vegetation.
  * QWA1 water      -- inland water, wadis and coastal water polygons, carved into the terrain mesh
                       and rendered with a water surface.
  * QBA1 barriers   -- walls, fences, hedges and retaining walls as lines with a height.
  * QPO1 points     -- spawn points and landmarks (parking, helipads, ports, runways, metro) that the
                       player, vehicles and the map use directly.

Nothing is invented: every vertex comes from OpenStreetMap (ODbL) reprojected once into EPSG:32639,
and every bundle carries a SHA-256 digest in the manifest.

Usage:
    python3 tools/game_world.py --osm data/raw/qatar-latest.osm.pbf --world assets/world
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import struct
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from shapely.geometry import LineString, Polygon  # noqa: E402

import build_world as bw  # noqa: E402  (proven stage 1: projection, extraction, packing)

GAME_FORMAT = 2
CM = 100.0  # centimetres per metre: bundle coordinates are int32 centimetres


NONE_INDEX = 0xFFFF  # the string table's "no name" sentinel, shared with stage 1


def load_string_table(path: Path) -> "bw.StringTable":
    """Re-open the stage 1 string table so new names append without shifting existing indices."""
    table = bw.StringTable()
    if not path.exists():
        return table
    raw = gzip.decompress(path.read_bytes())
    if len(raw) < 12 or raw[:4] != b"QTS1":
        raise SystemExit(f"unexpected string table header in {path}")
    count = struct.unpack_from("<I", raw, 8)[0]
    offset = 12
    for _ in range(count):
        length = struct.unpack_from("<H", raw, offset)[0]
        offset += 2
        table.add(raw[offset:offset + length].decode("utf-8"))
        offset += length
    return table


def add_name(table: "bw.StringTable", value: str | None) -> int:
    index = table.add(value) if value else NONE_INDEX
    return NONE_INDEX if index is None else index


# ---------------------------------------------------------------------------------------------
# Bundle primitives (little-endian, gzip level 6 with mtime 0 so builds are byte-reproducible)
# ---------------------------------------------------------------------------------------------

def gz(payload: bytes) -> bytes:
    buffer = __import__("io").BytesIO()
    with gzip.GzipFile(fileobj=buffer, mode="wb", compresslevel=6, mtime=0) as fh:
        fh.write(payload)
    return buffer.getvalue()


def write_bundle(path: Path, payload: bytes) -> str:
    path.write_bytes(gz(payload))
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    return digest


def delta_points(points: list[tuple[float, float]], min_points: int = 2) -> bytes | None:
    """Absolute first vertex then int32 centimetre deltas, the convention every ring/line uses."""
    pts = [(e, n) for (e, n) in points if math.isfinite(e) and math.isfinite(n)]
    if len(pts) < min_points:
        return None
    out = bytearray()
    first_e = int(round(pts[0][0] * CM))
    first_n = int(round(pts[0][1] * CM))
    out += struct.pack("<ii", first_e, first_n)
    prev_e, prev_n = first_e, first_n
    for (e, n) in pts[1:]:
        ie = int(round(e * CM))
        inn = int(round(n * CM))
        out += struct.pack("<ii", ie - prev_e, inn - prev_n)
        prev_e, prev_n = ie, inn
    return bytes(out)


def ring_points(ring) -> list[tuple[float, float]] | None:
    """Closed ring in projected metres, deduplicated and sanity checked."""
    try:
        coords = [(float(x), float(y)) for x, y in ring.coords if math.isfinite(x) and math.isfinite(y)]
    except Exception:  # noqa: BLE001
        return None
    if len(coords) < 4:
        return None
    cleaned: list[tuple[float, float]] = []
    for point in coords:
        if not cleaned or abs(point[0] - cleaned[-1][0]) > 1e-9 or abs(point[1] - cleaned[-1][1]) > 1e-9:
            cleaned.append(point)
    if len(cleaned) < 4:
        return None
    return cleaned


# ---------------------------------------------------------------------------------------------
# Buildings: footprints, heights, types
# ---------------------------------------------------------------------------------------------

# Roof silhouettes the runtime builder understands. 'flat' is the overwhelming majority in Qatar;
# the others let the mesher give a landmark a recognisable roof without any proprietary asset.
ROOF_FLAT, ROOF_GABLE, ROOF_DOME, ROOF_PYRAMID, ROOF_SHED = 0, 1, 2, 3, 4

ROOF_SHAPE = {
    "flat": ROOF_FLAT, "gabled": ROOF_GABLE, "hipped": ROOF_PYRAMID, "pyramidal": ROOF_PYRAMID,
    "dome": ROOF_DOME, "domed": ROOF_DOME, "skillion": ROOF_SHED, "lean_to": ROOF_SHED,
    "shed": ROOF_SHED, "round": ROOF_DOME, "onion": ROOF_DOME,
}


def pack_buildings(buildings: list[dict], strings: "bw.StringTable") -> tuple[bytes, dict]:
    """QBG1: one record per footprint ring.

    Layout (all little-endian):
      magic   char[4] 'QBG1'
      version uint32  = 1
      count   uint32  number of records
      payload uint64  payload byte length
      then per record:
        east_cm   int32   absolute east of the first vertex, centimetres (20 byte header)
        north_cm  int32   absolute north of the first vertex, centimetres
        points    uint16  vertex count, including the repeated closing vertex
        height_dm uint16  roof height above the ground, decimetres (0 = unknown)
        min_dm    uint16  lowest occupied height above ground (passages, overhangs), 0 = on ground
        levels    uint8   storeys, 0 = unknown
        type      uint8   bw.BUILDING_TYPE id
        roof      uint8   ROOF_* id
        flags     uint8   bit0 multipolygon part, bit1 named, bit2 landmark
        name_idx  uint16  string table index, 0 = none
        deltas    int32[npoints-1] x2  centimetre deltas
    """
    payload = bytearray()
    count = 0
    named = 0
    roof_hist: dict[str, int] = {}
    for record in buildings:
        rings = record["rings"]
        height = record["height"]
        min_height = record["min_height"]
        for ring in rings:
            points = ring_points(ring)
            if points is None:
                continue
            if len(points) > 2000:  # a building of 2000 vertices is already unreasonable
                continue
            encoded = delta_points(points, min_points=4)
            if encoded is None:
                continue
            name_idx = add_name(strings, record.get("name"))
            if name_idx != NONE_INDEX:
                named += 1
            flags = 0
            if len(rings) > 1:
                flags |= 1
            if name_idx != NONE_INDEX:
                flags |= 2
            if record.get("landmark"):
                flags |= 4
            payload += struct.pack(
                "<iiHHHBBBBH", int(round(points[0][0] * CM)), int(round(points[0][1] * CM)),
                min(65535, len(points)), min(65535, int(round(height * 10.0))),
                min(65535, int(round(min_height * 10.0))), min(255, record["levels"]),
                min(255, record["type"]), min(255, record["roof"]), flags, min(65535, name_idx))
            payload += encoded[8:]  # the absolute first vertex is already in the record header
            count += 1
            key = str(record["roof"])
            roof_hist[key] = roof_hist.get(key, 0) + 1
    header = struct.pack("<4sIIQ", b"QBG1", 1, count, len(payload))
    stats = {"records": count, "named": named, "roofHistogram": roof_hist}
    return header + bytes(payload), stats


# ---------------------------------------------------------------------------------------------
# Land use, water and barriers
# ---------------------------------------------------------------------------------------------

# Land use polygons are classified into the same material ids the terrain shader already uses, so a
# land use polygon and a terrain cell are two views of one classification.
LANDUSE_CLASS = {
    "residential": "urban_pad", "commercial": "urban_pad", "retail": "urban_pad",
    "industrial": "urban_pad", "construction": "urban_pad", "quarry": "gravel_rock",
    "farmland": "farm_soil", "orchard": "farm_soil", "vineyard": "farm_soil",
    "greenhouse_horticulture": "farm_soil", "grass": "farm_soil", "meadow": "farm_soil",
    "sand": "sand_loose_dune", "desert": "sand_loose_dune", "dune": "sand_loose_dune",
    "bare_rock": "gravel_rock", "scree": "gravel_rock", "scrub": "gravel_rock",
    "heath": "gravel_rock", "shrubbery": "gravel_rock", "mangrove": "mangrove_marsh",
    "wetland": "mangrove_marsh", "salt_pond": "sabkha", "salt_marsh": "sabkha",
}
LANDUSE_KIND_SURFACE, LANDUSE_KIND_VEGETATION = 0, 1
VEGETATION_TAGS = {"scrub", "heath", "shrubbery", "grass", "grassland", "meadow", "orchard", "vineyard",
                   "tree_row", "wood", "forest", "mangrove"}


def classify_landuse(tags: dict) -> int | None:
    for key in ("landuse", "natural", "leisure", "amenity", "surface"):
        value = tags.get(key)
        if not value:
            continue
        value = value.split(";")[0].strip().lower()
        if value in bw.MATERIAL:
            return bw.MATERIAL[value]
        if value in LANDUSE_CLASS:
            return bw.MATERIAL[LANDUSE_CLASS[value]]
    return None


def pack_areas(records: list[dict], strings: "bw.StringTable", magic: bytes) -> tuple[bytes, dict]:
    """QLU1 / QWA1: area records (footprint kept at full resolution, simplified fallback).

    Record: east_cm int32, north_cm int32, points uint16, class uint8, kind uint8, flags uint8,
            pad uint8, name_idx uint16, deltas int32[npoints-1] x2
    """
    payload = bytearray()
    count = 0
    classes: dict[int, int] = {}
    for record in records:
        points = ring_points(record["ring"])
        if points is None:
            continue
        encoded = delta_points(points, min_points=4)
        if encoded is None:
            continue
        name_idx = add_name(strings, record.get("name"))
        payload += struct.pack(
            "<iiHBBBBH", int(round(points[0][0] * CM)), int(round(points[0][1] * CM)),
            min(65535, len(points)), min(255, record["class"]), min(255, record["kind"]),
            min(255, record.get("flags", 0)), 0, min(65535, name_idx))
        payload += encoded[8:]
        count += 1
        classes[record["class"]] = classes.get(record["class"], 0) + 1
    header = struct.pack("<4sIIQ", magic, 1, count, len(payload))
    return header + bytes(payload), {"records": count, "classHistogram": classes}


def pack_barriers(records: list[dict], strings: "bw.StringTable") -> tuple[bytes, dict]:
    """QBA1: line barriers (wall, fence, hedge, retaining wall, gate).

    Record: east_cm int32, north_cm int32, points uint16, kind uint8, flags uint8,
            height_dm uint16, name_idx uint16, deltas int32[npoints-1] x2
    """
    payload = bytearray()
    count = 0
    kinds: dict[int, int] = {}
    for record in records:
        points = [(float(x), float(y)) for x, y in record["line"].coords]
        encoded = delta_points(points, min_points=2)
        if encoded is None:
            continue
        name_idx = add_name(strings, record.get("name"))
        payload += struct.pack(
            "<iiHBBHH", int(round(points[0][0] * CM)), int(round(points[0][1] * CM)),
            min(65535, len(points)), min(255, record["kind"]), min(255, record.get("flags", 0)),
            min(65535, int(round(record.get("height", 1.2) * 10.0))), min(65535, name_idx))
        payload += encoded[8:]
        count += 1
        kinds[record["kind"]] = kinds.get(record["kind"], 0) + 1
    header = struct.pack("<4sIIQ", b"QBA1", 1, count, len(payload))
    return header + bytes(payload), {"records": count, "kindHistogram": kinds}


def pack_pois(points: list[dict], strings: "bw.StringTable") -> tuple[bytes, dict]:
    """QPO1: points of interest and spawn points, absolute coordinates for random access.

    Record: east_cm int32, north_cm int32, kind uint8, class uint8, flags uint8, pad uint8,
            name_idx uint16, angle_deg uint16 (facing, 0 = unused)
    """
    payload = bytearray()
    kinds: dict[int, int] = {}
    count = 0
    for poi in points:
        east, north = poi["east"], poi["north"]
        if not (math.isfinite(east) and math.isfinite(north)):
            continue
        name_idx = add_name(strings, poi.get("name"))
        payload += struct.pack(
            "<iiBBBBHH", int(round(east * CM)), int(round(north * CM)), min(255, poi["kind"]),
            min(255, poi["class"]), min(255, poi.get("flags", 0)), 0, name_idx,
            int(poi.get("angle", 0)) % 3600)
        kinds[poi["kind"]] = kinds.get(poi["kind"], 0) + 1
        count += 1
    header = struct.pack("<4sIIQ", b"QPO1", 1, count, len(payload))
    return header + bytes(payload), {"records": count, "kindHistogram": kinds}


# Stable class ids. These are written into the bundle, so they must never depend on hash() ordering.
POI_CLASS_IDS = {
    "parking": 1, "fuel": 2, "helipad": 3, "airport": 4, "port": 5, "metro": 6, "bus": 7,
    "hospital": 8, "school": 9, "mosque": 10, "mall": 11, "stadium": 12, "landmark": 13,
}

# Point of interest classification: the classes the game actually consumes.
POI_CLASSES = {
    "parking": ("parking", 0), "parking_entrance": ("parking", 0), "fuel": ("fuel", 0),
    "helipad": ("helipad", 0), "heliport": ("helipad", 0), "aerodrome": ("airport", 0),
    "apron": ("airport", 0), "terminal": ("airport", 0), "harbour": ("port", 0),
    "marina": ("port", 0), "pier": ("port", 0), "station": ("metro", 0), "halt": ("metro", 0),
    "tram_stop": ("metro", 0), "bus_station": ("bus", 0), "hospital": ("hospital", 0),
    "clinic": ("hospital", 0), "school": ("school", 0), "university": ("school", 0),
    "mosque": ("mosque", 0), "mall": ("mall", 0), "marketplace": ("mall", 0),
    "stadium": ("stadium", 0), "museum": ("landmark", 0), "attraction": ("landmark", 0),
    "viewpoint": ("landmark", 0), "tower": ("landmark", 0), "theme_park": ("landmark", 0),
}

# Spawn point kinds the runtime uses to place the player and each vehicle.
SPAWN_CAR, SPAWN_HELI, SPAWN_BOAT, SPAWN_PLANE = 0, 1, 2, 3
SPAWN_FOR_KIND = {"parking": SPAWN_CAR, "helipad": SPAWN_HELI, "port": SPAWN_BOAT, "airport": SPAWN_PLANE}


def collect_pois(data: "bw.OsmData", strings: "bw.StringTable") -> list[dict]:
    """POIs from named/typed areas plus place nodes the extract carries."""
    pois: list[dict] = []
    seen: set[tuple[int, int, int]] = set()

    def add(east: float, north: float, key: str, name: str) -> None:
        entry = POI_CLASSES.get(key)
        if entry is None:
            return
        name_key = entry[0]
        # Both a gameplay class (for the map/filters) and a spawn kind (for vehicle placement).
        spawn_kind = SPAWN_FOR_KIND.get(name_key)
        signature = (int(round(east)), int(round(north)), spawn_kind if spawn_kind is not None else 99)
        if signature in seen:
            return
        seen.add(signature)
        pois.append({
            "east": east, "north": north,
            "kind": spawn_kind if spawn_kind is not None else 8,  # 8 = plain landmark, not a spawn
            "class": POI_CLASS_IDS.get(name_key, 13), "name": name,
        })

    for area in data.named_areas + data.places:
        coords = area.get("coords")
        if coords:
            lon = sum(c[0] for c in coords) / len(coords)
            lat = sum(c[1] for c in coords) / len(coords)
        elif "lon" in area and "lat" in area:
            lon, lat = float(area["lon"]), float(area["lat"])
        else:
            continue
        east, north = bw.FWD.transform(lon, lat)
        tags = area.get("tags", {})
        key = None
        for tag_key in ("amenity", "aeroway", "harbour", "public_transport", "railway", "shop", "tourism",
                        "man_made", "leisure", "place"):
            value = tags.get(tag_key)
            if value:
                key = value.split(";")[0].strip().lower()
                break
        if key is None:
            continue
        add(float(east), float(north), key, tags.get("name:en") or tags.get("name") or "")
    return pois


def runway_spawns(data: "bw.OsmData") -> list[dict]:
    """Runway end points, the natural place to spawn the light aeroplane."""
    out: list[dict] = []
    for way in data.aeroways:
        if (way["tags"].get("aeroway") or "") not in ("runway", "taxiway"):
            continue
        coords = way["coords"]
        if len(coords) < 2:
            continue
        for index, endpoint in enumerate((coords[0], coords[-1])):
            east, north = bw.FWD.transform(*endpoint)
            other = coords[-1] if index == 0 else coords[0]
            other_east, other_north = bw.FWD.transform(*other)
            angle = math.degrees(math.atan2(other_north - north, other_east - east)) % 360.0
            out.append({"east": float(east), "north": float(north), "kind": SPAWN_PLANE,
                        "class": POI_CLASS_IDS["airport"], "name": way["tags"].get("ref") or "",
                        "angle": int(round(angle * 10.0))})
    return out


# ---------------------------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------------------------

def build_buildings(data: "bw.OsmData", strings: "bw.StringTable") -> list[dict]:
    records: list[dict] = []
    for feature in data.buildings:
        coords = feature.get("coords")
        if coords is None or len(coords) < 3:
            continue
        ring = list(coords)
        if ring[0] != ring[-1]:
            ring.append(ring[0])
        try:
            poly = Polygon(bw._project(ring))
        except Exception:  # noqa: BLE001
            continue
        if poly.is_empty or poly.area < 4.0:  # below 4 m2 a footprint cannot be a real building
            continue
        if not poly.is_valid:
            poly = poly.buffer(0)
        if poly.is_empty or poly.geom_type != "Polygon":
            continue
        tags = feature["tags"]
        height = bw._building_height(tags)
        min_height = bw._parse_length(tags.get("min_height")) or 0.0
        levels = bw._parse_int(tags.get("building:levels")) or max(1, int(height / 3.2))
        building_type = bw.BUILDING_TYPE.get(tags.get("building") or "unknown", 0)
        roof = ROOF_SHAPE.get((tags.get("roof:shape") or "").lower(), ROOF_FLAT)
        landmark = bool(tags.get("tourism") == "attraction" or tags.get("building") in ("stadium", "tower")
                        or (tags.get("heritage")))
        records.append({
            "rings": [poly.exterior], "height": height, "min_height": min_height,
            "levels": min(255, levels), "type": building_type, "roof": roof,
            "name": tags.get("name:en") or tags.get("name"), "landmark": landmark,
        })
    return records


def build_areas(data: "bw.OsmData", kind: int) -> list[dict]:
    """Land use polygons (kind = surface/vegetation) or water polygons (kind = water classes)."""
    records: list[dict] = []
    for feature in data.landuse + data.named_areas:
        tags = feature["tags"]
        coords = feature.get("coords")
        if coords is None or len(coords) < 4:
            continue
        material = classify_landuse(tags)
        if material is None:
            continue
        ring = list(coords)
        if ring[0] != ring[-1]:
            ring.append(ring[0])
        try:
            poly = Polygon(bw._project(ring))
        except Exception:  # noqa: BLE001
            continue
        if poly.is_empty or poly.area < 200.0:
            continue
        if not poly.is_valid:
            poly = poly.buffer(0)
        if poly.is_empty or poly.geom_type != "Polygon":
            continue
        vegetation = False
        for tag_key in ("natural", "landuse", "leisure"):
            value = (tags.get(tag_key) or "").split(";")[0].strip().lower()
            if value in VEGETATION_TAGS:
                vegetation = True
        records.append({
            "ring": poly.exterior, "class": material,
            "kind": LANDUSE_KIND_VEGETATION if vegetation else LANDUSE_KIND_SURFACE,
            "name": tags.get("name:en") or tags.get("name"),
        })
    return records


def build_water(data: "bw.OsmData") -> list[dict]:
    records: list[dict] = []
    for feature in data.water_areas:
        tags = feature["tags"]
        coords = feature.get("coords")
        if coords is None or len(coords) < 4:
            continue
        ring = list(coords)
        if ring[0] != ring[-1]:
            ring.append(ring[0])
        try:
            poly = Polygon(bw._project(ring))
        except Exception:  # noqa: BLE001
            continue
        if poly.is_empty or poly.area < 100.0:
            continue
        if not poly.is_valid:
            poly = poly.buffer(0)
        if poly.is_empty or poly.geom_type != "Polygon":
            continue
        # kind 0 = sea level water, 1 = inland water body, 2 = wadi (usually dry)
        natural = (tags.get("natural") or "").lower()
        waterway = (tags.get("waterway") or "").lower()
        kind = 2 if waterway in ("wadi", "riverbed", "stream") else (0 if natural in ("bay", "strait") else 1)
        records.append({"ring": poly.exterior, "class": bw.MATERIAL["water_open"], "kind": kind,
                        "name": tags.get("name:en") or tags.get("name")})
    return records


def build_barriers(data: "bw.OsmData") -> list[dict]:
    kinds = {"wall": 0, "fence": 1, "hedge": 2, "retaining_wall": 3, "gate": 4, "city_wall": 0}
    records: list[dict] = []
    for feature in data.landuse + data.named_areas:
        tags = feature["tags"]
        key = tags.get("barrier")
        if not key:
            continue
        kind = kinds.get(key.split(";")[0].strip().lower())
        if kind is None:
            continue
        coords = feature.get("coords")
        if coords is None or len(coords) < 2:
            continue
        try:
            line = LineString(bw._project(coords))
        except Exception:  # noqa: BLE001
            continue
        if line.is_empty or line.length < 3.0:
            continue
        records.append({"line": line, "kind": kind, "name": tags.get("name:en") or tags.get("name"),
                        "height": bw._parse_length(tags.get("height")) or (1.2 if kind != 3 else 2.5)})
    return records


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--osm", default="data/raw/qatar-latest.osm.pbf")
    parser.add_argument("--world", default="android/assets/world",
                        help="directory holding the stage 1 bundles (the packaged asset folder)")
    parser.add_argument("--geo", default="data/geodata")
    args = parser.parse_args()

    world = Path(args.world)
    geo = Path(args.geo)
    world.mkdir(parents=True, exist_ok=True)
    osm_path = Path(args.osm)
    if not osm_path.exists():
        print(f"missing OSM extract: {osm_path}", file=sys.stderr)
        return 2

    bw.log("== game world: stage 2 ==")
    manifest_path = world / "manifest.json"
    if not manifest_path.exists():
        print("stage 1 manifest missing; run tools/build_world.py first", file=sys.stderr)
        return 2
    base = json.loads(manifest_path.read_text())

    data = bw.extract_osm(osm_path)
    # Names added here are appended to the stage 1 table, so every index already packed into the
    # roads and coast bundles keeps pointing at the same string.
    strings = load_string_table(world / "strings.bin.gz")

    bw.log("  packing buildings (real footprints)")
    buildings_payload, building_stats = pack_buildings(build_buildings(data, strings), strings)
    bw.log(f"    buildings: {building_stats['records']:,} rings, {building_stats['named']:,} named")

    bw.log("  packing land use")
    landuse_payload, landuse_stats = pack_areas(build_areas(data, LANDUSE_KIND_SURFACE), strings, b"QLU1")
    bw.log(f"    land use: {landuse_stats['records']:,} polygons")

    bw.log("  packing water")
    water_payload, water_stats = pack_areas(build_water(data), strings, b"QWA1")
    bw.log(f"    water: {water_stats['records']:,} polygons")

    bw.log("  packing barriers")
    barriers_payload, barrier_stats = pack_barriers(build_barriers(data), strings)
    bw.log(f"    barriers: {barrier_stats['records']:,} lines")

    bw.log("  packing points of interest and spawn points")
    poi_list = collect_pois(data, strings) + runway_spawns(data)
    pois_payload, poi_stats = pack_pois(poi_list, strings)
    bw.log(f"    points: {poi_stats['records']:,}")

    strings_payload = strings.to_bytes()
    bw.log(f"  strings: {len(strings):,} entries after appending game names")

    digests = {}
    digests["buildings"] = write_bundle(world / "buildings.bin.gz", buildings_payload)
    digests["landuse"] = write_bundle(world / "landuse.bin.gz", landuse_payload)
    digests["water"] = write_bundle(world / "water.bin.gz", water_payload)
    digests["barriers"] = write_bundle(world / "barriers.bin.gz", barriers_payload)
    digests["pois"] = write_bundle(world / "pois.bin.gz", pois_payload)
    digests["strings"] = write_bundle(world / "strings.bin.gz", strings_payload)

    # Every digest in the manifest is recomputed from the bytes on disk, so it can never lie.
    game = dict(base)
    game["format"] = GAME_FORMAT
    game["gameBundles"] = {
        "buildings": {"file": "buildings.bin.gz", "records": building_stats["records"],
                      "sha256": digests["buildings"]},
        "landuse": {"file": "landuse.bin.gz", "records": landuse_stats["records"],
                    "sha256": digests["landuse"]},
        "water": {"file": "water.bin.gz", "records": water_stats["records"],
                  "sha256": digests["water"]},
        "barriers": {"file": "barriers.bin.gz", "records": barrier_stats["records"],
                     "sha256": digests["barriers"]},
        "pois": {"file": "pois.bin.gz", "records": poi_stats["records"], "sha256": digests["pois"]},
    }
    for key, file_name in (("terrain_l0", "terrain_l0.bin.gz"), ("terrain_l1", "terrain_l1.bin.gz"),
                           ("materials", "material_l1.bin.gz"), ("roads", "roads.bin.gz"),
                           ("strings", "strings.bin.gz")):
        entry = base.get(key)
        if isinstance(entry, dict):
            entry = dict(entry)
        else:
            entry = {}
        path = world / file_name
        if path.exists():
            entry["file"] = file_name
            entry["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        game[key] = entry

    stats = dict(base.get("stats", {}))
    stats["buildings"] = building_stats["records"]
    stats["buildingNames"] = building_stats["named"]
    stats["landusePolygons"] = landuse_stats["records"]
    stats["waterPolygons"] = water_stats["records"]
    stats["barriers"] = barrier_stats["records"]
    stats["pois"] = poi_stats["records"]
    stats["spawnPoints"] = {
        "car": poi_stats["kindHistogram"].get(str(SPAWN_CAR), poi_stats["kindHistogram"].get(SPAWN_CAR, 0)),
        "helicopter": poi_stats["kindHistogram"].get(str(SPAWN_HELI), poi_stats["kindHistogram"].get(SPAWN_HELI, 0)),
        "boat": poi_stats["kindHistogram"].get(str(SPAWN_BOAT), poi_stats["kindHistogram"].get(SPAWN_BOAT, 0)),
        "aeroplane": poi_stats["kindHistogram"].get(str(SPAWN_PLANE), poi_stats["kindHistogram"].get(SPAWN_PLANE, 0)),
    }
    game["stats"] = stats
    game["roofHistogram"] = building_stats["roofHistogram"]
    game["generatedUtc"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    game["gameCompilerSha256"] = hashlib.sha256(
        (Path(__file__).resolve().read_bytes() + bw.compiler_fingerprint().encode())).hexdigest()

    manifest_path.write_text(json.dumps(game, indent=2, ensure_ascii=False) + "\n")
    bw.log(f"  manifest: {manifest_path} ({manifest_path.stat().st_size:,} bytes)")

    lines = []
    for name in ("terrain_l0.bin.gz", "terrain_l1.bin.gz", "material_l1.bin.gz", "roads.bin.gz",
                 "buildings.bin.gz", "landuse.bin.gz", "water.bin.gz", "barriers.bin.gz",
                 "pois.bin.gz", "strings.bin.gz", "coast.bin.gz", "manifest.json"):
        path = world / name
        if path.exists():
            lines.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {name}")
    (geo / "SHA256SUMS").write_text("\n".join(lines) + "\n")
    bw.log(f"  checksums: {len(lines)} files")

    report = {
        "buildings": building_stats, "landuse": landuse_stats, "water": water_stats,
        "barriers": barrier_stats, "pois": poi_stats, "spawnPoints": stats["spawnPoints"],
    }
    (geo / "GAME_WORLD_REPORT.json").write_text(json.dumps(report, indent=2) + "\n")
    bw.log("== game world: stage 2 complete ==")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
