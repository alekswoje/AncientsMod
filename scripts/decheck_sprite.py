"""Convert an AI-Studio-generated JPG sprite into a transparent PNG.

The model paints either a checker pattern or a solid colour to "represent"
transparency. Both behave the same way for our purposes: the corners
are some background colour and we want to flood-fill everything reachable
from a corner that matches that colour (within a tolerance) into alpha=0.

Usage:
    python decheck_sprite.py <input.jpg> <output.png>

Tunables:
    TOLERANCE       — per-channel RGB tolerance when matching corner colour.
                       40 captures both the bright/light checker squares and
                       the slight variation in flat-colour backdrops without
                       eating into the gem.
    EDGE_FEATHER_PX — gaussian blur radius applied to the alpha mask edge so
                       the cutout isn't jaggy. 1-2 looks good at 1024².
"""
from __future__ import annotations

import sys
from collections import deque

import numpy as np
from PIL import Image, ImageFilter


TOLERANCE = 40
EDGE_FEATHER_PX = 1


def _sample_corner_colors(arr: np.ndarray) -> list[tuple[int, int, int]]:
    """Sample a small region near each corner and return the median colour."""
    h, w = arr.shape[:2]
    pad = 8
    boxes = [
        arr[0:pad,       0:pad,       :3],
        arr[0:pad,       w - pad:w,   :3],
        arr[h - pad:h,   0:pad,       :3],
        arr[h - pad:h,   w - pad:w,   :3],
    ]
    out: list[tuple[int, int, int]] = []
    for box in boxes:
        rgb = box.reshape(-1, 3)
        med = np.median(rgb, axis=0).astype(int)
        out.append((int(med[0]), int(med[1]), int(med[2])))
    return out


def decheck(in_path: str, out_path: str) -> None:
    img = Image.open(in_path).convert("RGBA")
    arr = np.array(img)
    h, w = arr.shape[:2]
    corners = _sample_corner_colors(arr)

    visited = np.zeros((h, w), dtype=bool)
    queue: deque[tuple[int, int]] = deque()
    for sy, sx in [(0, 0), (0, w - 1), (h - 1, 0), (h - 1, w - 1)]:
        queue.append((sy, sx))

    rgb = arr[:, :, :3].astype(np.int16)

    while queue:
        y, x = queue.popleft()
        if y < 0 or y >= h or x < 0 or x >= w:
            continue
        if visited[y, x]:
            continue
        # Must be within tolerance of AT LEAST ONE corner colour. This lets
        # the checker pattern (alternating two near-white colours) all match
        # since both checker tiles are within tolerance of each other.
        px = rgb[y, x]
        matched = False
        for c in corners:
            if (abs(px[0] - c[0]) <= TOLERANCE
                    and abs(px[1] - c[1]) <= TOLERANCE
                    and abs(px[2] - c[2]) <= TOLERANCE):
                matched = True
                break
        if not matched:
            continue
        visited[y, x] = True
        queue.append((y + 1, x))
        queue.append((y - 1, x))
        queue.append((y, x + 1))
        queue.append((y, x - 1))

    arr[visited, 3] = 0
    out = Image.fromarray(arr)

    if EDGE_FEATHER_PX > 0:
        alpha = out.split()[3].filter(ImageFilter.GaussianBlur(EDGE_FEATHER_PX))
        out.putalpha(alpha)

    out.save(out_path, "PNG", optimize=True)
    transparent = int(visited.sum())
    print(f"in:  {in_path}")
    print(f"out: {out_path}")
    print(f"corners: {corners}")
    print(f"transparent pixels: {transparent}/{h*w} ({transparent/(h*w):.1%})")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("usage: python decheck_sprite.py <in.jpg> <out.png>", file=sys.stderr)
        sys.exit(2)
    decheck(sys.argv[1], sys.argv[2])
