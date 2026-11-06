"""Outline sheet for a tree icon atlas: one white ring per icon row that hugs the icon's shape.

The tree screens tint it (cyan pulse for search matches, cream for hover) with drawTexture's
colour argument, so the highlight follows the node's outline instead of a box around it.

Input atlas: 3 columns (taken / can take / locked) of 21x17 cells, one row per icon.
Output: one column of 25x21 cells (2 px padding), ring 1 fully opaque, ring 2 faint.

    python scripts/node_glow.py src/main/resources/assets/ancientsmod/textures/gui/hunter/nodes.png \
        src/main/resources/assets/ancientsmod/textures/gui/hunter/nodes_glow.png
"""
import sys
from PIL import Image

W, H, PAD = 21, 17, 2
GW, GH = W + 2 * PAD, H + 2 * PAD


def dilate(mask, diag):
    out = set(mask)
    for x, y in mask:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                if (dx or dy) and (diag or dx == 0 or dy == 0):
                    out.add((x + dx, y + dy))
    return out


def main(src, dst):
    atlas = Image.open(src).convert("RGBA")
    rows = atlas.height // H
    out = Image.new("RGBA", (GW, GH * rows), (0, 0, 0, 0))
    px = out.load()
    for r in range(rows):
        mask = set()
        for col in range(3):
            cell = atlas.crop((col * W, r * H, (col + 1) * W, (r + 1) * H)).load()
            for x in range(W):
                for y in range(H):
                    if cell[x, y][3] > 40:
                        mask.add((x + PAD, y + PAD))
        ring1 = dilate(mask, False) - mask
        inner = mask | ring1
        ring2 = dilate(inner, True) - inner
        for (x, y), a in [(p, 255) for p in ring1] + [(p, 90) for p in ring2]:
            if 0 <= x < GW and 0 <= y < GH:
                px[x, r * GH + y] = (255, 255, 255, a)
    out.save(dst)
    print(f"wrote {dst}: {rows} rows of {GW}x{GH}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
