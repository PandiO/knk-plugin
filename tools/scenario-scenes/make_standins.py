#!/usr/bin/env python3
"""Writes the synthetic stand-in scenes of the NPC movement scenarios as WorldEdit schematics.

Implementation plan 4c.5 items 4 to 6 (PandiO/knk-workspace#621, link M1): the jagged straight, the doorway and the
plaza are synthetic scenes built from the plan's descriptions (the stair house is the developer's own schematic,
knk-stairhouse.schem). Each is written in the format WorldEdit 7.3+ saves (Sponge schematic version 3, gzipped NBT):
knk-paper/src/main/resources/scenario-scenes/<name>.schem, read by SceneSchematic.

Run from the knk-plugin root:  python3 tools/scenario-scenes/make_standins.py
The output is deterministic (no dates), so a rerun without changes gives the same bytes.

Scene coordinates are local: x, z 0..31 and y 0..23, the scenario box's own (ScenarioScene.SIZE and HEIGHT).
"""

import gzip
import io
import os
import struct
import sys

DATA_VERSION = 5023  # Minecraft 26.3 (Paper 26.3 build 205, the paper-boot server)
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "knk-paper", "src", "main", "resources", "scenario-scenes")

AIR = "minecraft:air"
STONE = "minecraft:stone"
WALL = "minecraft:stone_bricks"
FENCE = "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]"
LANTERN = "minecraft:lantern"  # hanging=false is its default (MockBukkit knows no lantern properties)
WELL = "minecraft:cobblestone"


def stairs(facing):
    return "minecraft:stone_brick_stairs[facing=%s,half=bottom,shape=straight,waterlogged=false]" % facing


class Scene:
    def __init__(self, width, height, length):
        self.w, self.h, self.l = width, height, length
        self.blocks = {}

    def set(self, x, y, z, state):
        if not (0 <= x < self.w and 0 <= y < self.h and 0 <= z < self.l):
            raise ValueError("(%d, %d, %d) is outside the scene" % (x, y, z))
        if state == AIR:
            self.blocks.pop((x, y, z), None)
        else:
            self.blocks[(x, y, z)] = state

    def fill(self, x1, y1, z1, x2, y2, z2, state):
        for x in range(min(x1, x2), max(x1, x2) + 1):
            for y in range(min(y1, y2), max(y1, y2) + 1):
                for z in range(min(z1, z2), max(z1, z2) + 1):
                    self.set(x, y, z, state)


# ==================== NBT (just what a schematic needs) ====================

def _name(out, name):
    data = name.encode("utf-8")
    out.write(struct.pack(">H", len(data)))
    out.write(data)


def _tag(out, tag_type, name, payload):
    out.write(bytes([tag_type]))
    _name(out, name)
    out.write(payload)


def nbt_int(name, value):
    return (3, name, struct.pack(">i", value))


def nbt_short(name, value):
    return (2, name, struct.pack(">h", value))


def nbt_bytes(name, data):
    return (7, name, struct.pack(">i", len(data)) + data)


def nbt_ints(name, values):
    return (11, name, struct.pack(">i", len(values)) + b"".join(struct.pack(">i", v) for v in values))


def nbt_string(name, value):
    data = value.encode("utf-8")
    return (8, name, struct.pack(">H", len(data)) + data)


def nbt_compound(name, children):
    out = io.BytesIO()
    for tag_type, child, payload in children:
        _tag(out, tag_type, child, payload)
    out.write(b"\x00")
    return (10, name, out.getvalue())


def nbt_empty_list(name):
    return (9, name, b"\x0a" + struct.pack(">i", 0))


def write_schematic(path, scene, world_origin, offset):
    """Sponge v3 as WorldEdit writes it: root "" { Schematic: { Version 3, ..., Offset = min - origin, Blocks } }."""
    palette = {AIR: 0}
    data = bytearray()
    for y in range(scene.h):
        for z in range(scene.l):
            for x in range(scene.w):
                state = scene.blocks.get((x, y, z), AIR)
                index = palette.setdefault(state, len(palette))
                while True:
                    b = index & 0x7F
                    index >>= 7
                    if index:
                        data.append(b | 0x80)
                    else:
                        data.append(b)
                        break
    schematic = nbt_compound("Schematic", [
        nbt_int("Version", 3),
        nbt_int("DataVersion", DATA_VERSION),
        nbt_compound("Metadata", [
            nbt_compound("WorldEdit", [
                nbt_string("Version", "knk stand-in"),
                nbt_ints("Origin", list(world_origin)),
            ]),
        ]),
        nbt_short("Width", scene.w),
        nbt_short("Height", scene.h),
        nbt_short("Length", scene.l),
        nbt_ints("Offset", list(offset)),
        nbt_compound("Blocks", [
            nbt_compound("Palette", [nbt_int(state, i) for state, i in sorted(palette.items(), key=lambda e: e[1])]),
            nbt_bytes("Data", bytes(data)),
            nbt_empty_list("BlockEntities"),
        ]),
    ])
    raw = io.BytesIO()
    _tag(raw, 10, "", nbt_compound("", [schematic])[2])
    with open(path, "wb") as f:
        # mtime 0: the same bytes on every run
        with gzip.GzipFile(fileobj=f, mode="wb", mtime=0) as gz:
            gz.write(raw.getvalue())
    return len(palette)


