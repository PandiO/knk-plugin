"""Read-only export of everything a tile build reads from the API, for the offline replay (KNG-27).
Usage: python export_replay.py <tileX> <tileZ> <out.json> [path/to/appsettings.json]
The world defaults to world_KNK-DEV; set KNK_REPLAY_WORLD for another one. See README.md here."""
import json, os, sys, pymysql

WORLD = os.environ.get("KNK_REPLAY_WORLD", "world_KNK-DEV")
TX, TZ = int(sys.argv[1]), int(sys.argv[2])
OUT = sys.argv[3]
SIZE, MARGIN, DOMAIN_REACH = 512, 32, 8
rminx, rminz = TX * SIZE - MARGIN, TZ * SIZE - MARGIN
rmaxx, rmaxz = TX * SIZE + SIZE - 1 + MARGIN, TZ * SIZE + SIZE - 1 + MARGIN

def inregion(x, z):
    return rminx <= x <= rmaxx and rminz <= z <= rmaxz

# Connection from knk-web-api appsettings.json (ConnectionStrings:MySqlDbConnection); pass its path as argv[4].
# Default: knk-web-api next to knk-plugin (siblings, or both under knk-workspace/Repository).
settings = sys.argv[4] if len(sys.argv) > 4 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "knk-web-api", "appsettings.json")
cs = dict(kv.split("=", 1) for kv in json.load(open(settings))["ConnectionStrings"]["MySqlDbConnection"].split(";") if "=" in kv)
db = pymysql.connect(host=cs["Server"], user=cs["User"], password=cs["Password"], database=cs["Database"],
                     cursorclass=pymysql.cursors.DictCursor)
cur = db.cursor()
cur.execute("SET SESSION TRANSACTION READ ONLY")
out = {"tileX": TX, "tileZ": TZ}

cur.execute("SELECT * FROM road_profiles")
out["profiles"] = [{"id": p["Id"], "name": p["Name"], "enabled": bool(p["Enabled"]), "widthMin": p["WidthMin"],
                    "widthMax": p["WidthMax"], "materials": json.loads(p["MaterialsJson"] or "[]")}
                   for p in cur.fetchall()]

cur.execute("SELECT Id FROM road_tiles WHERE World=%s AND TileX=%s AND TileZ=%s", (WORLD, TX, TZ))
row = cur.fetchone()
tile_id = row["Id"] if row else None
nodes, edges = [], []
if tile_id is not None:
    cur.execute("SELECT * FROM road_nodes WHERE TileId=%s", (tile_id,))
    nodes = [{"id": n["Id"], "x": n["X"], "y": n["Y"], "z": n["Z"], "kind": n["Kind"], "locked": bool(n["Locked"]),
              "plazaRadius": n.get("PlazaRadius"), "name": n["Name"]} for n in cur.fetchall()]
    # SELECT *: Confirmed only exists after the AddRoadCuratedTiles migration (rev. 6, plan 5.7).
    cur.execute("SELECT * FROM road_edges WHERE TileId=%s", (tile_id,))
    edges = [{"id": e["Id"], "from": e["FromNodeId"], "to": e["ToNodeId"], "geometry": json.loads(e["GeometryJson"]),
              "source": e["Source"], "length": e["Length"], "avgWidth": e["AvgWidth"], "profileId": e["ProfileId"],
              "confirmed": bool(e.get("Confirmed", 0))} for e in cur.fetchall()]
out["nodes"], out["edges"] = nodes, edges

cur.execute("SELECT X, Y, Z FROM road_seeds WHERE World=%s", (WORLD,))
seeds = [[s["X"], s["Y"], s["Z"], "seed"] for s in cur.fetchall() if inregion(s["X"], s["Z"])]
cur.execute("SELECT Id, BreadcrumbJson FROM road_surveys WHERE World=%s ORDER BY Id", (WORLD,))
for s in cur.fetchall():
    since = 8
    for p in json.loads(s["BreadcrumbJson"] or "[]"):
        if not inregion(p["x"], p["z"]):
            continue
        since += 1 if p.get("onRoad") else 0
        if p.get("onRoad") and since >= 8:
            seeds.append([p["x"], p["y"], p["z"], "crumb"])
            since = 0
cur.execute("""SELECT l.X, l.Y, l.Z FROM domains d JOIN locations l ON l.Id = d.LocationId
               WHERE l.World=%s AND l.X BETWEEN %s AND %s AND l.Z BETWEEN %s AND %s ORDER BY d.Id""",
            (WORLD, rminx - DOMAIN_REACH, rmaxx + DOMAIN_REACH, rminz - DOMAIN_REACH, rmaxz + DOMAIN_REACH))
import math
for l in cur.fetchall():
    seeds.append([math.floor(l["X"]), math.floor(l["Y"]) - 1, math.floor(l["Z"]), "domain"])
cur.execute("""SELECT n.X, n.Y, n.Z, t.TileX, t.TileZ FROM road_nodes n JOIN road_tiles t ON t.Id = n.TileId
               WHERE t.World=%s AND n.Kind='Boundary'""", (WORLD,))
for n in cur.fetchall():
    if (n["TileX"], n["TileZ"]) != (TX, TZ) and max(abs(n["TileX"] - TX), abs(n["TileZ"] - TZ)) == 1 \
            and inregion(n["X"], n["Z"]):
        seeds.append([n["X"], n["Y"], n["Z"], "boundary"])
out["seeds"] = seeds
json.dump(out, open(OUT, "w"), indent=1, default=str)
print(f"profiles {len(out['profiles'])}, nodes {len(nodes)}, edges {len(edges)}, seeds {len(seeds)} -> {OUT}")
