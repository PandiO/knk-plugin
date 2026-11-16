#!/usr/bin/env python3
"""Writes the sample NPC navigation snapshot the replay scenario loads (link B1, PandiO/knk-workspace#670).

A zip in the format /knk npc nav snapshot <route> writes (knk-core NavSnapshotZip, format 1), built from the
developer's stair house schematic instead of a live server, so the replay (knk-paper SnapshotReplay) runs in CI before
the developer's own snapshot of quarry-to-keep is committed:

1. world/region/r.2.-2.mca and r.2.-1.mca: the schematic's blocks at their real place (1407 49 -549 to 1418 79 -487),
   stone at y 46..48 under it and under the street east of it (1419..1431, -530..-510), as the stair house scene lays
   them (NpcMovementScene). Anvil format, zlib chunks, block_states with palette and packed indexes.
2. route.json: the scene's climb line (NpcMovementScene.CLIMB with link M1's line), from the street east of the house to
   the top hall. No stretches (a real snapshot has them).
3. domains.json: a town polygon, a district cuboid round the house and the stair house as a structure inside it (all
   entry allowed), and an open gate at the entrance.
4. settings.json: the developer's settings during the smoke test of 2026-11-16 (chain report decision 35.3).

Run from the knk-plugin root:  python3 tools/scenario-scenes/make_snapshot_sample.py
The output is deterministic (fixed times, sorted entries), so a rerun without changes gives the same bytes.
"""

import gzip
import io
import json
import math
import os
import struct
import sys
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
SCHEMATIC = os.path.join(ROOT, "knk-paper", "src", "main", "resources", "scenario-scenes", "knk-stairhouse.schem")
OUT = os.path.join(ROOT, "knk-paper", "src", "main", "resources", "scenario-snapshots", "stairhouse-sample.zip")
DATA_VERSION = 5023  # Minecraft 26.3, as make_standins.py
FIXED_TIME = (2026, 11, 16, 0, 0, 0)

# the climb line (floor blocks; the line runs through their centres), as NpcMovementScene.climbLine()
M1_LINE = [(1413.5, 48, -513.5), (1413.5, 48, -509.5), (1413.0, 48, -507.5), (1412.5, 48, -505.0),
           (1412.5, 48, -502.5), (1412.5, 48, -500.5), (1412.5, 48, -499.5), (1412.5, 49, -498.5),
           (1412.5, 49, -496.5), (1412.5, 50, -494.5), (1412.5, 50, -491.5), (1413.5, 50, -489.5)]
CLIMB = [(1430, 48, -521), (1413, 48, -521), (1413, 50, -490), (1409, 50, -490), (1409, 50, -491), (1409, 51, -492),
         (1409, 51, -493), (1409, 52, -494), (1409, 52, -497), (1409, 53, -498), (1409, 53, -499), (1409, 54, -500),
         (1409, 54, -501), (1415, 54, -501), (1415, 55, -500), (1415, 55, -499), (1415, 56, -498), (1415, 56, -495),
         (1415, 57, -494), (1415, 57, -493), (1415, 58, -492), (1415, 58, -490), (1409, 58, -490), (1409, 58, -491),
         (1409, 59, -492), (1409, 59, -493), (1409, 60, -494), (1409, 60, -497), (1409, 61, -498), (1409, 61, -499),
         (1409, 62, -500), (1409, 62, -503), (1411, 62, -503), (1412, 63, -503), (1414, 63, -503), (1414, 63, -504),
         (1415, 63, -504), (1415, 63, -505), (1415, 64, -506), (1415, 64, -510), (1415, 65, -511), (1415, 65, -530),
         (1415, 64, -531), (1415, 64, -533), (1415, 65, -534), (1415, 65, -535), (1415, 66, -536), (1415, 66, -539),
         (1415, 67, -540), (1415, 67, -541), (1415, 68, -542), (1415, 68, -543), (1415, 69, -544), (1415, 69, -546),
         (1413, 69, -546), (1412, 70, -546), (1409, 70, -546), (1409, 70, -545), (1409, 71, -544), (1409, 71, -543),
         (1409, 72, -542), (1409, 72, -539), (1409, 73, -538), (1409, 73, -536), (1411, 73, -536), (1412, 74, -536),
         (1415, 74, -536), (1415, 74, -539), (1415, 75, -540), (1415, 75, -545), (1414, 75, -545)]