# ==================== the scenes ====================

def jagged_straight():
    """Plan 4c.5 item 4: a 3-wide straight along z (x 14..16, floor 1) with one-block notches in both walls every 2
    to 3 blocks, a lantern on a fence post in an east notch and a stair block in a west notch."""
    s = Scene(32, 8, 32)
    s.fill(0, 0, 0, 31, 1, 31, STONE)
    s.fill(13, 2, 0, 13, 4, 31, WALL)
    s.fill(17, 2, 0, 17, 4, 31, WALL)
    east = (4, 7, 9, 12, 15, 17, 20, 23, 25, 28)
    west = (3, 5, 8, 11, 13, 16, 19, 21, 24, 27)
    for z in east:
        s.fill(17, 2, z, 17, 4, z, AIR)
        s.fill(18, 2, z, 18, 4, z, WALL)
    for z in west:
        s.fill(13, 2, z, 13, 4, z, AIR)
        s.fill(12, 2, z, 12, 4, z, WALL)
    s.set(17, 2, 12, FENCE)                        # a lantern on a fence post, in the east notch at z 12
    s.set(17, 3, 12, LANTERN)
    s.set(13, 2, 21, stairs("east"))               # a stair block in the west notch at z 21
    return s, (0, 0, 0), (0, 0, 0)


def doorway():
    """Plan 4c.5 item 5: two 9 x 9 rooms (x 3..11 and 13..21, z 3..11, floor 1) and a one-block doorway between
    them at x 12, z 7, two blocks high."""
    s = Scene(32, 8, 32)
    s.fill(0, 0, 0, 31, 1, 31, STONE)
    s.fill(2, 2, 2, 22, 4, 2, WALL)
    s.fill(2, 2, 12, 22, 4, 12, WALL)
    s.fill(2, 2, 2, 2, 4, 12, WALL)
    s.fill(22, 2, 2, 22, 4, 12, WALL)
    s.fill(12, 2, 2, 12, 4, 12, WALL)
    s.set(12, 2, 7, AIR)
    s.set(12, 3, 7, AIR)
    return s, (0, 0, 0), (0, 0, 0)


def plaza():
    """Plan 4c.5 item 6: a 15 x 15 plaza (x, z 8..22, floor 1) with four 3-wide roads meeting it in the middle of
    each side and a 2 x 2 well (x, z 11..12) off-centre, on the chord from the west road to the north road."""
    s = Scene(32, 8, 32)
    s.fill(0, 0, 0, 31, 1, 31, STONE)
    s.fill(7, 2, 7, 23, 4, 7, WALL)
    s.fill(7, 2, 23, 23, 4, 23, WALL)
    s.fill(7, 2, 7, 7, 4, 23, WALL)
    s.fill(23, 2, 7, 23, 4, 23, WALL)
    for a in (14, 15, 16):                         # the four openings
        s.fill(a, 2, 7, a, 4, 7, AIR)
        s.fill(a, 2, 23, a, 4, 23, AIR)
        s.fill(7, 2, a, 7, 4, a, AIR)
        s.fill(23, 2, a, 23, 4, a, AIR)
    for lo, hi in ((0, 6), (24, 31)):              # the roads' walls
        s.fill(13, 2, lo, 13, 4, hi, WALL)
        s.fill(17, 2, lo, 17, 4, hi, WALL)
        s.fill(lo, 2, 13, hi, 4, 13, WALL)
        s.fill(lo, 2, 17, hi, 4, 17, WALL)
    s.fill(11, 2, 11, 12, 3, 12, WELL)
    return s, (0, 0, 0), (0, 0, 0)


SCENES = {
    "npc-jagged-straight": jagged_straight,
    "npc-doorway": doorway,
    "npc-plaza": plaza,
}


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, build in SCENES.items():
        scene, origin, offset = build()
        path = os.path.join(OUT, name + ".schem")
        n = write_schematic(path, scene, origin, offset)
        print("%s: %d x %d x %d, %d palette entries, %d bytes" % (path, scene.w, scene.h, scene.l, n,
                                                                 os.path.getsize(path)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
