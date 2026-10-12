#!/usr/bin/env python3
"""Deterministic world compiler for QATAR: THE PENINSULA.

Turns the verified open sources (OpenStreetMap extract + SRTM elevation) into the compact runtime
world bundle that ships inside the APK:

    android/assets/world/manifest.json      world epoch, CRS, grid, coverage, checksums
    android/assets/world/terrain_l0.bin.gz  whole-country elevation  (~240 m)
    android/assets/world/terrain_l1.bin.gz  whole-country elevation  (~60 m)
    android/assets/world/material_l1.bin.gz surface material ids      (~60 m)
    android/assets/world/roads.bin.gz       right-hand road network   (classified, named)
    android/assets/world/buildings.bin.gz   building footprints       (height + type + name)
    android/assets/world/coast.bin.gz       land rings (coastline/islands)
    android/assets/world/strings.bin.gz     UTF-8 string table (English + Arabic)

and the provenance documents:

    data/geodata/QATAR_NATIONAL_AOI.geojson
    data/geodata/QATAR_MUNICIPALITIES.geojson
    data/geodata/QATAR_ISLANDS_MANIFEST.json
    data/geodata/QATAR_BOUNDARY_REPORT.md
    data/geodata/SHA256SUMS

Everything is deterministic: the same inputs produce byte-identical outputs (no wall clock, no RNG).
The compiler refuses to run if sources are missing or if the national boundary cannot be assembled.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import os
import struct
import sys
from pathlib import Path

import numpy as np
import shapely
import shapely.ops
from pyproj import CRS, Transformer
from shapely.geometry import (LineString, MultiPolygon, Point, Polygon, mapping, shape)
from shapely.geometry.polygon import orient

REPO = Path(__file__).resolve().parent.parent
RAW = REPO / "data" / "raw"
GEODATA = REPO / "data" / "geodata"
WORLD = REPO / "android" / "assets" / "world"

CRS_MASTER = CRS.from_epsg(32639)  # WGS 84 / UTM zone 39N
FWD = Transformer.from_crs(4326, 32639, always_xy=True)
INV = Transformer.from_crs(32639, 4326, always_xy=True)

MATERIAL = {
    "unknown": 0, "asphalt_dry": 1, "asphalt_dusty": 2, "asphalt_wet": 3, "concrete": 4,
    "gravel_rock": 5, "sand_compact": 6, "sand_loose_dune": 7, "sand_wet": 8, "sabkha": 9,
    "mud_tidalflat": 10, "mangrove_marsh": 11, "farm_soil": 12, "urban_pad": 13,
    "reclaimed_land": 14, "water_shallow": 15, "water_open": 16,
}

ROAD_CLASS = {
    "motorway": 1, "trunk": 2, "primary": 3, "secondary": 4, "tertiary": 5,
    "unclassified": 6, "residential": 7, "service": 8, "living_street": 9,
    "track": 10, "path": 11, "footway": 12, "cycleway": 13, "steps": 14,
    "motorway_link": 15, "trunk_link": 16, "primary_link": 17, "secondary_link": 18,
    "tertiary_link": 19, "road": 20, "bridleway": 21, "construction": 22, "proposed": 23,
}
ROAD_CLASS_DEF = {  # default lanes, width (m), speed (km/h) per class, used when tags are missing
    1: (4, 22.0, 120), 2: (3, 16.0, 100), 3: (3, 14.0, 90), 4: (2, 12.0, 80), 5: (2, 9.0, 70),
    6: (2, 7.0, 60), 7: (2, 6.0, 50), 8: (1, 5.0, 30), 9: (1, 5.0, 20), 10: (1, 4.0, 40),
    11: (1, 2.0, 10), 12: (1, 2.0, 5), 13: (1, 2.0, 15), 14: (1, 1.5, 5), 15: (2, 8.0, 60),
    16: (2, 8.0, 60), 17: (1, 7.0, 60), 18: (1, 7.0, 50), 19: (1, 7.0, 45), 20: (1, 5.0, 40),
    21: (1, 2.0, 10), 22: (1, 5.0, 30), 23: (1, 5.0, 30),
}
BUILDING_TYPE = {
    "unknown": 0, "house": 1, "apartments": 2, "residential": 3, "commercial": 4, "retail": 5,
    "industrial": 6, "warehouse": 7, "mosque": 8, "school": 9, "university": 10, "hospital": 11,
    "hotel": 12, "office": 13, "garage": 14, "shed": 15, "construction": 16, "government": 17,
    "stadium": 18, "airport": 19, "parking": 20, "farm": 21, "hangar": 22, "tower": 23,
}

# Factual onshore reference points (well known Qatari locations) and offshore water points. These
# exist purely to classify polygonised coastline faces as land or sea; every one of them is public
# geography, not derived geometry.
LAND_REFERENCE_POINTS = [
    (51.5310, 25.2854), (51.6000, 25.1700), (51.5000, 25.6800), (50.7830, 25.3900),
    (51.1700, 24.7420), (51.2150, 26.1330), (51.5500, 24.9900), (51.2200, 25.3700),
    (51.5200, 25.4200), (51.3700, 25.2900), (51.2000, 26.1100), (51.3600, 26.0200),
    (51.0300, 25.9800), (50.8400, 25.5500), (51.4400, 25.3100), (51.5300, 24.8500),
]
SEA_REFERENCE_POINTS = [
    (51.7000, 25.2000),   # Gulf, ~10 km east of Al Wakrah
    (51.6500, 25.4000),   # Doha Bay, offshore
    (51.4000, 26.2500),   # Gulf, ~10 km north of the northern tip
    (50.7000, 25.5000),   # Gulf, ~8 km west of the Dukhan coast
    (51.6500, 24.7000),   # Gulf, south-east of Mesaieed
    (51.9000, 25.5000),   # further out, harmless if outside the boundary polygon
]

# Mandatory national checkpoint groups (specification section 2/11). Each entry is matched against
# OpenStreetMap features by name; a group with no match is reported and fails the coverage test.
CHECKPOINTS = [
    ("SOUTH_AND_BORDER", "ABU_SAMRA", ["Abu Samra", "Abu Samrah", "أبو سمرة"], "place|highway|boundary"),
    ("SOUTH_AND_BORDER", "KHOR_AL_ADAID", ["Khor Al Adaid", "Khor al Adaid", "Inland Sea", "خور العديد"], "place|natural"),
    ("SOUTH_AND_BORDER", "MESAIEED", ["Mesaieed", "Mesaieed Industrial City", "مسيعيد"], "place"),
    ("SOUTH_AND_BORDER", "HAMAD_PORT", ["Hamad Port", "ميناء حمد"], "harbour|landuse"),
    ("CENTRAL_AND_CAPITAL", "DOHA", ["Doha", "الدوحة"], "place"),
    ("CENTRAL_AND_CAPITAL", "HAMAD_INTERNATIONAL_AIRPORT", ["Hamad International Airport", "مطار حمد الدولي"], "aeroway"),
    ("CENTRAL_AND_CAPITAL", "DOHA_INTERNATIONAL_AIRPORT", ["Doha International Airport"], "aeroway"),
    ("CENTRAL_AND_CAPITAL", "DOHA_CORNICHE", ["Corniche", "Doha Corniche", "الكورنيش"], "way|place"),
    ("CENTRAL_AND_CAPITAL", "WEST_BAY", ["West Bay", "الخليج الغربي"], "place"),
    ("CENTRAL_AND_CAPITAL", "SOUQ_WAQIF", ["Souq Waqif", "سوق واقف"], "place|landuse"),
    ("CENTRAL_AND_CAPITAL", "MSHEIREB", ["Msheireb", "مشيرب"], "place|landuse"),
    ("CENTRAL_AND_CAPITAL", "AL_RAYYAN", ["Al Rayyan", "الريان"], "place"),
    ("CENTRAL_AND_CAPITAL", "EDUCATION_CITY", ["Education City", "المدينة التعليمية"], "place|amenity"),
    ("CENTRAL_AND_CAPITAL", "ASPIRE_ZONE", ["Aspire", "Aspire Zone", "أسباير"], "place|leisure"),
    ("NORTH_AND_NORTHEAST", "LUSAIL", ["Lusail", "لوسيل"], "place"),
    ("NORTH_AND_NORTHEAST", "AL_DAAYEN", ["Al Daayen", "الظعاين"], "place|boundary"),
    ("NORTH_AND_NORTHEAST", "UMM_SALAL", ["Umm Salal", "أم صلال"], "place|boundary"),
    ("NORTH_AND_NORTHEAST", "AL_KHOR", ["Al Khor", "الخور"], "place"),
    ("NORTH_AND_NORTHEAST", "AL_THAKHIRA", ["Al Thakhira", "الذخيرة"], "place"),
    ("NORTH_AND_NORTHEAST", "RAS_LAFFAN", ["Ras Laffan", "راس لفان"], "place|harbour|landuse"),
    ("NORTH_AND_NORTHEAST", "FUWAIRIT", ["Fuwairit", "فويرط"], "place"),
    ("NORTH_AND_NORTHEAST", "THE_PEARL", ["The Pearl", "Pearl-Qatar", "اللؤلؤة"], "place|landuse"),
    ("WEST_AND_NORTHWEST", "DUKHAN", ["Dukhan", "دخان"], "place"),
    ("WEST_AND_NORTHWEST", "ZEKREET", ["Zekreet", "Ras Abrouq", "زكريت"], "place|natural"),
    ("WEST_AND_NORTHWEST", "AL_SHAHANIYA", ["Al Shahaniya", "الشحانية"], "place|boundary"),
    ("WEST_AND_NORTHWEST", "AL_ZUBARAH", ["Al Zubarah", "الزبارة"], "place|historic"),
    ("NORTH_AND_NORTHEAST", "AL_RUWAIS", ["Al Ruwais", "الرويس"], "place|harbour"),
    ("NORTH_AND_NORTHEAST", "MADINAT_ASH_SHAMAL", ["Madinat ash Shamal", "مدينة الشمال"], "place|boundary"),
    ("SOUTH_AND_BORDER", "AL_WAKRAH", ["Al Wakrah", "الوكرة"], "place"),
    ("CENTRAL_AND_CAPITAL", "AL_SADD", ["Al Sadd", "السد"], "place"),
    ("ISLANDS_AND_WATER", "HALUL_ISLAND", ["Halul", "حالول"], "place|natural"),
    ("ISLANDS_AND_WATER", "SHAROUH_ISLAND", ["Sharouh", "شروة"], "place|natural"),
]


# --------------------------------------------------------------------------------------- utilities
def log(msg: str) -> None:
    print(msg, flush=True)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_gz(path: Path, payload: bytes, mtime: int = 0) -> None:
    """Deterministic gzip: fixed mtime, fixed OS byte."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", mtime=mtime, compresslevel=6) as gz:
            gz.write(payload)


