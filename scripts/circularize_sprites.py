"""Post-process sprites for PoE-style circular nodes.

Two outputs:
  1. Each sprite is masked to a circle — anything outside the inscribed
     disc becomes alpha=0 so the gem reads as a round disc not a square.
  2. A new `node_border.png` is generated procedurally — transparent
     interior, a thin grayscale ring near the edge — designed to be
     branch-tinted at render time so each node gets a coloured PoE-style
     halo around the gem.

Run from project root:
    python scripts/circularize_sprites.py
"""
from __future__ import annotations

import math
import os

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter


ASSETS = "src/main/resources/assets/prisonsmod/textures/gui/skilltree"
SIZE = 256


def circular_mask(size: int, inset_px: int = 4, feather_px: int = 2) -> Image.Image:
    """White disc, black outside, with a soft-edge feather."""
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((inset_px, inset_px, size - 1 - inset_px, size - 1 - inset_px), fill=255)
    if feather_px > 0:
        mask = mask.filter(ImageFilter.GaussianBlur(feather_px))
    return mask


def mask_sprite_to_circle(in_path: str, out_path: str) -> None:
    img = Image.open(in_path).convert("RGBA")
    if img.size != (SIZE, SIZE):
        img = img.resize((SIZE, SIZE), Image.LANCZOS)

    # 1. Crop to bounding box of the visible (non-transparent) content so
    #    the gem fills its sprite canvas instead of sitting in the middle
    #    with empty padding — that padding was making it look small inside
    #    the ring border at render time.
    bbox = img.split()[3].getbbox()
    if bbox is not None:
        # Make the crop square so the gem stays centred — pick the longer
        # axis and inflate the shorter one.
        cx = (bbox[0] + bbox[2]) // 2
        cy = (bbox[1] + bbox[3]) // 2
        half = max(bbox[2] - bbox[0], bbox[3] - bbox[1]) // 2 + 2
        sq = (max(0, cx - half), max(0, cy - half),
              min(SIZE, cx + half), min(SIZE, cy + half))
        img = img.crop(sq).resize((SIZE, SIZE), Image.LANCZOS)

    # 2. Apply the circular mask AND the existing alpha so internal
    #    transparency is preserved and the square corners are knocked out.
    mask = circular_mask(SIZE, inset_px=2, feather_px=2)
    existing_alpha = img.split()[3]
    new_alpha = ImageChops.multiply(existing_alpha, mask)
    img.putalpha(new_alpha)
    img.save(out_path, "PNG", optimize=True)
    print(f"circularised {os.path.basename(out_path)}")


def build_node_border(out_path: str) -> None:
    """Thin grayscale ring near the outer edge of a transparent canvas.

    Drawn as the difference between an outer filled disc and a slightly
    smaller inner disc, then gamma-corrected so the ring reads at small
    sizes. Centre is fully transparent. Designed to be tinted at render
    time (ARGB multiply) so each node's ring picks up its branch hue.
    """
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    # Outer disc — uniform white. We'll subtract a smaller disc to make
    # a ring.
    outer_inset = 2
    ring_thickness = 12
    outer_box = (outer_inset, outer_inset, SIZE - 1 - outer_inset, SIZE - 1 - outer_inset)
    inner_box = (outer_inset + ring_thickness, outer_inset + ring_thickness,
                 SIZE - 1 - outer_inset - ring_thickness,
                 SIZE - 1 - outer_inset - ring_thickness)
    draw.ellipse(outer_box, fill=(255, 255, 255, 255))
    draw.ellipse(inner_box, fill=(0, 0, 0, 0))
    # Soft feather on the alpha so the ring doesn't look stair-stepped
    # at small render sizes.
    alpha = img.split()[3].filter(ImageFilter.GaussianBlur(1.5))
    img.putalpha(alpha)
    img.save(out_path, "PNG", optimize=True)
    print(f"generated  {os.path.basename(out_path)}")


def main() -> None:
    here = os.path.dirname(os.path.abspath(__file__))
    proj = os.path.dirname(here)
    folder = os.path.join(proj, ASSETS).replace("\\", "/")
    targets = [
        "node_base_template.png",
        "node_notable_template.png",
        "node_grand_template.png",
        "node_gate.png",
    ]
    for name in targets:
        p = os.path.join(folder, name)
        if os.path.isfile(p):
            mask_sprite_to_circle(p, p)
    build_node_border(os.path.join(folder, "node_border.png"))


if __name__ == "__main__":
    main()
