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
CHARS += list(range(0x06D4, 0x06D6))
CHARS += list(range(0xFB50, 0xFB52))          # alef wasla
CHARS += list(range(0xFE70, 0xFF00))          # presentation forms A/B


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
            glyphs.append({"id": code, "w": 0, "h": 0, "adv": advance, "img": None,
                           "xoff": 0, "yoff": 0})
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

    atlas = Image.new("L", (atlas_w, atlas_h), 0)
    for g, spot in zip(glyphs, placements):
        if g["img"] is None or spot is None:
            continue
        atlas.paste(g["img"], spot)

    png_path = out_base.with_suffix(".png")
    fnt_path = out_base.with_suffix(".fnt")
    # libGDX expects the page file name to sit next to the .fnt.
    atlas.point(lambda v: v).save(png_path)

    lines = [
        f'info face="{ttf.stem}" size={size_px} bold=0 italic=0 charset="" unicode=1 stretchH=100 '
        f'smooth=1 aa=1 padding=0,0,0,0 spacing=1,1',
        f'common lineHeight={line_height} base={ascent} scaleW={atlas_w} scaleH={atlas_h} pages=1 '
        f'packed=0',
        f'page id=0 file="{png_path.name}"',
        f'chars count={sum(1 for g in glyphs if g["img"] is not None)}',
        'kernings count=0',
    ]
    for g, spot in zip(glyphs, placements):
        if g["img"] is None or spot is None:
            continue
        lines.append(
            f'char id={g["id"]} x={spot[0]} y={spot[1]} width={g["w"]} height={g["h"]} '
            f'xoffset={g["xoff"]} yoffset={g["yoff"]} xadvance={round(g["adv"])} page=0 chnl=15'
        )
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
