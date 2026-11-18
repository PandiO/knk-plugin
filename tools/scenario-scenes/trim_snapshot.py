#!/usr/bin/env python3
"""Trims an NPC navigation snapshot zip to a window, for a scenario replay in CI (link B1, PandiO/knk-workspace#670).

/knk npc nav snapshot <route> copies whole region files (512 x 512 blocks each, 8 to 9 MB). A scenario box is at most
64 blocks wide along x, so the replay needs only the chunks round the part of the route it walks. This keeps, in each
region file, the chunks whose 16 x 16 columns touch the window (x and z, every height), writes them into a fresh region
file, and sets the manifest's box to the window (with a note naming the original box). The route, roads, domains and
settings are kept whole.

Usage, from the knk-plugin root:
  python3 tools/scenario-scenes/trim_snapshot.py IN.zip OUT.zip MINX MINY MINZ MAXX MAXY MAXZ

The output is deterministic for the same input (fixed zip times, sorted entries).
"""

import io
import json
import struct
import sys
import zipfile

FIXED_TIME = (2026, 11, 16, 0, 0, 0)


def trim_region(name, data, box):
    rx, rz = [int(v) for v in name.rsplit("/", 1)[-1].split(".")[1:3]]
    header = bytearray(8192)
    body = bytearray()
    kept = 0
    for i in range(1024):
        location = struct.unpack_from(">i", data, i * 4)[0]
        offset, sectors = (location >> 8) & 0xFFFFFF, location & 0xFF
        if offset == 0:
            continue
        cx, cz = rx * 32 + (i & 31), rz * 32 + (i >> 5)
        if cx * 16 + 15 < box[0] or cx * 16 > box[3] or cz * 16 + 15 < box[2] or cz * 16 > box[5]:
            continue
        chunk = data[offset * 4096:(offset + sectors) * 4096]
        new_offset = 2 + len(body) // 4096
        struct.pack_into(">i", header, i * 4, (new_offset << 8) | sectors)
        struct.pack_into(">i", header, 4096 + i * 4, struct.unpack_from(">i", data, 4096 + i * 4)[0])
        body += chunk
        kept += 1
    return bytes(header + body), kept


def main(argv):
    if len(argv) != 9:
        print(__doc__)
        return 2
    src, dst = argv[1], argv[2]
    box = [int(v) for v in argv[3:9]]
    zin = zipfile.ZipFile(src)
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
        def put(name, data):
            info = zipfile.ZipInfo(name, FIXED_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(info, data)

        for name in sorted(zin.namelist()):
            data = zin.read(name)
            if name == "manifest.json":
                m = json.loads(data)
                original = "%s to %s" % (" ".join(map(str, m["boxMin"])), " ".join(map(str, m["boxMax"])))
                m["boxMin"], m["boxMax"] = box[:3], box[3:]
                m["notes"] = list(m.get("notes", [])) + [
                    "trimmed by tools/scenario-scenes/trim_snapshot.py to the window %s to %s (the snapshot's box was %s); "
                    "region files hold only the chunks round the window" % (" ".join(map(str, box[:3])),
                                                                            " ".join(map(str, box[3:])), original)]
                data = json.dumps(m, indent=2).encode()
            elif name.endswith(".mca"):
                data, kept = trim_region(name, data, box)
                print("%s: %d chunks kept" % (name, kept))
            put(name, data)
    with open(dst, "wb") as f:
        f.write(out.getvalue())
    print("wrote %s: %d bytes" % (dst, len(out.getvalue())))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
