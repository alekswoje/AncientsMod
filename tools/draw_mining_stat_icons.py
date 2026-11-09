"""Draws gui/mining/stats.png: one 9x9 icon per MiningStat ordinal, same style as gui/hunter/stats.png.

Each grid is the fill only; the dark outline is added around every filled pixel automatically.
Run from the repo root: python tools/draw_mining_stat_icons.py [preview.png]
"""
import sys
from PIL import Image

OUT = "src/main/resources/assets/ancientsmod/textures/gui/mining/stats.png"
OUTLINE = (10, 7, 6, 200)

GOLD = {"h": (255, 232, 140), "m": (236, 184, 64), "d": (150, 96, 20), "w": (255, 255, 230)}
RED = {"h": (255, 170, 140), "m": (238, 96, 74), "d": (150, 36, 32), "w": (255, 236, 200)}
BLUE = {"h": (176, 208, 255), "m": (96, 146, 240), "d": (40, 70, 170), "w": (240, 248, 255)}
CYAN = {"h": (190, 255, 248), "m": (96, 220, 214), "d": (30, 128, 132), "w": (255, 255, 255)}
PURPLE = {"h": (230, 190, 255), "m": (176, 116, 240), "d": (96, 46, 160), "w": (255, 245, 255)}
GREEN = {"h": (190, 250, 180), "m": (110, 210, 120), "d": (40, 120, 56), "p": (20, 60, 28)}
ROCK = {"R": (120, 104, 100), "r": (82, 68, 66), "s": (64, 52, 52)}
TRAIL = {"t": (255, 200, 90), "T": (255, 130, 60)}

ICONS = [
    # 0 Ore Gain: gold nugget
    ("""
.........
....hh...
...hhmd..
..hwhmmd.
.hhmmmdd.
.hmmmddd.
..mddd...
.........
.........""", GOLD),
    # 1 Energy Gain: lightning bolt
    ("""
.........
....hhhh.
...hhmm..
..hhmm...
.hhmmmmm.
....mmd..
...mmd...
..mdd....
..d......""", RED),
    # 2 XP Gain: orb
    ("""
.........
...mmm...
..hwhmm..
.mhhmmmd.
.mhmmmmd.
.mmmmmdd.
..mmmdd..
...ddd...
.........""", BLUE),
    # 3 Shard Find: crystal
    ("""
.........
....h....
...hhm...
..hwhmd..
..hhmmd..
..hhmmd..
..hmmdd..
...mdd...
....d....""", CYAN),
    # 4 Jewel Find: cut gem
    ("""
.........
.........
..hhhmm..
.hwhmmmd.
.hhhmmdd.
..hmmmd..
...mmd...
....d....
.........""", PURPLE),
    # 5 Proc Chance: die showing three
    ("""
.........
.hhhhhhm.
.hphhhmm.
.hhhhmmm.
.hhhpmmd.
.hhmmmdd.
.hmmmmpd.
.mmmdddd.
.........""", GREEN),
    # 6 Energy Capacity: charged battery
    ("""
.........
.........
.hhhhhd..
.hwwwrd..
.hwwwrdm.
.hwwwrdm.
.hwwwrd..
.mmmmdd..
.........""", {**RED, "r": (70, 20, 20)}),
    # 7 Extract Tax: coin with a minus
    ("""
.........
...hhm...
..hhmmm..
.hhmmmmd.
.hwwwwmd.
.hmmmmdd.
..mmmdd..
...mdd...
.........""", RED),
    # 8 Meteorite Ores: rock with gold, falling
    ("""
.........
.......t.
......tT.
.....tT..
..RRRT...
.RmRRr...
.RRrmr...
.rRrr....
..rs.....""", {**ROCK, **TRAIL, "m": GOLD["m"]}),
    # 9 Meteorite Energy: rock with a red glow, falling
    ("""
.........
.......t.
......tT.
.....tT..
..RRRT...
.RmRRr...
.RRrmr...
.rRrr....
..rs.....""", {**ROCK, **TRAIL, "m": (255, 110, 90)}),
    # 10 Mining Speed: pickaxe
    ("""
.........
..hhhhm..
.hmmmmhm.
.m...bhm.
....b.hm.
...b...m.
..b......
.b.......
.........""", {"h": (250, 246, 228), "m": (190, 184, 166), "b": (170, 112, 60)}),
]


def draw():
    n = len(ICONS)
    sheet = Image.new("RGBA", (9 * n, 9), (0, 0, 0, 0))
    for i, (grid, pal) in enumerate(ICONS):
        rows = grid.strip("\n").split("\n")
        assert len(rows) == 9 and all(len(r) == 9 for r in rows), f"icon {i} is not 9x9"
        filled = {(x, y) for y, r in enumerate(rows) for x, c in enumerate(r) if c != "."}
        for y in range(9):
            for x in range(9):
                if (x, y) in filled:
                    sheet.putpixel((i * 9 + x, y), pal[rows[y][x]] + (255,))
                elif any((x + dx, y + dy) in filled for dx in (-1, 0, 1) for dy in (-1, 0, 1)):
                    sheet.putpixel((i * 9 + x, y), OUTLINE)
    return sheet


if __name__ == "__main__":
    sheet = draw()
    sheet.save(OUT)
    if len(sys.argv) > 1:
        bg = Image.new("RGBA", sheet.size, (30, 12, 16, 255))
        bg.alpha_composite(sheet)
        bg.resize((sheet.width * 16, sheet.height * 16), Image.NEAREST).save(sys.argv[1])
