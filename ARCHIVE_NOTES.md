# ARCHIVE — QATAR: THE PENINSULA (milestone 1)

The previous project that lived in this repository has been **archived, not deleted**. Every file,
commit and build artefact is still on GitHub. This branch is now a blank slate for a new project.

## Where the old project is

| | |
|---|---|
| **Git tag** | [`qatar-peninsula-m1`](https://github.com/shuvrajeetkamila/temporary5/releases/tag/qatar-peninsula-m1) |
| **Archived commit** | `f23ccd99f90eadd3a153912102881ac4d0d0fdbc` (final commit of the project, all 164 files) |
| **Download source archive** | https://codeload.github.com/shuvrajeetkamila/temporary5/zip/refs/tags/qatar-peninsula-m1 |
| **Installable APK (14,148,317 bytes)** | https://github.com/shuvrajeetkamila/temporary5/releases/download/v0.1.0-m0/Qatar-The-Peninsula-v0.1.0-m0.apk |
| **APK SHA-256** | `701c7ffe767c3d723db829bc4331f8bee17267ef67847e90c9fa07c58eb5ef00` |
| **Delivery report** | inside the archive at `docs/MILESTONE_1_REPORT.md` |

The tag pins the commit, so the archive cannot disappear even if every branch is rewritten. No branch
was created for it (a tag needs none), and `main` was left untouched.

## What the archive contains

The whole project: the Android app and engine (`android/`, `core/`), the Python GIS/compiler pipeline
(`tools/`), the compiled world bundles (`android/assets/world/`, ≈12 MB), the fonts and licences
(`android/assets/`), CI workflows (`.github/workflows/`), documentation (`docs/`, `DATA_SOURCES.md`,
`ASSET_LICENSES.md`) and the CI evidence (`artifacts/`).

**Not included:** `data/raw/` (the downloaded OpenStreetMap PBF and SRTM tiles, ~hundreds of MB of
public upstream data). It is gitignored and fully reproducible from the archive: `tools/fetch_sources.py`
re-downloads exactly the same inputs and records their SHA-256 digests in `data/geodata/SOURCES.json`.

## How to restore it

Restore a single file, a folder, or the entire project into the current working tree:

```bash
git fetch --tags origin
git checkout qatar-peninsula-m1 -- .                    # whole project
git checkout qatar-peninsula-m1 -- tools/ docs/         # just part of it
git show qatar-peninsula-m1:tools/build_world.py        # or read a single file
```

Or unpack the downloaded zip (`codeload.github.com/.../zip/refs/tags/qatar-peninsula-m1`) anywhere.

If the tag is ever missing locally: `git fetch origin --tags`.

## Status of that project when archived

* CI green: 33/33 unit tests passing, `APK_VALIDATION validation=PASSED`, `QA_STATUS status=PASSED`
  (Build APK run `38271736697`), world data run `38267878702` green with
  `WORLD COVERAGE VALIDATION PASSED` (11,502 km² of land, 57 islands, 8 municipalities).
* It is a **milestone build**: the world and engine contracts are real and validated, but the
  renderer draws a single sky-and-ground pass, and the vehicle/human/traffic systems were never
  implemented. `docs/BUILD_SPEC.md` in the archive records exactly what was and was not done.
