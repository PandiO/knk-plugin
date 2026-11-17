# Smoke test 2026-11-17: NPC movement after L9 and B1–B4

The developer's retest on the dev server, plugin `0.1.0+e69dba6a`, merged dev config with `group-formation-keeping:
elastic`, `crowd-awareness: true`, `collidable: false`. Steps as posted in the coordinator session (plan Phase 4c.8).

1. Leader alone through the stair house: passed; about 4 small hops after a stair step (#695).
2. `ranks` group of 20: failed at the second-last flight. The leader ended up one flight directly above its group;
   the followers below walked up and down the flight under it and did not recover. Screenshot
   `step2-group-stuck-1408-76-546.webp` (the player at 1408 76 -546). Log: followers repeatedly "off its lane at
   1413 67 -539: its own leg back to the group's path at 1416 69 -542" from 20:17:29.
3. Group with 20 singles: the singles arrived; the group stuck the same way as step 2.
4. Access change mid-group (#668): passed.
5. Snapshots: `quarry-to-keep-20261117-193431.zip` (with gate doors) and
   `northern-wearway-upper-keep-tower-20261117-193740.zip`: a road route that ends about 100 blocks off-road through
   the keep's throne room and up a spiral staircase (goal 1401 113 -502). One NPC walked the road part well, then got
   stuck at 1405 85 -510 ("engine found no path", local leg to 1401 113 -503, order failed). Note: the snapshot's box
   reaches y 96 only, below the goal (y 113); the region files hold the whole columns.

Files: the `npc-nav` log and the traces of the run (`trace_knk-nav-*.log`).
