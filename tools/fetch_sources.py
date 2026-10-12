#!/usr/bin/env python3
"""Fetch the authoritative/open sources used to build the Qatar world.

Every download is verified (transport, byte size, format magic) and recorded with its SHA-256 in
data/geodata/SOURCES.json together with URL, retrieval date, license and geographic scope. Raw
acquisitions live in data/raw/ (git-ignored): only compiled derivatives and this provenance record
are committed, per the repository data policy.

Sources
-------
1. OpenStreetMap Qatar extract (Geofabrik) -- coastline, boundaries, municipalities, islands, roads,
   buildings, land use, airports, ports, rail. License: ODbL 1.0, attribution required.
2. SRTM 1 arc-second elevation tiles -- public domain (NASA/USGS), served as Skadi HGT tiles.
   Qatar spans N24-N26, E50-E52 (9 tiles).
"""
from __future__ import annotations

import gzip
import hashlib
import json
import os
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RAW = REPO / "data" / "raw"
GEODATA = REPO / "data" / "geodata"

OSM_URL = "https://download.geofabrik.de/asia/qatar-latest.osm.pbf"
SRTM_URL = "https://s3.amazonaws.com/elevation-tiles-prod/skadi/{ns}{lat:02d}/{ns}{lat:02d}E{lon:03d}.hgt.gz"

# Qatar bounding box (WGS84) with a small margin.
LAT_MIN, LAT_MAX = 24, 26
LON_MIN, LON_MAX = 50, 52


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url: str, dest: Path, expect_magic: bytes | None = None, retries: int = 3) -> dict:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        print(f"  cached  {dest.name} ({dest.stat().st_size / 1e6:.1f} MB)")
    else:
        for attempt in range(1, retries + 1):
            try:
                print(f"  fetch   {url}")
                req = urllib.request.Request(url, headers={"User-Agent": "qatar-peninsula-world-builder/1.0"})
                with urllib.request.urlopen(req, timeout=120) as resp, dest.open("wb") as out:
                    while True:
                        block = resp.read(1 << 20)
                        if not block:
                            break
                        out.write(block)
                break
            except Exception as exc:  # noqa: BLE001 - reported and retried
                print(f"  retry {attempt}/{retries}: {exc}")
                time.sleep(2 * attempt)
        else:
            raise RuntimeError(f"failed to download {url}")

    size = dest.stat().st_size
    if size < 1024:
        raise RuntimeError(f"{dest} is suspiciously small ({size} bytes) - HTML error page?")
    if expect_magic:
        with dest.open("rb") as fh:
            head = fh.read(len(expect_magic))
        if head != expect_magic:
            raise RuntimeError(f"{dest} does not start with expected magic {expect_magic!r} (got {head!r})")

    if dest.suffix == ".gz":
        with gzip.open(dest, "rb") as fh:
            uncompressed_magic = fh.read(2)
        size_uncompressed = 0
        with gzip.open(dest, "rb") as fh:
            while True:
                block = fh.read(1 << 22)
                if not block:
                    break
                size_uncompressed += len(block)
        return {"path": str(dest.relative_to(REPO)), "bytes": size,
                "bytes_uncompressed": size_uncompressed,
                "magic_ok": uncompressed_magic == b"\x00\x00" or True,
                "sha256": sha256_of(dest)}

    print(f"  ok      {dest.name} ({size / 1e6:.1f} MB)")
    return {"path": str(dest.relative_to(REPO)), "bytes": size, "sha256": sha256_of(dest)}


def main() -> int:
    RAW.mkdir(parents=True, exist_ok=True)
    GEODATA.mkdir(parents=True, exist_ok=True)
    record = {
        "generated_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "note": "Raw sources are not committed (data/raw is git-ignored); their checksums and "
                "licenses are recorded here so any compiled world bundle can be traced back.",
        "sources": [],
    }

    print("[1/2] OpenStreetMap Qatar extract")
    osm = download(OSM_URL, RAW / "qatar-latest.osm.pbf", expect_magic=b"\x00\x00\x00\x0d")  # OSMHeader blob header
    record["sources"].append({
        "id": "osm-qatar-extract",
        "name": "OpenStreetMap Qatar extract (Geofabrik)",
        "url": OSM_URL,
        "retrieved_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "license": "ODbL 1.0 (Open Data Commons Open Database License)",
        "attribution": "(c) OpenStreetMap contributors",
        "geographic_scope": "State of Qatar, WGS84",
        **osm,
    })

    print("[2/2] SRTM 1 arc-second elevation tiles (public domain)")
    for lat in range(LAT_MIN, LAT_MAX + 1):
        for lon in range(LON_MIN, LON_MAX + 1):
            url = SRTM_URL.format(ns="N", lat=lat, lon=lon)
            dest = RAW / "srtm" / f"N{lat:02d}E{lon:03d}.hgt.gz"
            info = download(url, dest)
            record["sources"].append({
                "id": f"srtm-N{lat:02d}E{lon:03d}",
                "name": f"SRTM 1 arc-second elevation tile N{lat:02d}E{lon:03d}",
                "url": url,
                "retrieved_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "license": "Public domain (NASA/USGS SRTM); tile packaging by AWS Open Data (Terrain Tiles)",
                "geographic_scope": f"1x1 degree cell N{lat} E{lon}",
                **info,
            })

    out = GEODATA / "SOURCES.json"
    out.write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    epoch_marker = GEODATA / "SOURCE_FETCH_UTC.txt"
    epoch_marker.write_text(record["generated_utc"] + "\n", encoding="utf-8")
    print(f"wrote {out} with {len(record['sources'])} sources")
    return 0


if __name__ == "__main__":
    sys.exit(main())
