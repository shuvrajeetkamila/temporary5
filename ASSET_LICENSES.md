# Asset licences

All assets in this repository are either produced inside the repository, generated deterministically
by `tools/`, or included from a source whose licence permits redistribution. No proprietary asset is
bundled, at any resolution, in any form.

## Typefaces

**Amiri** — `assets/source/fonts/Amiri-Regular.ttf`, compiled at build time by `tools/make_font.py`
into the bitmap fonts `android/assets/fonts/hud_small.{fnt,png}` and `hud_large.{fnt,png}` (a stand-in
until the runtime loads the bitmap font directly).

* Copyright © 2010–2024 Khaled Hosny, the Amiri project contributors.
* Licensed under the SIL Open Font License, Version 1.1.
* Full licence text is bundled in the APK: `android/assets/licenses/Amiri-OFL.txt`.
* Reserved Font Name: **Amiri**. The compiled `.fnt`/`.png` pair is a derived work distributed under
  the same licence and does not use the Reserved Font Name as a font name.

## Generated geometry, textures and data

Everything below is generated from source data listed in `DATA_SOURCES.md`; no third party asset file
is redistributed.

* Terrain heightfield, land mask and material grid — derived from public-domain SRTM elevation and
  ODbL OpenStreetMap coastline/land-use, compiled by `tools/build_world.py`.
* Roads, buildings, checkpoints, municipality and island records — ODbL OpenStreetMap, same compiler.
* String table (`QTS1`) — includes Arabic and English names authored for this project, plus
  OpenStreetMap place names.
* Shaders (`core/src/main/java/ai/arena/qatar/render/ShaderSources.java`), colours, palettes and all
  code — original work in this repository.
* Application icon and launcher resources — original work in this repository.
* No audio is shipped in this build.

## Engine

libGDX 1.12.1, Apache License 2.0, consumed as a normal Gradle dependency. The Android backend
(`gdx-bullet`, `gdx-backend-android`) carries the same licence. Their licence texts are a dependency
concern handled by the standard Gradle dependency report; nothing proprietary is linked.

## Redistribution rule for contributors

`tools/validate_apk.sh` fails the build if the packaged world bundle is missing, if its per-file
SHA-256 digests do not match `manifest.json`, or if the APK requests the `INTERNET` permission. Any
asset added to this project must be traceable to a licence that permits redistribution; if a licence
cannot be produced, the asset does not go in.