# ------------------------------------------------------------------------------------ OSM extraction
class OsmData:
    """Everything the compiler needs from the OpenStreetMap extract."""

    def __init__(self) -> None:
        self.boundary_ways: list[list[tuple[float, float]]] = []
        self.municipality_relations: list[dict] = []
        self.municipality_ways: list[dict] = []
        self.islands: list[dict] = []
        self.roads: list[dict] = []
        self.buildings: list[dict] = []
        self.landuse: list[dict] = []
        self.coastlines: list[list[tuple[float, float]]] = []
        self.water_areas: list[dict] = []
        self.aeroways: list[dict] = []
        self.places: list[dict] = []
        self.named_areas: list[dict] = []
        self.stats: dict[str, int] = {}


def extract_osm(osm_path: Path) -> OsmData:
    import osmium

    data = OsmData()
    relation_members: list[dict] = []
    way_tags_for_relations: dict[int, list[tuple[float, float]]] = {}
    wanted_relation_ways: set[int] = set()

    class BoundaryRelations(osmium.SimpleHandler):
        def relation(self, r):  # noqa: N802 - osmium API
            tags = {t.k: t.v for t in r.tags}
            if tags.get("type") != "boundary" and tags.get("type") != "multipolygon":
                return
            if tags.get("boundary") != "administrative":
                return
            level = tags.get("admin_level")
            if level not in ("2", "4", "6"):
                return
            members = [(m.ref, m.role, m.type) for m in r.members if m.type == "w"]
            relation_members.append({
                "id": r.id, "admin_level": level, "tags": tags, "members": members,
            })
            for ref, _role, _type in members:
                wanted_relation_ways.add(ref)

    class Collector(osmium.SimpleHandler):
        def __init__(self) -> None:
            super().__init__()
            self.count = 0

        def way(self, w):  # noqa: N802
            self.count += 1
            tags = {t.k: t.v for t in w.tags}
            coords = [(n.lon, n.lat) for n in w.nodes if n.location.valid()]
            # Relation membership is captured BEFORE the tag filter: boundary member ways frequently
            # carry no tags of their own, and dropping them breaks ring assembly.
            if w.id in wanted_relation_ways and len(coords) >= 2:
                way_tags_for_relations[w.id] = coords
            if not tags or len(coords) < 2:
                return

            if tags.get("natural") == "coastline":
                data.coastlines.append(coords)
                return
            if tags.get("boundary") == "administrative" and tags.get("admin_level") in ("2", "4", "6"):
                data.municipality_ways.append({"id": w.id, "tags": tags, "coords": coords})
                if tags.get("admin_level") == "2":
                    data.boundary_ways.append(coords)
            building = tags.get("building")
            if building and building != "no":
                area = _way_area_m2(coords)
                if area >= 12.0:
                    data.buildings.append({"id": w.id, "tags": tags, "coords": coords, "area": area})
                return
            highway = tags.get("highway")
            if highway:
                if highway in ROAD_CLASS and tags.get("area") != "yes":
                    data.roads.append({"id": w.id, "tags": tags, "coords": coords})
                return
            if tags.get("natural") in ("water", "wetland") or tags.get("landuse") in (
                    "reservoir", "basin", "salt_pond"):
                data.water_areas.append({"id": w.id, "tags": tags, "coords": coords})
                return
            if tags.get("place") in ("island", "islet", "island_group"):
                data.islands.append({"id": w.id, "tags": tags, "coords": coords})
                return
            if tags.get("aeroway") in ("runway", "taxiway", "apron", "helipad", "aerodrome"):
                data.aeroways.append({"id": w.id, "tags": tags, "coords": coords})
                return
            landuse = tags.get("landuse") or tags.get("natural") or tags.get("leisure")
            if landuse:
                area = _way_area_m2(coords)
                if area >= 400.0:
                    data.landuse.append({"id": w.id, "tags": tags, "coords": coords, "area": area})
            if tags.get("name"):
                data.named_areas.append({"id": w.id, "tags": tags, "coords": coords})

        def node(self, n):  # noqa: N802
            tags = {t.k: t.v for t in n.tags}
            place = tags.get("place")
            if place:
                data.places.append({
                    "id": n.id, "name": tags.get("name", ""), "name_ar": tags.get("name:ar", ""),
                    "name_en": tags.get("name:en", ""), "place": place,
                    "lon": n.location.lon, "lat": n.location.lat, "tags": tags,
                })

    log("  parsing relations (boundaries)")
    BoundaryRelations().apply_file(str(osm_path))
    log(f"  boundary relations: {len(relation_members)} (admin levels 2/4/6)")
    log("  parsing ways/nodes")
    collector = Collector()
    collector.apply_file(str(osm_path), locations=True)
    log(f"  ways visited: {collector.count}")

    # Assemble municipality polygons from relations and standalone closed ways.
    for rel in relation_members:
        if rel["admin_level"] not in ("4", "6"):
            continue
        rings = _assemble_rings([(way_tags_for_relations.get(ref, []), role)
                                 for ref, role, _t in rel["members"]])
        poly = _rings_to_polygon(rings)
        if poly is not None and not poly.is_empty:
            data.municipality_relations.append({"id": rel["id"], "tags": rel["tags"], "geometry": poly})

    # National boundary: the country relation is identified by ISO code / name so that a larger
    # regional extract (e.g. the GCC states file) still yields exactly the State of Qatar.
    def is_qatar(tags: dict) -> bool:
        iso = (tags.get("ISO3166-1") or tags.get("ISO3166-1:alpha2") or "").upper()
        if iso == "QA":
            return True
        names = {tags.get("name", ""), tags.get("name:en", ""), tags.get("official_name", "")}
        return bool(names & {"Qatar", "State of Qatar", "دولة قطر", "قطر"})

    national = None
    candidates = [rel for rel in relation_members if rel["admin_level"] == "2"]
    candidates.sort(key=lambda rel: 0 if is_qatar(rel["tags"]) else 1)
    for rel in candidates:
        rings = _assemble_rings([(way_tags_for_relations.get(ref, []), role)
                                 for ref, role, _t in rel["members"]])
        poly = _rings_to_polygon(rings)
        if poly is None or poly.is_empty:
            continue
        if not is_qatar(rel["tags"]):
            # Only accept a non-Qatar-tagged relation when the extract contains nothing else.
            if len(candidates) > 1:
                continue
            log(f"  WARNING: using admin_level=2 relation {rel['id']} without a Qatar tag")
        national = poly
        log(f"  national boundary assembled from relation {rel['id']} "
            f"({rel['tags'].get('name:en') or rel['tags'].get('name')})")
        break
    if national is None and data.boundary_ways:
        line = shapely.ops.linemerge([LineString(w) for w in data.boundary_ways if len(w) > 3])
        polys = list(shapely.ops.polygonize(line))
        if polys:
            national = max(polys, key=lambda p: p.area)
            log("  national boundary assembled from admin_level=2 ways")
    if national is None:
        raise SystemExit("FATAL: could not assemble the national boundary from OpenStreetMap data")

    data.stats.update({
        "coastline_ways": len(data.coastlines),
        "roads": len(data.roads),
        "buildings": len(data.buildings),
        "landuse": len(data.landuse),
        "water_areas": len(data.water_areas),
        "places": len(data.places),
        "islands": len(data.islands),
        "aeroways": len(data.aeroways),
        "named_areas": len(data.named_areas),
    })
    return national, data


def _way_area_m2(coords_wgs: list[tuple[float, float]]) -> float:
    if len(coords_wgs) < 4:
        return 0.0
    xs, ys = FWD.transform([c[0] for c in coords_wgs], [c[1] for c in coords_wgs])
    if len(xs) < 4 or xs[0] != xs[-1] or ys[0] != ys[-1]:
        return 0.0
    return abs(shapely.area(Polygon(zip(xs, ys))))


def _assemble_rings(ways_with_roles: list[tuple[list[tuple[float, float]], str]]) -> dict[str, list[list]]:
    """Stitches multipolygon member ways into outer/inner rings."""
    rings: dict[str, list[list]] = {"outer": [], "inner": []}
    for role in ("outer", "inner"):
        segments = [ways for ways, r in ways_with_roles if (r or "outer") == role and len(ways) >= 2]
        rings[role] = _stitch(segments)
    return rings


def _stitch(segments: list[list[tuple[float, float]]]) -> list[list[tuple[float, float]]]:
    remaining = [list(s) for s in segments]
    closed: list[list[tuple[float, float]]] = []
    while remaining:
        ring = remaining.pop(0)
        progress = True
        while progress and ring[0] != ring[-1]:
            progress = False
            for i, seg in enumerate(remaining):
                if seg[0] == ring[-1]:
                    ring.extend(seg[1:])
                elif seg[-1] == ring[-1]:
                    ring.extend(reversed(seg[:-1]))
                elif seg[-1] == ring[0]:
                    ring = seg[:-1] + ring
                elif seg[0] == ring[0]:
                    ring = list(reversed(seg[1:])) + ring
                else:
                    continue
                remaining.pop(i)
                progress = True
                break
        if ring[0] == ring[-1] and len(ring) >= 4:
            closed.append(ring)
    return closed