def climb_line():
    line = [(CLIMB[0][0] + 0.5, CLIMB[0][1], CLIMB[0][2] + 0.5), (CLIMB[1][0] + 0.5, CLIMB[1][1], CLIMB[1][2] + 0.5)]
    line += M1_LINE
    line += [(x + 0.5, y, z + 0.5) for (x, y, z) in CLIMB[3:]]
    return line


# ==================== NBT ====================

def read_nbt(data):
    pos = [0]

    def u(fmt):
        v = struct.unpack_from(">" + fmt, data, pos[0])
        pos[0] += struct.calcsize(">" + fmt)
        return v[0]

    def s():
        n = u("H")
        v = data[pos[0]:pos[0] + n].decode()
        pos[0] += n
        return v

    def payload(t):
        if t == 1: return u("b")
        if t == 2: return u("h")
        if t == 3: return u("i")
        if t == 4: return u("q")
        if t == 5: return u("f")
        if t == 6: return u("d")
        if t == 7:
            n = u("i"); v = data[pos[0]:pos[0] + n]; pos[0] += n; return v
        if t == 8: return s()
        if t == 9:
            et = u("b"); n = u("i"); return [payload(et) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                tt = u("b")
                if tt == 0:
                    return out
                k = s()
                out[k] = payload(tt)
        if t == 11:
            n = u("i"); return [u("i") for _ in range(n)]
        if t == 12:
            n = u("i"); return [u("q") for _ in range(n)]
        raise ValueError("tag %d" % t)

    t = u("b")
    s()
    return payload(t)


class Tag:
    def __init__(self, kind, value):
        self.kind, self.value = kind, value


def w_str(out, text):
    b = text.encode()
    out.write(struct.pack(">H", len(b)) + b)


def w_payload(out, tag):
    k, v = tag.kind, tag.value
    if k == 1: out.write(struct.pack(">b", v))
    elif k == 3: out.write(struct.pack(">i", v))
    elif k == 8: w_str(out, v)
    elif k == 9:
        elem = v[0].kind if v else 10
        out.write(struct.pack(">bi", elem, len(v)))
        for item in v:
            w_payload(out, item)
    elif k == 10:
        for name in sorted(v):
            out.write(struct.pack(">b", v[name].kind))
            w_str(out, name)
            w_payload(out, v[name])
        out.write(b"\x00")
    elif k == 12:
        out.write(struct.pack(">i", len(v)))
        for x in v:
            out.write(struct.pack(">q", x))
    else:
        raise ValueError(k)


def nbt_root(compound):
    out = io.BytesIO()
    out.write(b"\x0a")
    w_str(out, "")
    w_payload(out, Tag(10, compound))
    return out.getvalue()


# ==================== the blocks ====================

def schematic_blocks():
    root = read_nbt(gzip.open(SCHEMATIC).read())
    sch = root.get("Schematic", root)
    w, h, l = sch["Width"], sch["Height"], sch["Length"]
    blocks = sch["Blocks"]
    inv = {v: k for k, v in blocks["Palette"].items()}
    data, values, i = blocks["Data"], [], 0
    while i < len(data):
        v = shift = 0
        while True:
            b = data[i] & 0xFF
            i += 1
            v |= (b & 0x7F) << shift
            shift += 7
            if not b & 0x80:
                break
        values.append(v)
    origin = sch["Metadata"]["WorldEdit"]["Origin"]
    offset = sch["Offset"]
    min_x, min_y, min_z = origin[0] + offset[0], origin[1] + offset[1], origin[2] + offset[2]
    out = {}
    for y in range(h):
        for z in range(l):
            for x in range(w):
                state = inv[values[x + z * w + y * w * l]]
                if state not in ("minecraft:air", "minecraft:cave_air"):
                    out[(min_x + x, min_y + y, min_z + z)] = state
    for x in range(min_x, min_x + w):
        for z in range(min_z, min_z + l):
            for y in range(46, min_y):
                out.setdefault((x, y, z), "minecraft:stone")
    for x in range(min_x + w, 1432):
        for z in range(-530, -509):
            for y in range(46, 49):
                out[(x, y, z)] = "minecraft:stone"
    return out


def palette_entry(state):
    if "[" not in state:
        return Tag(10, {"Name": Tag(8, state)})
    name, props = state[:-1].split("[", 1)
    properties = {}
    for pair in props.split(","):
        k, v = pair.split("=", 1)
        properties[k] = Tag(8, v)
    return Tag(10, {"Name": Tag(8, name), "Properties": Tag(10, properties)})


def to_signed(v):
    return v - (1 << 64) if v >= (1 << 63) else v


def chunk_nbt(cx, cz, blocks):
    sections = []
    for sy in range(-4, 20):
        cells = []
        palette = ["minecraft:air"]
        index = {"minecraft:air": 0}
        any_block = False
        for i in range(4096):
            x, z, y = cx * 16 + (i & 15), cz * 16 + ((i >> 4) & 15), sy * 16 + (i >> 8)
            state = blocks.get((x, y, z), "minecraft:air")
            if state not in index:
                index[state] = len(palette)
                palette.append(state)
            any_block |= state != "minecraft:air"
            cells.append(index[state])
        if not any_block:
            continue
        bits = max(4, math.ceil(math.log2(len(palette))))
        per = 64 // bits
        longs = []
        for start in range(0, 4096, per):
            v = 0
            for j, cell in enumerate(cells[start:start + per]):
                v |= cell << (j * bits)
            longs.append(to_signed(v))
        sections.append(Tag(10, {"Y": Tag(1, sy), "block_states": Tag(10, {
            "palette": Tag(9, [palette_entry(p) for p in palette]), "data": Tag(12, longs)})}))
    return nbt_root({"DataVersion": Tag(3, DATA_VERSION), "xPos": Tag(3, cx), "zPos": Tag(3, cz),
                     "yPos": Tag(3, -4), "Status": Tag(8, "minecraft:full"), "sections": Tag(9, sections)})


def region_files(blocks):
    chunks = sorted({(x >> 4, z >> 4) for (x, _, z) in blocks})
    files = {}
    for (cx, cz) in chunks:
        files.setdefault((cx >> 5, cz >> 5), []).append((cx, cz))
    out = {}
    for (rx, rz), members in sorted(files.items()):
        header = bytearray(8192)
        body = bytearray()
        for (cx, cz) in members:
            raw = zlib.compress(chunk_nbt(cx, cz, blocks), 9)
            payload = struct.pack(">ib", len(raw) + 1, 2) + raw
            payload += b"\x00" * (-len(payload) % 4096)
            offset = 2 + len(body) // 4096
            sectors = len(payload) // 4096
            slot = (cx & 31) + (cz & 31) * 32
            struct.pack_into(">i", header, slot * 4, (offset << 8) | sectors)
            body += payload
        out["r.%d.%d.mca" % (rx, rz)] = bytes(header + body)
    return out


# ==================== the JSON parts ====================

def snapshot_json(line, region_names):
    xs = [math.floor(p[0]) for p in line]
    ys = [math.floor(p[1]) for p in line]
    zs = [math.floor(p[2]) for p in line]
    box_min = [min(xs) - 8, min(ys) - 6, min(zs) - 8]
    box_max = [max(xs) + 8, max(ys) + 12, max(zs) + 8]
    route = {"name": "stairhouse-sample", "from": list(line[0]), "to": list(line[-1]), "status": "FOUND",
             "message": None, "line": [list(p) for p in line], "stretches": [],
             "stretchesNote": "none: a sample made from the schematic, not from a server",
             "gates": [], "plazas": []}

    def domain(region, domain_id, name, kind, shape, points, min_y, max_y, parent, priority, doors=()):
        return {"regionId": region, "domainId": domain_id, "name": name, "type": kind, "shape": shape,
                "points": points, "minY": min_y, "maxY": max_y, "parent": parent, "priority": priority,
                "entryFlag": None, "exitFlag": None, "allowEntry": True, "allowExit": True, "gateDoors": list(doors)}

    domains = [
        domain("town_1", 1, "Sample Town", "town", "polygon",
               [[1300, -650], [1550, -650], [1550, -400], [1300, -400]], -64, 319, None, 10),
        domain("district_1000004", 1000004, "Keep District", "district", "cuboid",
               [[1395, 40, -560], [1440, 100, -480]], 40, 100, "town_1", 20),
        domain("structure_3000001", 3000001, "Keep Stair House", "structure", "cuboid",
               [[1407, 49, -549], [1418, 79, -487]], 49, 79, "district_1000004", 30),
        domain("gate_2000131", 2000131, "Stair House Gate", "gate", "cuboid",
               [[1411, 49, -509], [1415, 53, -507]], 49, 53, "district_1000004", 40,
               [{"doorId": 7, "name": "stair house door", "state": "OPEN", "open": True}]),
    ]
    settings = [{"key": k, "value": v, "layer": "global"} for k, v in [
        ("stretch-blocks", "8.0"), ("stationary-ticks", "60"), ("no-progress-ticks", "60"), ("collidable", "false"),
        ("group-spacing", "2.0"), ("group-ranks-width", "3"), ("group-formation-keeping", "levels"),
        ("corridor-steering", "true"), ("local-legs-clearance", "true"), ("plaza-lines", "true"),
        ("crowd-awareness", "true"), ("stop-on-trespass", "true")]]
    region_paths = ["world/region/" + n for n in region_names]
    manifest = {"format": 1, "route": "stairhouse-sample", "world": "world", "boxMin": box_min, "boxMax": box_max,
                "takenAt": "2026-11-16T00:00:00Z", "pluginVersion": "sample (tools/scenario-scenes/make_snapshot_sample.py)",
                "counts": {"route points": len(line), "stretches": 0, "road nodes": 0, "road edges": 0,
                           "domains": len(domains), "settings": len(settings), "region files": len(region_paths)},
                "regionFiles": region_paths,
                "notes": ["a sample made from knk-stairhouse.schem, not a server snapshot"]}
    return manifest, route, {"nodes": [], "edges": []}, domains, settings


def main():
    blocks = schematic_blocks()
    regions = region_files(blocks)
    manifest, route, roads, domains, settings = snapshot_json(climb_line(), sorted(regions))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as z:
        def put(name, data):
            info = zipfile.ZipInfo(name, FIXED_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
        put("README.txt", "Sample NPC navigation snapshot made from knk-stairhouse.schem "
                          "(tools/scenario-scenes/make_snapshot_sample.py, PandiO/knk-workspace#670).\n")
        put("manifest.json", json.dumps(manifest, indent=2))
        put("route.json", json.dumps(route, indent=2))
        put("roads.json", json.dumps(roads, indent=2))
        put("domains.json", json.dumps(domains, indent=2))
        put("settings.json", json.dumps(settings, indent=2))
        for name in sorted(regions):
            put("world/region/" + name, regions[name])
    with open(OUT, "wb") as f:
        f.write(buffer.getvalue())
    print("wrote %s: %d blocks in %s, %d bytes" % (os.path.relpath(OUT, ROOT), len(blocks), sorted(regions),
                                                   len(buffer.getvalue())))


if __name__ == "__main__":
    sys.exit(main())
