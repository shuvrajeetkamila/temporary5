#!/usr/bin/env python3
"""Generate the runtime bitmap font atlases from a licensed TTF.

Why a generator: the game renders Arabic and English text with libGDX's BitmapFont, which needs a
glyph atlas. Generating it here (instead of shipping a proprietary .fnt) keeps the shipped art
original and traceable, and lets us include exactly the glyphs the Arabic reshaper can emit --
contextual presentation forms (U+FE70..U+FEFF), the lam-alef ligatures, harakat, Arabic-Indic
digits and ASCII.

The atlas is produced deterministically (fixed glyph order, fixed packing), so a font change shows
up as a clean diff.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

REPO = Path(__file__).resolve().parent.parent

# ASCII printable + Arabic presentation forms (what ArabicReshaper emits) + base Arabic letters
# (fallback when text has not been shaped) + Arabic-Indic digits + common punctuation.
CHARS = list(range(0x20, 0x7F))
CHARS += list(range(0x060C, 0x0610))          # comma, semicolon, question mark, etc.
CHARS += list(range(0x061B, 0x061D))
CHARS += list(range(0x0621, 0x063B))          # base Arabic letters
CHARS += list(range(0x0640, 0x0654))          # tatweel, letters, harakat
CHARS += list(range(0x0660, 0x066A))          # Arabic-Indic digits
CHARS += list(range(0x066A, 0x066E))
CHARS += list(range(0x0670, 0x0672))
CHARS += list(range(0x0679, 0x0680))
CHARS += list(range(0x06A0, 0x0700))          # the rest of the Arabic block: Persian letters (06CC),
                                              # the marks, and the extended Arabic-Indic digits
CHARS += list(range(0x0750, 0x0780))          # Arabic Supplement
CHARS += list(range(0x06D4, 0x06D6))
CHARS += list(range(0xFB50, 0xFB52))          # alef wasla
CHARS += list(range(0xFE70, 0xFF00))          # presentation forms A/B
# Punctuation that is not in ASCII and not Arabic: the copyright sign and degree mark the credits and
# the instruments use, and the dashes, ellipsis and quotes that arrive in OpenStreetMap names. Every one
# of these has a real glyph in Amiri; a character the typeface lacks would be a blank on screen, which is
# why the coverage test checks the shipped font against the strings the game prints.
CHARS += [0x00A9, 0x00B0, 0x00B1, 0x00B7, 0x00D7, 0x2013, 0x2014, 0x2018, 0x2019, 0x201C, 0x201D,
          0x2026, 0x2032, 0x2033]
# Latin letters with diacritics, for the names in the data that are transcribed rather than spelled in
# Arabic: "Musée", "Wādī", and nine such names in the compiled country. Amiri covers these two ranges
# completely, which is why they can be added wholesale -- a character the typeface lacks would come out
# of PIL as the notdef box, and the atlas would claim a glyph that draws as a box on screen.
CHARS += list(range(0x00C0, 0x0100))          # Latin-1 letters
CHARS += list(range(0x0100, 0x0180))          # Latin Extended-A


# The ranges above overlap (06A0..0700 contains 06D4..06D6, and so on). Duplicates would be packed
# twice and listed twice in the .fnt, so they are dropped here, keeping the first mention's order.
CHARS = list(dict.fromkeys(CHARS))


def build(ttf: Path, out_base: Path, size_px: int, padding: int = 1) -> dict:
    font = ImageFont.truetype(str(ttf), size_px)
    ascent, descent = font.getmetrics()
    line_height = ascent + descent

    glyphs = []
    for code in CHARS:
        ch = chr(code)
        try:
            bbox = font.getbbox(ch)
        except Exception:
            continue
        left, top, right, bottom = bbox
        w, h = max(0, right - left), max(0, bottom - top)
        advance = font.getlength(ch)
        if w == 0 or h == 0:
            # A character can have an advance but no ink: space is the obvious one. It still needs an
            # entry in the .fnt, because that is where libGDX reads the advance from -- without it,
            # "Where is the metro" renders as "Whereisthemetro". A 1x1 transparent pixel carries it.
            if advance > 0:
                glyphs.append({"id": code, "w": 1, "h": 1, "adv": advance,
                               "img": Image.new("L", (1, 1), 0), "xoff": 0, "yoff": 0})
            continue
        glyph_img = Image.new("L", (w, h), 0)
        draw = ImageDraw.Draw(glyph_img)
        draw.text((-left, -top), ch, font=font, fill=255)
        glyphs.append({"id": code, "w": w, "h": h, "adv": advance, "img": glyph_img,
                       "xoff": left, "yoff": top})

    # Shelf packing into a power-of-two atlas.
    atlas_w = 512
    x = padding
    y = padding
    shelf_h = 0
    placements = []
    for g in glyphs:
        if g["img"] is None:
            placements.append(None)
            continue
        if x + g["w"] + padding > atlas_w:
            x = padding
            y += shelf_h + padding
            shelf_h = 0
        placements.append((x, y))
        x += g["w"] + padding
        shelf_h = max(shelf_h, g["h"])
    atlas_h = y + shelf_h + padding
    # Round up to the next power of two for mipmap friendliness.
    pow2 = 1
    while pow2 < atlas_h:
        pow2 *= 2
    atlas_h = pow2
    while atlas_w < atlas_w:  # noqa - placeholder to keep the width fixed
        break

    # The atlas is written RGBA, with white where the glyph is and the coverage in the alpha channel.
    # libGDX's own font tool writes exactly this, and it is the shape a BitmapFont is built to consume:
    # the texture is multiplied by the vertex colour, so white-with-alpha tints correctly and anti-aliases
    # against whatever is behind it. The alternative -- an 8-bit greyscale page, which is what this
    # generator used to write -- only works if the decoder happens to map it to an alpha-only pixmap, and
    # a font that loads on one device and not another is a font nobody can debug from here.
    atlas = Image.new("RGBA", (atlas_w, atlas_h), (255, 255, 255, 0))
    for g, spot in zip(glyphs, placements):
        if g["img"] is None or spot is None:
            continue
        coverage = g["img"]
        tile = Image.new("RGBA", coverage.size, (255, 255, 255, 0))
        tile.putalpha(coverage)
        atlas.paste(tile, spot)          # replaces the region: the tile carries its own clear pixels

    png_path = out_base.with_suffix(".png")
    fnt_path = out_base.with_suffix(".fnt")
    # libGDX expects the page file name to sit next to the .fnt.
    atlas.save(png_path)

    # THE ORDER OF THESE SECTIONS IS PART OF THE FORMAT, and it cost a device build to learn it. libGDX
    # reads the file as a stream: after the page definitions it loops over lines, and it *stops at the
    # first line beginning "kernings"*. Writing "kernings count=0" before the character lines therefore
    # parses zero glyphs, and BitmapFont then dereferences the null that comes back from looking up its
    # fallback glyph: a NullPointerException, a font that never loads, and a HUD with no text on it.
    # Counted, not assumed: characters with neither ink nor advance are dropped, so the count is what is
    # actually written.
    lines = [
        f'info face="{ttf.stem}" size={size_px} bold=0 italic=0 charset="" unicode=1 stretchH=100 '
        f'smooth=1 aa=1 padding=0,0,0,0 spacing=1,1',
        f'common lineHeight={line_height} base={ascent} scaleW={atlas_w} scaleH={atlas_h} pages=1 '
        f'packed=0',
        f'page id=0 file="{png_path.name}"',
        f'chars count={sum(1 for g, spot in zip(glyphs, placements) if g["img"] is not None and spot is not None)}',
    ]
    for g, spot in zip(glyphs, placements):
        if g["img"] is None or spot is None:
            continue
        lines.append(
            f'char id={g["id"]} x={spot[0]} y={spot[1]} width={g["w"]} height={g["h"]} '
            f'xoffset={g["xoff"]} yoffset={g["yoff"]} xadvance={round(g["adv"])} page=0 chnl=15'
        )
    # Last, always: nothing may follow the first section the reader stops at.
    lines.append('kernings count=0')
    fnt_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    return {"ttf": str(ttf.relative_to(REPO)) if str(ttf).startswith(str(REPO)) else str(ttf),
            "sizePx": size_px, "glyphs": sum(1 for g in glyphs if g["img"] is not None),
            "atlas": f"{atlas_w}x{atlas_h}", "fnt": str(fnt_path.relative_to(REPO)),
            "png": str(png_path.relative_to(REPO)), "lineHeight": line_height, "base": ascent}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ttf", default=str(REPO / "assets" / "source" / "fonts" / "Amiri-Regular.ttf"))
    ap.add_argument("--out-dir", default=str(REPO / "android" / "assets" / "fonts"))
    args = ap.parse_args()

    ttf = Path(args.ttf)
    if not ttf.exists():
        raise SystemExit(f"missing font: {ttf}")

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    report = []
    for name, size in (("hud_small", 18), ("hud_large", 30)):
        info = build(ttf, out_dir / name, size)
        report.append(info)
        print(f"  {name}: {info['glyphs']} glyphs, atlas {info['atlas']}, lineHeight {info['lineHeight']}")

    (out_dir / "FONT_GENERATION.json").write_text(
        json.dumps({"generator": "tools/make_font.py", "fonts": report}, indent=2) + "\n",
        encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