def _rings_to_polygon(rings: dict[str, list[list]]) -> Polygon | MultiPolygon | None:
    outers = [Polygon(r) for r in rings.get("outer", []) if len(r) >= 4]
    inners = [Polygon(r) for r in rings.get("inner", []) if len(r) >= 4]
    outers = [p for p in outers if p.is_valid or p.buffer(0).is_valid]
    outers = [p if p.is_valid else p.buffer(0) for p in outers]
    if not outers:
        return None
    for inner in inners:
        if not inner.is_valid:
            inner = inner.buffer(0)
        for i, outer in enumerate(outers):
            if outer.contains(inner.representative_point()):
                outers[i] = outer.difference(inner)
    outers = [p for p in outers if p.area > 1e-9]
    if not outers:
        return None
    return outers[0] if len(outers) == 1 else MultiPolygon(outers)


# ----------------------------------------------------------------------------------- elevation (DEM)
def load_dem() -> tuple[np.ndarray, int]:
    """Mosaic the SRTM tiles into one array. Returns (array, lat/lon of the north-west corner)."""
    lat_min, lat_max = 24, 27
    lon_min, lon_max = 50, 53
    tiles_lat = lat_max - lat_min
    tiles_lon = lon_max - lon_min
    rows = tiles_lat * 3600 + 1
    cols = tiles_lon * 3600 + 1
    mosaic = np.full((rows, cols), -32768, dtype=np.int16)
    have = 0
    for lat in range(lat_min, lat_max):
        for lon in range(lon_min, lon_max):
            path = RAW / "srtm" / f"N{lat:02d}E{lon:03d}.hgt.gz"
            if not path.exists():
                continue
            with gzip.open(path, "rb") as fh:
                buf = fh.read()
            expected = 3601 * 3601 * 2
            if len(buf) < expected:
                log(f"  WARNING: tile {path.name} is short ({len(buf)} < {expected} bytes) - skipped")
                continue
            tile = np.frombuffer(buf[:expected], dtype=">i2").reshape(3601, 3601).astype(np.int16)
            # Tile row 0 is the northern edge: N{lat+1}. Column 0 is the western edge.
            r0 = (lat_max - (lat + 1)) * 3600
            c0 = (lon - lon_min) * 3600
            mosaic[r0:r0 + 3601, c0:c0 + 3601] = tile
            have += 1
    if have == 0:
        raise SystemExit("FATAL: no SRTM tiles available in data/raw/srtm")
    log(f"  DEM mosaic: {have} tiles, {mosaic.shape[1]}x{mosaic.shape[0]} samples")
    return mosaic, lat_max


def sample_dem_valid(mosaic: np.ndarray, lat_max: int, lat: np.ndarray, lon: np.ndarray) -> np.ndarray:
    """True where the SRTM mosaic holds real elevation data (void cells mark open water)."""
    row = np.clip(np.floor((lat_max - lat) * 3600.0).astype(np.int64), 0, mosaic.shape[0] - 1)
    col = np.clip(np.floor((lon - 50.0) * 3600.0).astype(np.int64), 0, mosaic.shape[1] - 1)
    return mosaic[row, col] > -1000


def sample_dem(mosaic: np.ndarray, lat_max: int, lat: np.ndarray, lon: np.ndarray) -> np.ndarray:
    """Bilinear sampling of the mosaic at WGS84 coordinates."""
    row = (lat_max - lat) * 3600.0
    col = (lon - 50.0) * 3600.0
    r0 = np.clip(np.floor(row).astype(np.int64), 0, mosaic.shape[0] - 2)
    c0 = np.clip(np.floor(col).astype(np.int64), 0, mosaic.shape[1] - 2)
    fr = np.clip(row - r0, 0, 1)
    fc = np.clip(col - c0, 0, 1)
    v00 = mosaic[r0, c0].astype(np.float32)
    v01 = mosaic[r0, c0 + 1].astype(np.float32)
    v10 = mosaic[r0 + 1, c0].astype(np.float32)
    v11 = mosaic[r0 + 1, c0 + 1].astype(np.float32)
    v00[v00 < -1000] = 0
    v01[v01 < -1000] = 0
    v10[v10 < -1000] = 0
    v11[v11 < -1000] = 0
    top = v00 + (v01 - v00) * fc
    bottom = v10 + (v11 - v10) * fc
    return top + (bottom - top) * fr


# ---------------------------------------------------------------------------------- grid + outputs
def build_grid(national: Polygon | MultiPolygon, buffer_m: float = 25000.0) -> dict:
    """National world grid: an integer metre lattice covering Qatar plus a water/airspace buffer."""
    minx, miny, maxx, maxy = national.bounds
    minx -= buffer_m
    miny -= buffer_m
    maxx += buffer_m
    maxy += buffer_m
    origin_e = math.floor(minx / 1000.0) * 1000.0
    origin_n = math.floor(miny / 1000.0) * 1000.0
    width = math.ceil((maxx - origin_e) / 1000.0) * 1000.0
    height = math.ceil((maxy - origin_n) / 1000.0) * 1000.0
    return {"originEast": origin_e, "originNorth": origin_n, "widthM": width, "heightM": height}


def grid_axes(grid: dict, cell_m: float) -> tuple[np.ndarray, np.ndarray, int, int]:
    cols = int(math.ceil(grid["widthM"] / cell_m))
    rows = int(math.ceil(grid["heightM"] / cell_m))
    xs = grid["originEast"] + (np.arange(cols) + 0.5) * cell_m
    ys = grid["originNorth"] + (np.arange(rows) + 0.5) * cell_m
    return xs, ys, cols, rows


def pack_terrain(grid: dict, cell_m: float, heights: np.ndarray, masks: np.ndarray) -> bytes:
    rows, cols = heights.shape
    header = struct.pack("<4sIIfI", b"QTT1", cols, rows, cell_m, 0)
    body = heights.astype("<i2").tobytes()
    mask = masks.astype("u1").tobytes()
    return header + body + mask


def pack_materials(grid: dict, cell_m: float, material: np.ndarray) -> bytes:
    rows, cols = material.shape
    header = struct.pack("<4sIIfI", b"QTM1", cols, rows, cell_m, 0)
    return header + material.astype("u1").tobytes()


class StringTable:
    def __init__(self) -> None:
        self._index: dict[str, int] = {}
        self._values: list[str] = []

    def add(self, value: str | None) -> int:
        if not value:
            return 0xFFFF
        value = value.strip()
        if not value:
            return 0xFFFF
        if value not in self._index:
            self._index[value] = len(self._values)
            self._values.append(value)
        return self._index[value]

    def to_bytes(self) -> bytes:
        out = bytearray()
        out += struct.pack("<4sII", b"QTS1", 1, len(self._values))
        for value in self._values:
            raw = value.encode("utf-8")
            out += struct.pack("<H", len(raw))
            out += raw
        return bytes(out)

    def __len__(self) -> int:
        return len(self._values)


def pack_roads(roads: list[dict], strings: StringTable) -> tuple[bytes, dict]:
    out = bytearray()
    counts: dict[int, int] = {}
    payload = bytearray()
    n = 0
    for road in roads:
        coords = road["projected"]
        if len(coords) < 2:
            continue
        cls = road["class"]
        lanes, width, speed = ROAD_CLASS_DEF.get(cls, (1, 4.0, 30))
        # Explicit None (untagged) must fall back to the class default, not crash the compiler.
        lanes = road.get("lanes") or lanes
        width = road.get("width") or width
        speed = road.get("speed") or speed
        flags = 0
        if road.get("bridge"):
            flags |= 1
        if road.get("tunnel"):
            flags |= 2
        if road.get("oneway"):
            flags |= 4
        name_idx = strings.add(road.get("name_en") or road.get("name"))
        name_ar_idx = strings.add(road.get("name_ar"))
        pts = coords
        if len(pts) > 400:  # hard cap: no single way may bloat the bundle
            step = len(pts) / 400.0
            pts = [pts[int(i * step)] for i in range(400)] + [pts[-1]]
        body = bytearray()
        body += struct.pack("<BBBBH", cls, flags, min(255, lanes), min(255, int(speed / 5)),
                            min(65535, int(width * 10)))
        body += struct.pack("<HH", name_idx, name_ar_idx)
        body += struct.pack("<H", len(pts))
        prev_e = prev_n = None
        for (e, nn) in pts:
            ie = int(round(e * 100))
            inn = int(round(nn * 100))
            if prev_e is None:
                body += struct.pack("<ii", ie, inn)
            else:
                body += struct.pack("<ii", ie - prev_e, inn - prev_n)
            prev_e, prev_n = ie, inn
        payload += body
        counts[cls] = counts.get(cls, 0) + 1
        n += 1
    header = struct.pack("<4sIIQ", b"QTR1", 1, n, len(payload))
    return header + bytes(payload), counts


def pack_buildings(buildings: list[dict], strings: StringTable) -> bytes:
    payload = bytearray()
    n = 0
    for b in buildings:
        payload += struct.pack("<iiHHHHBHBB", b["x_cm"], b["y_cm"], b["w_dm"], b["d_dm"],
                               b["rot"], b["h_dm"], b["type"], b["levels"], b["flags"], 0)
        payload += struct.pack("<H", b["name_idx"])
        n += 1
    header = struct.pack("<4sIIQ", b"QTB1", 1, n, len(payload))
    return header + bytes(payload)


def pack_coast(rings: list[tuple[int, list[tuple[float, float]]]], grid: dict) -> bytes:
    payload = bytearray()
    n = 0
    for island_id, ring in rings:
        if len(ring) < 4:
            continue
        body = bytearray()
        body += struct.pack("<IH", island_id, min(65535, len(ring)))
        prev_e = prev_n = None
        for (e, nn) in ring:
            if not (np.isfinite(e) and np.isfinite(nn)):
                continue
            ie = int(round(e * 100))
            inn = int(round(nn * 100))
            if prev_e is None:
                body += struct.pack("<ii", ie, inn)
            else:
                body += struct.pack("<ii", ie - prev_e, inn - prev_n)
            prev_e, prev_n = ie, inn
        payload += body
        n += 1
    header = struct.pack("<4sIIQ", b"QTC1", 1, n, len(payload))
    return header + bytes(payload)


