# Smoke test 2026-11-16: NPC movement traces

Traces from the developer's dev server (jar `0.1.0+0d9c55d6`), route `quarry-to-keep` through the Keep stair house,
copied from the logs the developer uploaded to the chain coordinator. Evidence for PandiO/knk-workspace#670 (B1) and
the root cause in the chain report (decision 34). Each file starts with a header that explains its columns; the
`# waypoints:` lines are the stretches as handed out, `# corridor:` lines the free width per waypoint.

1. `knk-nav-69`, `knk-nav-81`: the stretch handover skipping a flight on the stacked hairpins.
2. `knk-nav-48`, `-62`, `-63`, `-87`, `-143`, `-152`: group followers, falls at 1412 66 -537 and 1412 49 -500, the stuck
   spot at 1410 49 -503.

The server's settings during the test (decision 35): `stationary-ticks` and `no-progress-ticks` 60, `group-spacing`
2.0, `group-formation-keeping` unset (levels), `corridor-steering`, `local-legs-clearance`, `plaza-lines`,
`crowd-awareness` and `stop-on-trespass` true, `collidable` false.
