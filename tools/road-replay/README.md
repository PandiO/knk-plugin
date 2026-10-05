# Road build offline replay (KNG-27)

**Status:** Committed tooling (plan §5.7 decision D7). First used for finding L, 2026-10-04.
**Last updated:** 2026-10-05

Runs the real `TileBuilder` on **copies** of the dev world's region files, with the build inputs exported read-only
from the dev DB. Use it to reproduce a live build exactly, then change one input at a time: tombstones on or off, a
config value, a builder change. For a curated tile it also prints the proposal a rebuild would make (`TileDiff`,
rev. 6 Part B). The knk-workspace smoke-test guide, finding L, has an example.

## Files

- `export_replay.py` (here): exports one tile's build inputs to JSON: profiles, seeds, survey breadcrumbs (every
  8th on-road point), Domain Locations, neighbour Boundary nodes, and the tile's nodes, edges and tombstones. It
  opens a read-only session and reads the connection from knk-web-api's `appsettings.json` (by default the
  knk-web-api checkout next to knk-plugin). It needs `pymysql`. The world is `world_KNK-DEV`; set
  `KNK_REPLAY_WORLD` for another one.
- `knk-paper/src/test/java/net/knightsandkings/knk/paper/roads/RoadReplayTest.java`: the JUnit harness. It reads
  the region files with a small Anvil/NBT reader, captures chunks with the server's own
  `CompactSurfaceGrid`/`SpanExtractor`, builds, and writes a text report per tile. It is **skipped unless
  `KNK_REPLAY_DIR` is set**, so normal builds and CI never run it.

## Use

1. Make a folder, e.g. `<scratch>/replay-data/`, with `region/` and `replay/` inside.
2. **Copy** (never move or open in place) the region files around the tile from
   `MinecraftServer/Servers/DEV_SERVER_1.21.10/world_KNK-DEV/region/` into `region/`. A tile `(tx, tz)` plus its
   32-block margin needs `r.{tx-1..tx+1}.{tz-1..tz+1}.mca`.
3. Run `python tools/road-replay/export_replay.py 2 -2 <scratch>/replay-data/replay/tile_2_-2.json`. Pass the
   path to `appsettings.json` as a 4th argument if it is somewhere else.
4. Write `replay/control.txt` (lines starting with `#` are ignored):
   ```
   files=tile_2_-2.json
   variants=live,notomb,raw
   # the server's builder config (config.yml key names); these are the 2026-10-04 dev values
   junction-cluster-radius=5
   min-spur-length=15
   ambiguous-reach=2
   plaza-growth=4
   # optional: list only nodes/edges in x0,x1,z0,z1; draw a top view of x0,x1,z0,z1,y0,y1 to map.txt
   focus=1370,1460,-600,-495
   map=1380,1450,-590,-505,38,56
   ```
   - Variants: `live`, `notomb`, `raw`, `noauto`, `c3`, `c1`, `rawc3` (add more in `RoadReplayTest.variants`).
   - Other keys: `tile-margin`, `max-cells-per-tile`, `locked-node-reach`, `auto-plazas`, `overlay-materials`.
5. Stop the Gradle daemon (`./gradlew --stop`, so it picks up the variable), then run
   `KNK_REPLAY_DIR=<scratch>/replay-data ./gradlew :knk-paper:test --offline --rerun --tests "*RoadReplayTest*"`.
   On Windows, set the variable first (`set KNK_REPLAY_DIR=...` or `$env:KNK_REPLAY_DIR=...`). `--rerun` matters:
   Gradle does not see changes to `control.txt` and would skip the test.
6. Read `replay/out_<tile>.txt` (each variant's nodes, edges, warnings and corrections; after `live` the
   proposal against the stored graph) and `replay/map.txt`.

First check that the `live` variant reproduces the stored build: the same node and edge counts and the same
geometry. If it does not, an input is missing. Gate cells are not exported, and the passability rules use the
curated collidable list, not Bukkit's. The proposal leaves out domains, regions and gate doors, because the replay
does not tag them.