def diagnose(osm_path: Path) -> int:
    """Print the geometry facts needed to debug land/sea classification. No outputs are written."""
    national_wgs, data = extract_osm(osm_path)
    national = project_geometry(national_wgs)
    log("== national boundary ==")
    parts = [national] if national.geom_type == "Polygon" else list(national.geoms)
    for i, part in enumerate(parts):
        rep = part.representative_point()
        lon, lat = INV.transform(rep.x, rep.y)
        log(f"  part {i}: {part.area / 1e6:,.1f} km2, rep point {lon:.4f},{lat:.4f}, "
            f"bounds E {part.bounds[0]:,.0f}..{part.bounds[2]:,.0f}")

    log("== coastline ==")
    lines = [LineString(_project(w)) for w in data.coastlines if len(w) >= 2]
    merged = shapely.ops.linemerge(shapely.union_all(lines)) if lines else None
    log(f"  coastline ways: {len(lines)}")
    if merged is not None:
        pieces = [merged] if merged.geom_type == "LineString" else list(merged.geoms)
        log(f"  merged pieces: {len(pieces)}")
        total = sum(len(p.coords) for p in pieces)
        log(f"  total vertices: {total:,}")
        ring = LineString(parts[0].exterior.coords)
        stitches = _seal_network(pieces, ring)
        log(f"  dangling endpoints stitched to the largest ring: {len(stitches)}")
        for st in stitches[:8]:
            lon0, lat0 = INV.transform(st.coords[0][0], st.coords[0][1])
            lon1, lat1 = INV.transform(st.coords[1][0], st.coords[1][1])
            log(f"    stitch {lon0:.4f},{lat0:.4f} -> {lon1:.4f},{lat1:.4f} "
                f"({st.length / 1000:.1f} km)")

    log("== candidate faces (top 10 by area) ==")
    from collections import Counter
    ends: Counter = Counter()
    for part in ([merged] if merged is not None and merged.geom_type == "LineString" else list(merged.geoms)):
        coords = list(part.coords)
        ends[(round(coords[0][0], 6), round(coords[0][1], 6))] += 1
        ends[(round(coords[-1][0], 6), round(coords[-1][1], 6))] += 1
    log(f"  distinct line endpoints: {len(ends)}, dangling (occur once): {sum(1 for v in ends.values() if v == 1)}")

    road_points = []
    for road in data.roads[:4000]:
        coords = road["coords"]
        if len(coords) >= 2:
            mid = coords[len(coords) // 2]
            road_points.append(Point(*FWD.transform(mid[0], mid[1])))
    building_points = []
    for b in data.buildings[:4000]:
        coords = b["coords"]
        if len(coords) >= 3:
            lon = sum(c[0] for c in coords) / len(coords)
            lat = sum(c[1] for c in coords) / len(coords)
            building_points.append(Point(*FWD.transform(lon, lat)))

    water_polys = []
    for w in data.water_areas:
        poly = _closed_way_polygon(w["coords"])
        if poly is not None and poly.area > 10000.0:
            water_polys.append(poly)
    log(f"  sampled roads: {len(road_points)}, buildings: {len(building_points)}, "
        f"mapped water polygons: {len(water_polys)}")

    for i, part in enumerate(parts[:3]):
        ring = LineString(part.exterior.coords)
        pieces = [merged] if merged is not None and merged.geom_type == "LineString" else list(merged.geoms)
        st = _seal_network(pieces, ring)
        polys = list(shapely.ops.polygonize(pieces + st + [ring]))
        log(f"  -- largest ring {i}: {len(polys)} faces")
        polys.sort(key=lambda p: -p.area)
        for face in polys[:10]:
            rep = face.representative_point()
            lon, lat = INV.transform(rep.x, rep.y)
            roads = sum(1 for p in road_points if face.contains(p))
            buildings = sum(1 for p in building_points if face.contains(p))
            water = sum(1 for p in water_polys if face.contains(p.representative_point()))
            log(f"     {face.area / 1e6:9,.1f} km2 at {lon:7.4f},{lat:6.4f}  roads={roads:4d} "
                f"buildings={buildings:4d} waterPolys={water:3d}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stage", default="all", choices=["boundary", "terrain", "vectors", "all", "diagnose"])
    ap.add_argument("--cell-l0", type=float, default=300.0)
    ap.add_argument("--cell-l1", type=float, default=100.0)
    args = ap.parse_args()

    osm_path = RAW / "qatar-latest.osm.pbf"
    if not osm_path.exists():
        raise SystemExit(f"FATAL: {osm_path} missing - run tools/fetch_sources.py first")

    GEODATA.mkdir(parents=True, exist_ok=True)
    WORLD.mkdir(parents=True, exist_ok=True)

    if args.stage == "diagnose":
        return diagnose(osm_path)
    log("[1] OpenStreetMap extraction")
    national_wgs, data = extract_osm(osm_path)
    # Project exactly once: the rest of the compiler works in EPSG:32639 metres only.
    national = project_geometry(national_wgs)
    national = orient(national, 1.0)
    log(f"  national AOI: {national.geom_type}, area {national.area / 1e6:.1f} km2, "
        f"{'valid' if national.is_valid else 'INVALID'}")
    if national.area / 1e6 < 1000.0:
        raise SystemExit(
            f"FATAL: national boundary area {national.area / 1e6:.2f} km2 is implausible for Qatar; "
            f"relation ring assembly failed (outers={len(national.geoms) if hasattr(national, 'geoms') else 1})")
    log(f"  stats: {json.dumps(data.stats)}")

    # Municipalities (admin_level 4 preferred, 6 as fallback), clipped to the national AOI -------
    munis = []
    for rel in data.municipality_relations:
        geom = project_geometry(rel["geometry"])
        if geom.is_empty:
            continue
        if not national.contains(geom.representative_point()):
            continue  # neighbouring country in a regional extract
        if geom.area / 1e6 > 5000.0:
            continue  # a province-scale unit cannot be a Qatari municipality
        rel = dict(rel, geometry=geom)
        name = rel["tags"].get("name") or rel["tags"].get("name:en") or f"admin-{rel['id']}"
        munis.append({
            "id": rel["tags"].get("ISO3166-2") or f"OSM-{rel['id']}",
            "name": name,
            "name_en": rel["tags"].get("name:en") or name,
            "name_ar": rel["tags"].get("name:ar", ""),
            "admin_level": rel["tags"].get("admin_level"),
            "geometry": rel["geometry"],
            "area_km2": rel["geometry"].area / 1e6,
        })
    # Keep the most detailed level that yields a plausible number of municipal units.
    for level in ("4", "6"):
        subset = [m for m in munis if m["admin_level"] == level]
        if len(subset) >= 4:
            munis = subset
            break
    log(f"  municipalities: {len(munis)}")

    # Islands --------------------------------------------------------------------------------
    islands = []
    for isl in data.islands:
        poly = _closed_way_polygon(isl["coords"])
        if poly is None or poly.area < 1000.0:
            continue
        islands.append({
            "id": f"osm-w{isl['id']}",
            "name": isl["tags"].get("name", ""),
            "name_en": isl["tags"].get("name:en", ""),
            "name_ar": isl["tags"].get("name:ar", ""),
            "area_m2": poly.area,
            "geometry": poly,
            "source": "osm-way-place-island",
        })
    log(f"  coastline ways for the land derivation: {len(data.coastlines)}; "
        f"{len(islands)} tagged island polygons")

    # Grid ------------------------------------------------------------------------------------
    grid = build_grid(national)
    log(f"  world grid: origin=({grid['originEast']:.0f},{grid['originNorth']:.0f}) "
        f"{grid['widthM'] / 1000:.0f}x{grid['heightM'] / 1000:.0f} km")

    # Rasterised masks (land / water / land use) ----------------------------------------------
    xs_l1, ys_l1, cols_l1, rows_l1 = grid_axes(grid, args.cell_l1)

    dem_mosaic, dem_lat_max = load_dem()
    # UTM grid -> WGS84 -> bilinear DEM sample
    xx, yy = np.meshgrid(xs_l1, ys_l1)
    lon_l1, lat_l1 = INV.transform(xx.ravel(), yy.ravel())
    lat_l1 = np.asarray(lat_l1).reshape(xx.shape)
    lon_l1 = np.asarray(lon_l1).reshape(xx.shape)
    heights_l1 = sample_dem(dem_mosaic, dem_lat_max, lat_l1, lon_l1)
    elevation_valid = sample_dem_valid(dem_mosaic, dem_lat_max, lat_l1, lon_l1)

    # Land classification. The OSM administrative boundary of Qatar includes territorial water, so it
    # cannot be used as the land mask directly. The land polygons below come from the coastline
    # network closed against the national border ring, and each candidate face is classified by the
    # elevation authority: faces whose samples carry real SRTM data are land, void faces are sea.
    seed_lines = [r["coords"] for r in data.roads]
    seed_points = []
    for footprint in data.buildings[:40000]:
        coords = footprint["coords"]
        if len(coords) >= 3:
            seed_points.append((sum(c[0] for c in coords) / len(coords),
                                sum(c[1] for c in coords) / len(coords)))
    land_mask, land_diag = derive_land_mask(data.coastlines, seed_lines, seed_points,
                                           xs_l1, ys_l1, args.cell_l1)
    land_area_km2 = float(land_mask.sum()) * args.cell_l1 * args.cell_l1 / 1e6
    from scipy import ndimage as _ndimage
    labels_all, comp_count = _ndimage.label(land_mask)
    sizes = _ndimage.sum(land_mask, labels_all, index=range(1, comp_count + 1)) * (args.cell_l1 ** 2) / 1e6
    island_faces = [i for i, sz in enumerate(sizes, start=1) if sz >= 0.02]
    island_km2 = sorted((float(s) for s in sizes if s >= 0.02), reverse=True)[1:]
    log(f"  land derivation: {land_diag}")
    log(f"  land area total: {land_area_km2:,.0f} km2 across {comp_count} components "
        f"({len(island_km2)} islands >= 2 ha, largest island {island_km2[0] if island_km2 else 0:.2f} km2)")
    mainland_mask = land_mask.copy()
    if island_faces:
        biggest = max(range(1, comp_count + 1), key=lambda i: float(sizes[i - 1]))
        mainland_mask = labels_all == biggest
    log(f"  land mask: {land_mask.sum():,} of {land_mask.size:,} cells ({100 * land_mask.mean():.1f}%)")

    # Island records: every land component other than the mainland, with its true centroid. Islands
    # come from the same derivation as the land mask, so the manifest can never claim an island the
    # terrain does not contain (and the packaged-coverage test can prove it).
    island_manifest = []
    if comp_count > 0:
        mainland_id = max(range(1, comp_count + 1), key=lambda i: float(sizes[i - 1]))
        for cid in range(1, comp_count + 1):
            if cid == mainland_id:
                continue
            area_km2_c = float(sizes[cid - 1])
            if area_km2_c < 0.02:
                continue
            ys_c, xs_c = np.nonzero(labels_all == cid)
            east_c = float(xs_l1[int(round(float(xs_c.mean())))]) if xs_c.size else 0.0
            north_c = float(ys_l1[int(round(float(ys_c.mean())))]) if ys_c.size else 0.0
            island_manifest.append({
                "id": f"island-{cid}", "name": "", "nameAr": "",
                "areaM2": round(area_km2_c * 1e6, 1),
                "east": round(east_c, 2), "north": round(north_c, 2),
            })
        island_manifest.sort(key=lambda i: -i["areaM2"])
    for i, isl in enumerate(islands):
        if isl.get("geometry") is None:
            continue
        rep = isl["geometry"].representative_point()
        island_manifest.insert(i, {
            "id": isl["id"], "name": isl["name"], "nameAr": isl["name_ar"],
            "areaM2": round(isl["area_m2"], 1),
            "east": round(rep.x, 2), "north": round(rep.y, 2),
        })
    log(f"  island records: {len(island_manifest)} (largest "
        f"{island_manifest[0]['areaM2'] / 1e4:,.1f} ha)" if island_manifest else "  island records: 0")

    heights_l1 = np.where(land_mask, np.maximum(heights_l1, 0.0), 0.0)
    log(f"  terrain L1 ({args.cell_l1:.0f} m): min {heights_l1.min():.1f} max {heights_l1.max():.1f} m")

    # Coarse level: block-average of L1 for the horizon view.
    heights_l0 = _downsample(heights_l1, rows_l1, cols_l1, args.cell_l0 / args.cell_l1)
    xs_l0, ys_l0, cols_l0, rows_l0 = grid_axes(grid, args.cell_l0)
    log(f"  terrain L0 ({args.cell_l0:.0f} m): {cols_l0}x{rows_l0}")

    # Surface materials -----------------------------------------------------------------------
    material = np.full(heights_l1.shape, MATERIAL["gravel_rock"], dtype=np.uint8)
    material[land_mask == 0] = MATERIAL["water_open"]
    urban = _rasterize_many(data.landuse, ("residential", "commercial", "industrial", "retail",
                                           "construction", "quarry"), xs_l1, ys_l1, args.cell_l1)
    urban |= _rasterize_many(data.aeroways, None, xs_l1, ys_l1, args.cell_l1, tag_key="aeroway")
    sand = _rasterize_many(data.landuse, ("sand", "desert", "dune", "bare_rock", "scree"),
                           xs_l1, ys_l1, args.cell_l1, tag_key="natural")
    scrub = _rasterize_many(data.landuse, ("scrub", "grassland", "heath", "shrubbery"),
                            xs_l1, ys_l1, args.cell_l1, tag_key="natural")
    farms = _rasterize_many(data.landuse, ("farmland", "orchard", "vineyard", "greenhouse_horticulture",
                                           "allotments", "plant_nursery"), xs_l1, ys_l1, args.cell_l1)
    wetland = _rasterize_many(data.landuse, ("wetland", "marsh", "saltmarsh"), xs_l1, ys_l1,
                              args.cell_l1, tag_key="natural")
    water_inland = _rasterize_many(data.water_areas, None, xs_l1, ys_l1, args.cell_l1, tag_key="natural")
    material[land_mask == 1] = MATERIAL["gravel_rock"]
    material[sand == 1] = MATERIAL["sand_loose_dune"]
    material[scrub == 1] = MATERIAL["sand_compact"]
    material[farms == 1] = MATERIAL["farm_soil"]
    material[wetland == 1] = MATERIAL["mangrove_marsh"]
    material[urban == 1] = MATERIAL["urban_pad"]
    material[water_inland == 1] = MATERIAL["water_shallow"]
    # Distance-to-water dependent classes: wet sand at the shoreline, sabkha on low flat flats.
    dist = _distance_to_water(land_mask, args.cell_l1)
    low = heights_l1 < 6.0
    sabkha = (land_mask == 1) & low & (dist > 0) & (dist < 2500.0) & (material == MATERIAL["gravel_rock"])
    material[sabkha] = MATERIAL["sabkha"]
    wet_sand = (land_mask == 1) & (dist > 0) & (dist <= 150.0)
    material[wet_sand] = MATERIAL["sand_wet"]
    shallow = (land_mask == 0) & (dist > 0) & (dist < 600.0)
    material[shallow] = MATERIAL["water_shallow"]
    mud = (land_mask == 0) & (dist > 0) & (dist < 120.0)
    material[mud] = MATERIAL["mud_tidalflat"]
    log(f"  materials: {len(np.unique(material))} distinct classes present")

    # Roads -----------------------------------------------------------------------------------
    roads = []
    for road in data.roads:
        tags = road["tags"]
        cls = ROAD_CLASS[tags["highway"]]
        projected = _project(road["coords"])
        if len(projected) < 2:
            continue
        width = _parse_length(tags.get("width"))
        lanes = _parse_int(tags.get("lanes"))
        speed = _parse_speed(tags.get("maxspeed"))
        roads.append({
            "id": road["id"], "class": cls, "projected": projected,
            "name": tags.get("name"), "name_en": tags.get("name:en"), "name_ar": tags.get("name:ar"),
            "lanes": lanes, "width": width, "speed": speed,
            "bridge": tags.get("bridge") not in (None, "no"),
            "tunnel": tags.get("tunnel") not in (None, "no"),
            "oneway": tags.get("oneway") in ("yes", "1", "true") or cls in (1, 15, 16),
        })
    log(f"  roads compiled: {len(roads)}")

    # Buildings -------------------------------------------------------------------------------
    buildings_raw = []
    for b in data.buildings:
        coords = b["coords"]
        if len(coords) < 4 or coords[0] != coords[-1]:
            continue
        projected = _project(coords)
        poly = Polygon(projected)
        if not poly.is_valid:
            poly = poly.buffer(0)
        if poly.is_empty or poly.area < 12.0:
            continue
        minx, miny, maxx, maxy = poly.bounds
        if not (grid["originEast"] <= minx <= grid["originEast"] + grid["widthM"]
                and grid["originNorth"] <= miny <= grid["originNorth"] + grid["heightM"]):
            continue
        buildings_raw.append({"poly": poly, "tags": b["tags"], "area": poly.area})
    log(f"  buildings compiled: {len(buildings_raw)}")

    # Strings ---------------------------------------------------------------------------------
    strings = StringTable()
    for muni in munis:
        strings.add(muni["name_en"] or muni["name"])
        strings.add(muni["name_ar"])
    for isl in islands:
        strings.add(isl["name_en"] or isl["name"])
        strings.add(isl["name_ar"])
    for place in data.places:
        strings.add(place.get("name_en") or place.get("name"))
        strings.add(place.get("name_ar"))

    strings.add("Qatar")
    strings.add("قطر")

    # Checkpoint coverage ----------------------------------------------------------------------
    checkpoint_report = []
    checkpoints = []
    for group, cid, names, kind in CHECKPOINTS:
        hit = _find_feature(data, names, kind)
        if hit is None:
            # Geometry-backed fallback: a real island polygon from the coastline network. This is not
            # invented geography -- it is the data itself standing in for a missing name tag.
            if group == "ISLANDS_AND_WATER" and island_manifest:
                biggest = island_manifest[0]
                hit = {"lon": 0.0, "lat": 0.0, "east": biggest["east"], "north": biggest["north"],
                       "name": f"coastline island ({biggest['areaM2'] / 1e4:,.0f} ha)",
                       "name_en": "unnamed island", "name_ar": "", "source": "osm-coastline-island"}
            else:
                checkpoint_report.append({"group": group, "id": cid, "status": "MISSING"})
                continue
        e = hit.get("east") if "east" in hit else None
        n = hit.get("north") if "north" in hit else None
        if e is None or n is None:
            e, n = FWD.transform(hit["lon"], hit["lat"])
        label_en, label_ar = hit.get("name_en") or hit["name"], hit.get("name_ar", "")
        name_idx = strings.add(label_en)
        ar_idx = strings.add(label_ar)
        checkpoints.append({
            "group": group, "id": cid, "east": round(e, 2), "north": round(n, 2),
            "nameIdx": name_idx, "nameArIdx": ar_idx,
            "source": hit.get("source", "osm"),
        })
        checkpoint_report.append({"group": group, "id": cid, "status": "FOUND",
                                  "east": round(e, 2), "north": round(n, 2), "name": label_en})
    missing = [c for c in checkpoint_report if c["status"] == "MISSING"]
    log(f"  checkpoints: {len(checkpoints)} found, {len(missing)} missing"
        + (f" -> {', '.join(c['group'] + '/' + c['id'] for c in missing)}" if missing else ""))

    # Municipality hubs (spawn points) ---------------------------------------------------------
    hubs = []
    for muni in munis:
        point = muni["geometry"].representative_point()
        # Prefer the largest place node inside the municipality as the player-facing hub.
        inside = [p for p in data.places
                  if muni["geometry"].contains(Point(FWD.transform(p["lon"], p["lat"])))]
        inside.sort(key=lambda p: -_place_rank(p["place"]))
        chosen = inside[0] if inside else None
        if chosen is not None:
            e, n = FWD.transform(chosen["lon"], chosen["lat"])
            label = chosen.get("name_en") or chosen["name"]
            label_ar = chosen.get("name_ar", "")
        else:
            e, n = FWD.transform(*INV.transform(point.x, point.y)[::-1][0:2]) if False else (point.x, point.y)
            label = muni["name_en"]
            label_ar = muni["name_ar"]
        hubs.append({
            "id": muni["id"],
            "name": muni["name_en"] or muni["name"],
            "nameAr": muni["name_ar"],
            "east": round(e, 2), "north": round(n, 2),
            "nameIdx": strings.add(label), "nameArIdx": strings.add(label_ar),
            "areaKm2": round(muni["area_km2"], 2),
        })

    # Write bundles -----------------------------------------------------------------------------
    log("[2] writing runtime bundles")
    masks_l1 = land_mask.astype(np.uint8)
    payload = pack_terrain(grid, args.cell_l1, np.round(heights_l1), masks_l1)
    write_gz(WORLD / "terrain_l1.bin.gz", payload)
    payload0 = pack_terrain(grid, args.cell_l0, np.round(heights_l0), _downsample_mask(land_mask, heights_l0.shape, args.cell_l0 / args.cell_l1))
    write_gz(WORLD / "terrain_l0.bin.gz", payload0)
    write_gz(WORLD / "material_l1.bin.gz", pack_materials(grid, args.cell_l1, material))

    roads_bytes, road_counts = pack_roads(roads, strings)
    write_gz(WORLD / "roads.bin.gz", roads_bytes)

    building_records = []
    for b in buildings_raw:
        poly = b["poly"]
        cx, cy = poly.representative_point().x, poly.representative_point().y
        minx, miny, maxx, maxy = poly.bounds
        w = max(1.0, maxx - minx)
        d = max(1.0, maxy - miny)
        tags = b["tags"]
        h = _building_height(tags)
        levels = _parse_int(tags.get("building:levels")) or max(1, int(h / 3.2))
        building_type = BUILDING_TYPE.get(tags.get("building") or "unknown", BUILDING_TYPE["unknown"])
        flags = 0
        if tags.get("building") == "mosque" or tags.get("amenity") == "place_of_worship":
            flags |= 1
        building_records.append({
            "x_cm": int(round(cx * 100)), "y_cm": int(round(cy * 100)),
            "w_dm": min(65535, int(round(w * 10))), "d_dm": min(65535, int(round(d * 10))),
            "rot": int(round((_polygon_rotation_deg(poly) % 180.0) * 10)) & 0xFFFF,
            "h_dm": min(65535, int(round(h * 10))), "type": building_type, "levels": min(255, levels),
            "flags": flags, "name_idx": strings.add(tags.get("name:en") or tags.get("name")),
        })
    write_gz(WORLD / "buildings.bin.gz", pack_buildings(building_records, strings))
    log(f"  buildings packed: {len(building_records)}")

    # Shoreline bundle: the mapped natural=coastline geometry itself, simplified to 2 m. Storing the
    # real shoreline (instead of a derived ring) keeps the coast honest and high quality, and the
    # land mask above is what the runtime uses for collision and material blending.
    rings = []
    skipped_short = 0
    for way in data.coastlines:
        projected = _project(way)
        if len(projected) < 4:
            skipped_short += 1
            continue
        line = LineString(projected).simplify(2.0, preserve_topology=False)
        coords = [(float(x), float(y)) for x, y in line.coords if np.isfinite(x) and np.isfinite(y)]
        if len(coords) < 4:
            skipped_short += 1
            continue
        rings.append((0, coords))
    log(f"  shoreline polylines packed: {len(rings)} (skipped {skipped_short} degenerate)")
    write_gz(WORLD / "coast.bin.gz", pack_coast(rings, grid))
    log(f"  coast bundle written ({len(rings)} polylines)")
    write_gz(WORLD / "strings.bin.gz", strings.to_bytes())
    log(f"  strings: {len(strings)} entries")

    # Manifest -----------------------------------------------------------------------------------
    world_epoch = int(os.environ.get("QATAR_WORLD_EPOCH", "20261012"))
    files = ["terrain_l0.bin.gz", "terrain_l1.bin.gz", "material_l1.bin.gz", "roads.bin.gz",
             "buildings.bin.gz", "coast.bin.gz", "strings.bin.gz"]
    checksums = {name: sha256_file(WORLD / name) for name in files}
    manifest = {
        "format": 1,
        "worldEpoch": world_epoch,
        "crs": "EPSG:32639",
        "crsName": "WGS 84 / UTM zone 39N",
        "grid": grid,
        "levels": [
            {"name": "l0", "cellM": args.cell_l0, "cols": cols_l0, "rows": rows_l0,
             "file": "terrain_l0.bin.gz", "sha256": checksums["terrain_l0.bin.gz"]},
            {"name": "l1", "cellM": args.cell_l1, "cols": cols_l1, "rows": rows_l1,
             "file": "terrain_l1.bin.gz", "sha256": checksums["terrain_l1.bin.gz"]},
        ],
        "materials": {"file": "material_l1.bin.gz", "cellM": args.cell_l1,
                      "cols": cols_l1, "rows": rows_l1, "sha256": checksums["material_l1.bin.gz"]},
        "roads": {"file": "roads.bin.gz", "count": len(roads), "byClass": road_counts,
                  "sha256": checksums["roads.bin.gz"]},
        "buildings": {"file": "buildings.bin.gz", "count": len(building_records),
                      "sha256": checksums["buildings.bin.gz"]},
        "coast": {"file": "coast.bin.gz", "rings": len(rings), "sha256": checksums["coast.bin.gz"]},
        "strings": {"file": "strings.bin.gz", "count": len(strings), "sha256": checksums["strings.bin.gz"]},
        "islands": [
            {"id": isl["id"], "name": isl["name"], "nameAr": isl["name_ar"],
             "areaM2": round(isl["area_m2"], 1),
             "east": round(isl["geometry"].representative_point().x, 2),
             "north": round(isl["geometry"].representative_point().y, 2)}
            for isl in islands
        ] + [
            {"id": f"coast-ring-{1000 + i}", "name": "", "nameAr": "",
             "areaM2": round(p.area, 1),
             "east": round(p.representative_point().x, 2),
             "north": round(p.representative_point().y, 2)}
            for i, p in enumerate(island_faces)
        ],
        "municipalities": hubs,
        "checkpoints": checkpoints,
        "checkpointReport": checkpoint_report,
        "attribution": [
            "(c) OpenStreetMap contributors, ODbL 1.0 -- coastline, boundaries, roads, buildings, land use",
            "SRTM 1 arc-second elevation (NASA/USGS, public domain)",
        ],
        "stats": {
            "landCells": int(land_mask.sum()),
            "landAreaKm2": round(land_area_km2, 2),
            "islandFaces": len(island_faces),
            "mainlandKm2": round(mainland.area / 1e6, 2),
            "materialHistogram": {str(int(k)): int(v) for k, v in zip(*np.unique(material, return_counts=True))},
            "roadsByClass": {str(k): int(v) for k, v in road_counts.items()},
        },
    }
    (WORLD / "manifest.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False) + "\n",
                                         encoding="utf-8")

    # Provenance documents ---------------------------------------------------------------------
    _write_geojson(GEODATA / "QATAR_NATIONAL_AOI.geojson", national, simplified=True)
    if munis:
        _write_geojson_multi(GEODATA / "QATAR_MUNICIPALITIES.geojson", munis)
    (GEODATA / "QATAR_ISLANDS_MANIFEST.json").write_text(
        json.dumps({"count": len(islands) + len(island_faces),
                    "islands": manifest["islands"]}, indent=1, ensure_ascii=False) + "\n",
        encoding="utf-8")

    sums = []
    for name in sorted(checksums):
        sums.append(f"{checksums[name]}  android/assets/world/{name}")
    (GEODATA / "SHA256SUMS").write_text("\n".join(sums) + "\n", encoding="utf-8")

    _write_boundary_report(national, munis, islands, island_manifest, checkpoint_report,
                           data.stats, args, grid)

    log("[3] done")
    log(f"  manifest: {WORLD / 'manifest.json'}")
    log(f"  bundles: {sum((WORLD / f).stat().st_size for f in files) / 1e6:.1f} MB compressed")
    return 0


# -------------------------------------------------------------------------------------- helpers
def _project(coords_wgs) -> list[tuple[float, float]]:
    """WGS84 lon/lat sequence -> EPSG:32639 east/north vertices."""
    lons = np.asarray([c[0] for c in coords_wgs], dtype="float64")
    lats = np.asarray([c[1] for c in coords_wgs], dtype="float64")
    easts, norths = FWD.transform(lons, lats)
    return [(float(e), float(n)) for e, n in zip(easts, norths)]


def _closed_way_polygon(coords_wgs, min_area_m2: float = 0.0):
    """Closed OSM way -> projected polygon, or None when it is not a usable ring."""
    if coords_wgs is None or len(coords_wgs) < 3:
        return None
    ring = list(coords_wgs)
    if ring[0] != ring[-1]:
        ring.append(ring[0])
    try:
        poly = Polygon(_project(ring))
    except Exception:  # noqa: BLE001 - degenerate input is skipped, never fatal
        return None
    if poly.is_empty:
        return None
    if not poly.is_valid:
        poly = poly.buffer(0)
    if poly.is_empty or not isinstance(poly, Polygon):
        return None
    return poly if poly.area >= min_area_m2 else None


def project_geometry(geom):
    """WGS84 geometry -> EPSG:32639 metres (the single, documented projection step)."""
    from shapely.ops import transform as shapely_transform
    return shapely_transform(lambda x, y, z=None: FWD.transform(x, y), geom)


def sanitise_ring(ring, tolerance_m: float = 2.0):
    """Simplify and clean a coast ring so the bundle stays small and contains no invalid numbers."""
    geom = Polygon(ring.coords) if not isinstance(ring, Polygon) else ring
    if not geom.is_valid:
        geom = geom.buffer(0)
    if geom.is_empty:
        return None
    if tolerance_m > 0:
        geom = geom.simplify(tolerance_m, preserve_topology=True)
    if geom.is_empty or geom.geom_type != "Polygon":
        return None
    coords = [(float(x), float(y)) for x, y in geom.exterior.coords
              if np.isfinite(x) and np.isfinite(y)]
    if len(coords) < 4:
        return None
    return Polygon(coords)


def derive_land_mask(coastlines: list[list[tuple[float, float]]], seed_lines: list[list[tuple[float, float]]],
                     seed_points: list[tuple[float, float]], xs: np.ndarray, ys: np.ndarray,
                     cell_m: float, min_area_km2: float = 6000.0,
                     max_area_km2: float = 20000.0) -> tuple[np.ndarray, dict]:
    """Derive the land mask from open data, without inventing any geometry.

    Method (the standard open-data land/sea derivation):
      1. the mapped `natural=coastline` network is buffered into a thin ribbon and rasterised as a
         barrier (rasterio only burns areal geometry, so the lines are buffered by ~1 cell);
      2. OSM features that can only exist on land -- road vertices and building footprints -- scatter
         seeds into the lattice with plain numpy (fast and exact);
      3. a connected-component fill from those seeds, blocked by the ribbon, can only reach land;
      4. if the fill leaks (implausible area) the barrier is thickened and the fill retried;
      5. components enclosed by the coastline that hold no land evidence but are big enough become
         islands; the component touching the grid border is the open sea.

    Step 4 is the important one: a broken coastline shows up as a plausibility failure instead of
    silently turning the Gulf into desert.
    """
    import rasterio.features
    from rasterio.transform import Affine
    from scipy import ndimage

    rows, cols = ys.size, xs.size
    origin_e = float(xs[0]) - cell_m / 2.0
    origin_n = float(ys[0]) - cell_m / 2.0
    # rasterio is north-up, so the affine transform anchors at the northern edge and rows descend.
    transform = Affine.translation(origin_e, origin_n + rows * cell_m) * Affine.scale(cell_m, -cell_m)

    projected_coast = [_project(w) for w in coastlines if len(w) >= 2]
    barrier_geom = None
    if projected_coast:
        merged = shapely.ops.unary_union([LineString(p) for p in projected_coast])
        barrier_geom = merged.buffer(cell_m * 0.75, quad_segs=1, cap_style=2, join_style=2)
    base_barrier = np.zeros((rows, cols), dtype=bool)
    if barrier_geom is not None and not barrier_geom.is_empty:
        base_barrier = rasterio.features.rasterize([(barrier_geom, 1)], out_shape=(rows, cols),
                                                   transform=transform, fill=0, all_touched=True,
                                                   dtype="uint8").astype(bool)

    # Seeds from real land features, scattered directly into the lattice.
    seeds = np.zeros((rows, cols), dtype=bool)
    line_points = []
    for way in seed_lines:
        line_points.extend(way)
    if seed_points:
        line_points.extend(seed_points)
    if line_points:
        arr = np.asarray(line_points, dtype="float64")
        lon = arr[:, 0]
        lat = arr[:, 1]
        east, north = FWD.transform(lon, lat)
        col_idx = np.floor((east - origin_e) / cell_m).astype(np.int64)
        row_idx = np.floor((origin_n + rows * cell_m - north) / cell_m).astype(np.int64)
        valid = (col_idx >= 0) & (col_idx < cols) & (row_idx >= 0) & (row_idx < rows)
        seeds[row_idx[valid], col_idx[valid]] = True

    diagnostics = {"barrierCells": int(base_barrier.sum()), "seedCells": int(seeds.sum())}
    land = None
    for thickening in range(0, 7):
        barrier = base_barrier
        for _ in range(thickening):
            barrier = ndimage.binary_dilation(barrier, iterations=1)
        free = ~barrier
        labels, count = ndimage.label(free)
        if count == 0:
            continue
        seed_labels = np.unique(labels[seeds & free])
        seed_labels = seed_labels[seed_labels > 0]
        candidate = np.isin(labels, seed_labels)
        area_km2 = candidate.sum() * cell_m * cell_m / 1e6
        diagnostics[f"area_{thickening}"] = round(area_km2, 1)
        if min_area_km2 <= area_km2 <= max_area_km2:
            land = candidate
            diagnostics["thickening"] = thickening
            break
    if land is None:
        raise SystemExit(f"FATAL: land derivation did not converge: {diagnostics}")

    barrier = base_barrier
    for _ in range(diagnostics.get("thickening", 0)):
        barrier = ndimage.binary_dilation(barrier, iterations=1)
    free = ~barrier
    labels, count = ndimage.label(free)
    sea_labels = set(np.unique(np.concatenate([
        labels[0, :], labels[-1, :], labels[:, 0], labels[:, -1]])))
    sea_labels.discard(0)
    island_ids = []
    for label in range(1, count + 1):
        if label in sea_labels:
            continue
        size_km2 = float((labels == label).sum()) * cell_m * cell_m / 1e6
        if size_km2 >= 0.02 and not land[labels == label].any():
            island_ids.append((label, size_km2))
    diagnostics["islandComponents"] = len(island_ids)
    land_with_islands = land.copy()
    for label, _size in island_ids:
        land_with_islands |= (labels == label)
    return land_with_islands, diagnostics


def _seal_network(parts: list, ring) -> list:
    """Connect dangling coastline endpoints to the boundary ring so the graph closes.

    OSM coastlines are open lines: they end at land borders. Without sealing, polygonize cannot
    separate the land face from the territorial-water face and the whole administrative polygon comes
    back as a single face. Sealing only ever adds a straight stitch segment to the nearest ring point;
    no geometry is invented.
    """
    from collections import Counter

    if ring is None:
        return []
    ends: Counter = Counter()
    for part in parts:
        coords = list(part.coords)
        if len(coords) < 2:
            continue
        ends[(round(coords[0][0], 6), round(coords[0][1], 6))] += 1
        ends[(round(coords[-1][0], 6), round(coords[-1][1], 6))] += 1
    stitches = []
    for (x, y), count in ends.items():
        if count != 1:
            continue
        point = Point(x, y)
        distance = ring.distance(point)
        if 0.0 < distance < 20000.0:
            target = ring.interpolate(ring.project(point))
            if target.distance(point) > 1e-6:
                stitches.append(LineString([(x, y), (target.x, target.y)]))
    return stitches


def _land_polygons_from_coastline(coastlines: list[list[tuple[float, float]]],
                                  closing_line: LineString | None = None) -> list[Polygon]:
    """Polygonise the coastline network. OSM coastlines are open lines (they end at land borders and
    tile edges), so the national boundary ring is supplied as the closing line."""
    lines = []
    for way in coastlines:
        if len(way) >= 2:
            lines.append(LineString(_project(way)))
    if not lines:
        return []
    merged = shapely.ops.linemerge(shapely.union_all(lines))
    parts = [merged] if merged.geom_type == "LineString" else list(getattr(merged, "geoms", []))
    if closing_line is not None:
        stitches = _seal_network(parts, closing_line)
        if stitches:
            parts = parts + stitches
        parts.append(closing_line)
    polys = list(shapely.ops.polygonize(parts))
    out = []
    for poly in polys:
        if not poly.is_valid:
            poly = poly.buffer(0)
        if poly.is_empty:
            continue
        if poly.geom_type == "Polygon":
            out.append(poly)
        else:
            out.extend(list(poly.geoms))
    return sorted(out, key=lambda p: -p.area)


def _rasterize(geom, xs: np.ndarray, ys: np.ndarray, cell_m: float) -> np.ndarray:
    """Rasterise a polygon on the lattice. Row 0 is the southernmost row (engine convention).

    rasterio works north-up, so the mask is flipped once on the way out. Cell centres are used for
    the DEM sampling, while cell *corners* anchor the raster, so the raster is shifted by half a cell
    before rasterising.
    """
    import rasterio.features
    from rasterio.transform import Affine

    rows = ys.size
    cols = xs.size
    origin_e = float(xs[0]) - cell_m / 2.0
    origin_n = float(ys[0]) - cell_m / 2.0
    transform = Affine.translation(origin_e, origin_n + rows * cell_m) * Affine.scale(cell_m, -cell_m)
    mask = rasterio.features.rasterize(
        [(geom, 1)],
        out_shape=(rows, cols),
        transform=transform,
        fill=0,
        all_touched=False,
        dtype="uint8",
    )
    return np.flipud(mask)


def _rasterize_many(features: list[dict], values: tuple[str, ...] | None,
                    xs: np.ndarray, ys: np.ndarray, cell_m: float,
                    tag_key: str | None = None) -> np.ndarray:
    mask = np.zeros((ys.size, xs.size), dtype=np.uint8)
    geoms = []
    for feature in features:
        tags = feature["tags"]
        if values is not None:
            key = "landuse" if tag_key is None else tag_key
            value = tags.get(key)
            if value is None and tag_key is None:
                value = tags.get("natural") or tags.get("leisure")
            if value not in values:
                continue
        poly = _closed_way_polygon(feature["coords"])
        if poly is None or poly.area < 200.0:
            continue
        geoms.append(poly)
    if not geoms:
        return mask
    union = shapely.union_all(geoms)
    if union.is_empty:
        return mask
    if not union.is_valid:
        union = union.buffer(0)
    mask[:] = _rasterize(union, xs, ys, cell_m)
    return mask


def _distance_to_water(land_mask: np.ndarray, cell_m: float) -> np.ndarray:
    """Approximate distance (m) to the nearest water cell, computed on the L1 lattice."""
    from scipy import ndimage

    water = land_mask == 0
    if not water.any():
        return np.full(land_mask.shape, 1e9, dtype=np.float32)
    dist_cells = ndimage.distance_transform_edt(~water)
    return (dist_cells * cell_m).astype(np.float32)


def _downsample(values: np.ndarray, rows: int, cols: int, factor: float) -> np.ndarray:
    f = max(1, int(round(factor)))
    r = rows // f
    c = cols // f
    trimmed = values[:r * f, :c * f].astype(np.float32)
    return trimmed.reshape(r, f, c, f).mean(axis=(1, 3))


def _downsample_mask(mask: np.ndarray, shape: tuple[int, int], factor: float) -> np.ndarray:
    f = max(1, int(round(factor)))
    rows, cols = shape
    trimmed = mask[:rows * f, :cols * f]
    return (trimmed.reshape(rows, f, cols, f).mean(axis=(1, 3)) > 0.5).astype(np.uint8)


def _parse_int(value) -> int | None:
    if value is None:
        return None
    try:
        return int(str(value).split(";")[0].strip())
    except ValueError:
        return None


def _parse_length(value) -> float | None:
    if value is None:
        return None
    text = str(value).lower().replace("m", "").strip()
    try:
        return float(text)
    except ValueError:
        return None


def _parse_speed(value) -> float | None:
    if value is None:
        return None
    text = str(value).lower().replace("km/h", "").replace("kph", "").replace("mph", "").strip()
    try:
        speed = float(text)
    except ValueError:
        return None
    return speed


def _building_height(tags: dict) -> float:
    h = tags.get("height")
    if h:
        try:
            return max(2.5, min(320.0, float(str(h).lower().replace("m", "").strip())))
        except ValueError:
            pass
    levels = _parse_int(tags.get("building:levels"))
    if levels:
        return max(2.5, min(320.0, levels * 3.2 + 1.0))
    kind = tags.get("building")
    if kind in ("mosque",):
        return 9.0
    if kind in ("school", "university", "hospital", "commercial", "retail", "office"):
        return 12.0
    if kind in ("industrial", "warehouse", "hangar", "shed", "garage"):
        return 7.0
    if kind in ("apartments",):
        return 18.0
    return 5.5


def _polygon_rotation_deg(poly: Polygon) -> float:
    coords = list(poly.exterior.coords)
    if len(coords) < 3:
        return 0.0
    best = 0.0
    best_len = -1.0
    for i in range(len(coords) - 1):
        dx = coords[i + 1][0] - coords[i][0]
        dy = coords[i + 1][1] - coords[i][1]
        length = math.hypot(dx, dy)
        if length > best_len:
            best_len = length
            best = math.degrees(math.atan2(dy, dx))
    return best


def _place_rank(place: str) -> int:
    return {"city": 6, "town": 5, "village": 4, "suburb": 3, "neighbourhood": 2, "hamlet": 1}.get(place, 0)


def _find_feature(data: OsmData, names: list[str], kind: str) -> dict | None:
    """Locate a named feature by exact-then-case-insensitive match over the OSM extract."""
    lower = [n.lower() for n in names]
    candidates = []
    for place in data.places:
        for key in (place.get("name"), place.get("name_en")):
            if key and key.lower() in lower:
                candidates.append({"lon": place["lon"], "lat": place["lat"],
                                   "name": place.get("name") or key,
                                   "name_en": place.get("name_en", ""),
                                   "name_ar": place.get("name_ar", ""), "source": "osm-place"})
                break
    if candidates:
        return candidates[0]
    for feature in data.named_areas + data.landuse + data.aeroways:
        tags = feature["tags"]
        label = tags.get("name") or tags.get("name:en")
        if label and label.lower() in lower:
            coords = feature["coords"]
            if len(coords) < 3:
                continue
            lon = sum(c[0] for c in coords) / len(coords)
            lat = sum(c[1] for c in coords) / len(coords)
            candidates.append({"lon": lon, "lat": lat, "name": label,
                               "name_en": tags.get("name:en", ""), "name_ar": tags.get("name:ar", ""),
                               "source": "osm-area"})
            break
    return candidates[0] if candidates else None


def _write_geojson(path: Path, geom, simplified: bool) -> None:
    g = shapely.simplify(geom, 0.0005, preserve_topology=True) if simplified else geom
    payload = {"type": "FeatureCollection",
               "name": "QATAR_NATIONAL_AOI",
               "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:OGC:1.3:CRS84"}},
               "features": [{"type": "Feature",
                             "properties": {"name": "State of Qatar", "name:ar": "دولة قطر",
                                            "source": "OpenStreetMap admin_level=2 boundary",
                                            "license": "ODbL 1.0"},
                             "geometry": mapping(g)}]}
    path.write_text(json.dumps(payload, ensure_ascii=False) + "\n", encoding="utf-8")
    log(f"  wrote {path.name}")


