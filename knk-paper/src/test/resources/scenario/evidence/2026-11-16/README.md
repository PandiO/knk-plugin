# Smoke test 2026-11-16: NPC movement traces

Traces from the developer's dev server (jar `0.1.0+0d9c55d6`), route `quarry-to-keep` through the Keep stair house,
copied from the logs the developer uploaded to the chain coordinator. Evidence for PandiO/knk-workspace#670 (B1) and
the root cause in the chain report (decision 34). Each file starts with a header that explains its columns; the
`# waypoints:` lines are the stretches as handed out, `# corridor:` lines the free width per waypoint.

1. `knk-nav-69`, `knk-nav-81`: the stretch handover skipping a flight on the stacked hairpins.
2. `knk-nav-48`, `-62`, `-63`, `-87`, `-143`, `-152`: other NPCs of the same runs. The report's failures to look for:
   the falls at 1412 66 -537 and 1412 49 -500, the stuck spot at 1410 49 -503, the queue at 1412 74 -536.

The server's settings during the test (decision 35): `stationary-ticks` and `no-progress-ticks` 60, `group-spacing`
2.0, `group-formation-keeping` unset (levels), `corridor-steering`, `local-legs-clearance`, `plaza-lines`,
`crowd-awareness` and `stop-on-trespass` true, `collidable` false.

## Snapshot

`quarry-to-keep-20261116-212531.zip`: the developer's `/knk npc nav snapshot quarry-to-keep` (plugin
`0.1.0+0f039203`, 2026-11-16 21:25 UTC), with box 1391 36 -559 to 1505 96 -393, region files `r.2.-1.mca` and
`r.2.-2.mca`, 103 route points in 18 stretches, 54 road nodes and 46 edges, and 13 regions (town_1, the districts
1000002, 1000004 and 1000006, the gates). It was taken on the merged dev config: `group-formation-keeping: elastic`,
`group-spacing` 1.5, and `stationary-ticks` and `no-progress-ticks` 40.
