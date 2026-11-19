# Smoke retest 2026-11-19: B5, C1, E1, R1

The developer's retest on the dev server (snapshot plugin `0.1.0+6311de11`), steps as posted in the coordinator
session (smoke guide sections "Link B5", "Link C1", "Link E1", "Link R1"). All passed.

1. B5: a `ranks` group of 20 up the stair house, alone and with 20 singles: works; the group holds formation well.
2. C1: one NPC and a group of 20 up the keep tower to the roof: works. The NPC looked a bit jittery in the spiral
   staircase (the walk path hugs the inner wall); passable, an improvement for later. The off-road start works.
3. E1: a leader alone and a group half inside with an exit denied mid-trip, with and without a refresh: works.
4. R1: works.
5. `rules: trespasses 0` throughout; no "order failed", "walked in hops" or "engine found no path".

Files: the `npc-nav` log of the run, and the new tower snapshot (box 1271 36 -633 to 1429 125 -481, above the goal at
y 113 and round the route's start, as C1 made it).