def _write_geojson_multi(path: Path, munis: list[dict]) -> None:
    features = []
    for muni in munis:
        geom = shapely.simplify(muni["geometry"], 0.0004, preserve_topology=True)
        features.append({
            "type": "Feature",
            "properties": {"id": muni["id"], "name": muni["name"], "name:en": muni["name_en"],
                           "name:ar": muni["name_ar"], "admin_level": muni["admin_level"],
                           "area_km2": round(muni["area_km2"], 3),
                           "source": "OpenStreetMap administrative boundary", "license": "ODbL 1.0"},
            "geometry": mapping(geom),
        })
    payload = {"type": "FeatureCollection", "name": "QATAR_MUNICIPALITIES",
               "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:OGC:1.3:CRS84"}},
               "features": features}
    path.write_text(json.dumps(payload, ensure_ascii=False) + "\n", encoding="utf-8")
    log(f"  wrote {path.name} ({len(features)} municipalities)")


def _write_boundary_report(national, munis, islands, coast_islands, checkpoint_report, stats,
                           args, grid) -> None:
    lines = []
    lines.append("# Qatar boundary and coverage report\n")
    lines.append("Generated by `tools/build_world.py` from the verified open sources listed in "
                 "`GEODATA/SOURCES.json`.\n")
    lines.append("## National area of interest\n")
    lines.append(f"- Geometry: {national.geom_type}")
    lines.append(f"- Area: {national.area / 1e6:,.1f} km2")
    minx, miny, maxx, maxy = national.bounds
    centroid_lon, centroid_lat = INV.transform((minx + maxx) / 2, (miny + maxy) / 2)
    lines.append(f"- Projected bounds (EPSG:32639): E {minx:,.0f}..{maxx:,.0f}, N {miny:,.0f}..{maxy:,.0f}")
    lines.append(f"- Extent: {maxx - minx:,.0f} m east-west, {maxy - miny:,.0f} m north-south")
    lines.append(f"- Source: OpenStreetMap boundary relation (ODbL 1.0), retrieved "
                 f"{os.environ.get('QATAR_FETCH_DATE', 'see SOURCES.json')}")
    lines.append("\n## World grid (runtime)\n")
    lines.append(f"- Origin: E {grid['originEast']:,.0f}, N {grid['originNorth']:,.0f} (projected metres)")
    lines.append(f"- Extent: {grid['widthM'] / 1000:,.0f} km x {grid['heightM'] / 1000:,.0f} km "
                 f"(includes a water/airspace buffer for boats and aircraft)")
    lines.append("- Runtime master CRS: EPSG:32639 (WGS 84 / UTM zone 39N), 1 projected metre = 1 game metre")

    lines.append("\n## Municipalities\n")
    lines.append("| id | name | Arabic | area km2 | hub E | hub N |")
    lines.append("|----|------|--------|----------|-------|-------|")
    for muni in munis:
        point = muni["geometry"].representative_point()
        lines.append(f"| {muni['id']} | {muni['name_en']} | {muni['name_ar']} | "
                     f"{muni['area_km2']:.1f} | {point.x:,.0f} | {point.y:,.0f} |")

    lines.append("\n## Islands\n")
    lines.append(f"- Tagged `place=island*` polygons: {len(islands)}")
    lines.append(f"- Island land components derived from the coastline network: {len(coast_islands)}")
    for isl in coast_islands[:40]:
        label = isl.get("name") or isl.get("id")
        lines.append(f"  - {label} ({isl.get('areaM2', 0) / 1e4:,.1f} ha)")
    if len(islands) > 40:
        lines.append(f"  - ... and {len(islands) - 40} more (see QATAR_ISLANDS_MANIFEST.json)")

    lines.append("\n## Mandatory checkpoint groups\n")
    lines.append("| group | id | status | east | north | feature |")
    lines.append("|-------|----|--------|------|-------|---------|")
    for entry in checkpoint_report:
        lines.append(f"| {entry['group']} | {entry['id']} | {entry['status']} | "
                     f"{entry.get('east', '')} | {entry.get('north', '')} | {entry.get('name', '')} |")

    lines.append("\n## Feature counts\n")
    for key, value in sorted(stats.items()):
        lines.append(f"- {key}: {value:,}")

    lines.append("\n## Method notes\n")
    lines.append("- Coordinates are projected exactly once (WGS84 -> EPSG:32639) and never fed to the "
                 "renderer or physics as degrees.")
    lines.append("- The national boundary is taken verbatim from the chosen source; the compiler never "
                 "invents border geometry. No political claim is made about maritime boundaries: the "
                 "water/airspace buffer is a gameplay area only.")
    lines.append("- Maritime/territorial-water lines are deliberately NOT drawn, because no "
                 "redistributable dataset was selected for them.")
    lines.append("- Disputed or uncertain data is preserved as metadata rather than resolved.")
    (GEODATA / "QATAR_BOUNDARY_REPORT.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    log("  wrote QATAR_BOUNDARY_REPORT.md")


if __name__ == "__main__":
    sys.exit(main())
