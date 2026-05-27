"""Convert an AI-Studio-generated JPG sprite into a transparent PNG.

The model paints a checker pattern to represent transparency. We flood-fill
from each corner with a brightness threshold so only the checker (and any
other near-pure-white background pixels reachable from a corner) gets
replaced with alpha=0. The actual gem stays opaque because its rim is dark
enough to block the flood.

Usage:
    python decheck_sprite.py <input.jpg> <output.png>

Tunables:
    THRESHOLD       — minimum brightness (0..255) considered "background".
                       170 catches both the white and light-gray checker
                       squares without eating into the gem's mid-tones.
    EDGE_FEATHER_PX — number of pixels to feather along the alpha edge to
                       avoid a hard jaggy cutout. 1-2 looks good at
                       1024x1024 source.
"""
from __future__ import annotations

import sys
from collections import deque

import numpy as np
from PIL import Image, ImageFilter


THRESHOLD = 170
EDGE_FEATHER_PX = 1


def decheck(in_path: str, out_path: str) -> None:
    img = Image.open(in_path).convert("RGBA")
    arr = np.array(img)
    h, w = arr.shape[:2]
    gray = arr[:, :, :3].mean(axis=2)

    visited = np.zeros((h, w), dtype=bool)
    queue: deque[tuple[int, int]] = deque()
    for sy, sx in [(0, 0), (0, w - 1), (h - 1, 0), (h - 1, w - 1)]:
        queue.append((sy, sx))

    while queue:
        y, x = queue.popleft()
        if y < 0 or y >= h or x < 0 or x >= w:
            continue
        if visited[y, x]:
            continue
        if gray[y, x] < THRESHOLD:
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
    print(f"transparent pixels: {transparent}/{h*w} ({transparent/(h*w):.1%})")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("usage: python decheck_sprite.py <in.jpg> <out.png>", file=sys.stderr)
        sys.exit(2)
    decheck(sys.argv[1], sys.argv[2])
