package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup;
import net.knightsandkings.knk.core.npc.nav.snapshot.NavSnapshot;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.scenario.SizedScene;
import net.knightsandkings.knk.paper.scenario.SnapshotReplay;

/**
 * Link B1 (PandiO/knk-workspace#670), the main replay: the developer's snapshot of {@code quarry-to-keep} replayed
 * on a real server. Since link B5 (PandiO/knk-workspace#699) that is the snapshot taken on the dev server on 2026-11-17
 * at 19:34 (plugin {@code 0.1.0+e69dba6a}, with the gate doors' states), before the smoke test whose group stuck under
 * its leader a flight above; B1 to B4 replayed the one of 2026-11-16 at 21:25. {@value #SNAPSHOT} is that zip trimmed
 * to the window round the stair house ({@code tools/scenario-scenes/trim_snapshot.py}: x 1391..1454, y 36..96,
 * z -559..-480; the whole 17.6 MB zip is on knk-plugin {@code evidence/smoke-2026-11-16}, folder
 * {@code knk-paper/src/test/resources/scenario/evidence/2026-11-17/}).
 * <p>
 * It places the real blocks from the region files, builds the 13 domains' WorldGuard regions with the access data the
 * plugin's caches had, and plans on the stored road line. Checks the replay itself (blocks placed, regions seen by the
 * NPC rule checks, every setting known). The walks: a leader alone from the street east of the house (1436 43 -492) up
 * all five flights to the top hall (1415 76 -546) and a group of 20 in ranks from the same street, each reported by
 * {@link ClimbWatch} (falls, places skipped, handovers onto another level, the 30 s guards), twice: with the stored
 * settings (the developer's dev config of the smoke test of 2026-11-17: {@code elastic}, {@code crowd-awareness} on,
 * {@code collidable} off, spacing 1.5, 40/40, stretches of 32 blocks) and with the settings of the failed smoke test
 * of 2026-11-16 (decision 35.3: {@code levels}, spacing 2.0, 60/60). With the stored settings the group walks on to the
 * route's own end in the keep (1421 85 -516), past the top hall and two gates, as the developer's did: its leader goes
 * on past the flights while the rear ranks still climb them. Since link B2 (PandiO/knk-workspace#671) each walk fails on
 * a fall from a flight or landing, a handover onto another level, a turn released by the 30 s guard after its holder
 * fell ({@link ClimbWatch#failures}) and a leader that does not arrive. Since link B4 (PandiO/knk-workspace#673) the
 * group also fails on a lost follower: on the real blocks they came from slots on flight 5's half-block steps at
 * whole-block heights. Since link B5 the group fails on a follower that left its lane on the flights and landings
 * (an own leg back from off its lane there) and on a looping follower ({@link #ownLegs}).
 * <p>
 * {@link #pastTheBinade()} (PandiO/knk-workspace#958) is the same replay, with the snapshot's settings only, in a scene
 * at world x {@value #BINADE} or beyond: before the route line was put on a grid ({@code RouteFollower#LINE_GRID}) the
 * group fell from the climb there (17 falls) and not further west, because the road's points on the block face x 1412
 * rounded to the other side of the face.
 */
public final class NpcSnapshotReplayScenario implements Scenario<ScenarioScene>, SizedScene {

    /** The committed, trimmed snapshot, in the jar's {@value SnapshotReplay#RESOURCE_FOLDER}. */
    static final String SNAPSHOT = "quarry-to-keep-stairhouse.zip";
    static final int MEMBERS = 20;
    static final int LEADER_TICKS = 120 * 20;
    static final int GROUP_TICKS = 240 * 20;
    /** Followers keep walking this long after the leader's trip ended. */
    static final int SETTLE_TICKS = 30 * 20;
    /** The start on the street east of the house and the goal in the top hall (real feet). */
    static final double[] START = {1436.5, 43, -491.5};
    static final double[] GOAL = {1415.5, 76, -545.5};
    /** The route's own end in the keep (real feet), past the top hall and two gates. */
    static final double[] END = {1421.5, 85, -515.5};

    /**
     * The world x a scene of {@link #pastTheBinade()} starts at or beyond (PandiO/knk-workspace#958): from 2^15 on, a
     * double has one bit less for the fraction, so a route point there rounds differently than below it.
     */
    static final int BINADE = 1 << 15;

    private final String name;
    private final boolean both;
    private final boolean pastBinade;
    private SnapshotReplay replay;

    /** The replay with both configurations, wherever its scene is. */
    public NpcSnapshotReplayScenario() {
        this("npc-replay-quarry-to-keep", true, false);
    }

    private NpcSnapshotReplayScenario(String name, boolean both, boolean pastBinade) {
        this.name = name;
        this.both = both;
        this.pastBinade = pastBinade;
    }

    /**
     * The check of PandiO/knk-workspace#958: the replay with the snapshot's settings (the walks that failed) in a scene
     * at world x {@value #BINADE} or beyond. Registered from the 45th scenario on (scene index 44 is x 32816 with the
     * shipped origin); a scene further west fails the scenario's first check, so it never passes without testing that.
     */
    public static NpcSnapshotReplayScenario pastTheBinade() {
        return new NpcSnapshotReplayScenario("npc-replay-quarry-to-keep-past-32768", false, true);
    }

    private SnapshotReplay replay() {
        if (replay == null) {
            replay = SnapshotReplay.fromResource(SNAPSHOT);
        }
        return replay;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return pastBinade
            ? "The quarry-to-keep replay with the snapshot's settings in a scene at world x " + BINADE
                + " or beyond (#958): the same walks as further west"
            : "The developer's snapshot of quarry-to-keep replayed (real blocks, regions, line, settings); "
                + "a leader alone and a group of 20 up the stair house";
    }

    @Override
    public ScenarioScene.Box box() {
        return boxOf(replay().snapshot());
    }

    /** The scene box a snapshot needs: its own box. */
    public static ScenarioScene.Box boxOf(NavSnapshot snapshot) {
        int[] min = snapshot.manifest().boxMin();
        int[] max = snapshot.manifest().boxMax();
        return new ScenarioScene.Box(max[0] - min[0] + 1, max[1] - min[1] + 1, max[2] - min[2] + 1);
    }

    /** A real feet position in the scene. */
    private static NavPoint feet(ScenarioScene scene, SnapshotReplay replay, double x, double y, double z) {
        return replay.inScene(scene, new double[] {x, y, z});
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        SnapshotReplay replay = replay();
        AtomicReference<SnapshotReplay.Placed> placed = new AtomicReference<>();
        List<String> regions = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        if (pastBinade) {
            plan.check("the scene starts at world x " + BINADE + " or beyond (#958)", () -> scene.worldX(0) >= BINADE
                ? null : "the scene starts at x " + scene.worldX(0) + "; this check needs " + BINADE
                    + " or beyond (register it later or move testing.scenarios.origin.x east)");
        }
        plan.act("place the snapshot's blocks", () -> placed.set(replay.placeBlocks(scene)))
            .check("the region files' blocks are placed in the scene", () -> {
                SnapshotReplay.Placed p = placed.get();
                if (p.placed() == 0 || p.skippedChunks() != 0 || !p.problems().isEmpty()) {
                    return "placed " + p.describe() + " " + p.problems();
                }
                NavPoint start = feet(scene, replay, START[0], START[1] - 1, START[2]);
                if (scene.world().getBlockAt(start.blockX(), start.blockY(), start.blockZ()).getType().isAir()) {
                    return "no floor under the start at " + start;
                }
                return null;
            })
            .act("build the snapshot's regions", () -> regions.addAll(replay.buildRegions(scene)))
            .cleanup("remove the snapshot's regions", () -> replay.removeRegions(scene))
            .check("the regions are built and the NPC checks see the town and district at the start", () -> {
                if (regions.size() != replay.snapshot().domains().size()) {
                    return regions.size() + " regions built for " + replay.snapshot().domains().size() + " domains";
                }
                NavPoint start = feet(scene, replay, START[0], START[1], START[2]);
                Set<String> here = scene.services().npcRules().regionsAt(start.world(), start.blockX(), start.blockY(),
                    start.blockZ());
                if (!here.contains(replay.regionId(scene, "town_1"))) {
                    return "the NPC rule checks see " + here + " at the start, not town_1";
                }
                return null;
            })
            .check("every stored setting is known", () -> {
                replay.settings(rejected);
                return rejected.isEmpty() ? null : "rejected " + rejected;
            });
        walks(plan, scene, replay, placed, "the snapshot's settings", java.util.function.UnaryOperator.identity(), END);
        if (both) {
            walks(plan, scene, replay, placed, "the smoke test's settings", NpcSnapshotReplayScenario::smokeTest, GOAL);
        }
    }

    /**
     * The developer's server during the failed smoke test of 2026-11-16 (chain report decision 35.3), over the
     * snapshot's settings: stationary and no-progress ticks 60, group spacing 2.0, formation keeping {@code levels}.
     */
    static NavigationSettings.Overrides smokeTest(NavigationSettings.Overrides stored) {
        return stored.with(NavigationSettings.STATIONARY_TICKS, 60)
            .with(NavigationSettings.NO_PROGRESS_TICKS, 60)
            .with(NavigationSettings.GROUP_SPACING, 2.0)
            .with(NavigationSettings.GROUP_FORMATION_KEEPING, "levels");
    }

    /** A leader alone, then a group of 20, on the replay with one configuration; its own NPCs, started and stopped. */
    private void walks(ScenarioPlan plan, ScenarioScene scene, SnapshotReplay replay,
                       AtomicReference<SnapshotReplay.Placed> placed, String config,
                       java.util.function.UnaryOperator<NavigationSettings.Overrides> change, double[] groupGoal) {
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<ClimbWatch> watch = new AtomicReference<>();
        AtomicReference<NpcMovementReport> report = new AtomicReference<>();
        plan.act("start NPC navigation with " + config, () -> {
                npcs.set(ScenarioNpcs.start(scene, replay.loop(scene), replay.settings(new ArrayList<>(), change),
                    replay.accessRules(scene), replay.gates(scene)));
                npcs.get().plazas(replay.plazas(scene));
                npcs.get().everyTick(() -> {
                    if (watch.get() != null) {
                        watch.get().look(npcs.get().movers());
                    }
                });
            })
            .cleanup("remove the NPCs of " + config, () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            });

        // a leader alone
        List<NpcMover> alone = new ArrayList<>();
        AtomicReference<NavigationResult> leaderResult = new AtomicReference<>();
        long[] t = {0, -1};
        plan.act("a leader alone walks from the street east of the house to the top hall (" + config + ")", () -> {
                report.set(new NpcMovementReport(scene, name()).phaseStarts(npcs.get()));
                watch.set(new ClimbWatch(scene, replay, npcs.get().service()).stairHopsIn(scene.world()));
                NpcMover leader = npcs.get().spawn(feet(scene, replay, START[0], START[1], START[2]));
                alone.add(leader);
                t[0] = npcs.get().ticks();
                if (!npcs.get().service().navigate(leader.id(), feet(scene, replay, GOAL[0], GOAL[1], GOAL[2]), r -> {
                    leaderResult.set(r);
                    t[1] = npcs.get().ticks() - t[0];
                })) {
                    throw new ScenarioAssertionError("the navigation service refused the leader's order");
                }
            })
            .await("the leader's trip ends or the time is up", LEADER_TICKS + 20,
                () -> leaderResult.get() != null || npcs.get().ticks() - t[0] >= LEADER_TICKS)
            .check("report: the leader alone (" + config + ")", () -> {
                report(report.get(), npcs.get(), watch.get(), alone, leaderResult.get(), t[1], LEADER_TICKS, null,
                    placed.get(), config).log("a leader alone, " + config);
                List<String> failures = new ArrayList<>(watch.get().failures());
                if (!(leaderResult.get() instanceof NavigationResult.Arrived)) {
                    failures.add("the leader did not arrive: " + leaderResult.get());
                }
                npcs.get().remove(alone);
                return failures.isEmpty() ? null : String.join("; ", failures);
            });

        // a group of 20
        List<NpcMover> members = new ArrayList<>();
        AtomicReference<NavigationResult> groupResult = new AtomicReference<>();
        AtomicReference<NavGroup> group = new AtomicReference<>();
        long[] g = {0, -1};
        plan.act("a group of 20 in ranks walks the same way (" + config + ")", () -> {
                report.set(new NpcMovementReport(scene, name()).phaseStarts(npcs.get()));
                watch.set(new ClimbWatch(scene, replay, npcs.get().service()).stairHopsIn(scene.world()));
                members.add(npcs.get().spawn(feet(scene, replay, START[0], START[1], START[2])));
                for (int i = 1; i < MEMBERS; i++) {
                    // three abreast behind the leader, further south on the street (real 1435..1437, -490..-484)
                    members.add(npcs.get().spawn(feet(scene, replay, 1435.5 + (i - 1) % 3, START[1],
                        -490.5 + (i - 1) / 3)));
                }
                g[0] = npcs.get().ticks();
                group.set(npcs.get().group(members, Formation.Type.RANKS));
                group.get().navigate(feet(scene, replay, groupGoal[0], groupGoal[1], groupGoal[2]), r -> {
                    groupResult.set(r);
                    g[1] = npcs.get().ticks() - g[0];
                });
            })
            .await("everybody is in the top hall, or the leader's trip ended " + SETTLE_TICKS / 20
                + " s ago, or the time is up", GROUP_TICKS + 20, () -> {
                    long now = npcs.get().ticks();
                    return (groupGoal == GOAL && watch.get().reached(members,
                        net.knightsandkings.knk.paper.scenario.NpcMovementScene.climbPlaces() - 1) == members.size())
                        || (groupResult.get() != null && now - g[0] - g[1] >= SETTLE_TICKS)
                        || now - g[0] >= GROUP_TICKS;
                })
            .check("report: the group of 20 (" + config + ")", () -> {
                List<List<String>> legs = ownLegs(npcs.get(), scene, replay);
                report(report.get(), npcs.get(), watch.get(), members, groupResult.get(), g[1], GROUP_TICKS,
                    group.get(), placed.get(), config)
                    .add("followers off their lane on the stair house (B5)", ClimbWatch.first(legs.get(0), 4))
                    .add("looping followers (B5)", ClimbWatch.first(legs.get(1), 4))
                    .log("a group of 20 in ranks, " + config);
                List<String> failures = new ArrayList<>(watch.get().failures());
                // link B5 (PandiO/knk-workspace#699): no follower leaves its lane on the flights and landings, and
                // none loops (the smoke test of 2026-11-17)
                if (!legs.get(0).isEmpty()) {
                    failures.add("own legs back from off the lane on the stair house " + ClimbWatch.first(legs.get(0), 4));
                }
                if (!legs.get(1).isEmpty()) {
                    failures.add("looping followers: " + ClimbWatch.first(legs.get(1), 4));
                }
                if (!(groupResult.get() instanceof NavigationResult.Arrived)) {
                    failures.add("the group's leader did not arrive: " + groupResult.get());
                }
                // since link B4 (PandiO/knk-workspace#673) a lost follower fails the walk: the rear rows' slots on
                // flight 5's half-block steps are at the real surface height now (B2 left it report only)
                if (group.get().lost() > 0) {
                    failures.add(group.get().lost() + " followers lost:" + lostLines(npcs.get(), 24));
                }
                npcs.get().stop();
                return failures.isEmpty() ? null : String.join("; ", failures);
            });
    }

    private static NpcMovementReport report(NpcMovementReport report, ScenarioNpcs npcs, ClimbWatch watch,
                                            List<NpcMover> movers, NavigationResult result, long took, int maxTicks,
                                            NavGroup group, SnapshotReplay.Placed placed, String config) {
        var crowd = npcs.service().crowd();
        report.add("snapshot", SNAPSHOT + " (quarry-to-keep, 2026-11-17 19:34)")
            .add("settings", config)
            .add("blocks", placed.describe())
            .add(group == null ? "leader alone" : "leader", result == null ? "still walking after " + maxTicks / 20 + " s"
                : result.getClass().getSimpleName() + String.format(Locale.ROOT, " after %.1f s", took / 20.0))
            .add("got to", watch.furthest(movers))
            .add("falls", ClimbWatch.first(watch.falls(), 4))
            .add("places skipped or walked back to", ClimbWatch.first(watch.skips(), 4))
            .add("stretch handovers", watch.handovers())
            .add("handovers onto another level", ClimbWatch.first(watch.crossLevel(), 4))
            .add("new stretches starting off the feet level (B1's measure)", watch.stretchOffLevel().size())
            .add("falls from the climb", watch.fallsFromClimb().size())
            .add("jumps (by upward speed)", watch.jumps().size())
            .add("engine stair hops (off a stair's step)", watch.stairHops().size())
            .add("engine jumps held back, walked instead (J1)", movers.stream().mapToLong(NpcMover::stepHopsHeld).sum())
            .add("jumps toward a waypoint on the flights and their entries", ClimbWatch.first(watch.jumpsOnClimb(), 4))
            .add("queue let go by the 30 s guard", crowd.overdue())
            .add("why the guard let them go (B3)", ClimbWatch.first(watch.releases(npcs::nameOf), 6))
            .add("waits of 10 s or more (B3)", ClimbWatch.first(watch.longWaits(npcs::nameOf), 4))
            .add("turns dropped by the 30 s guard", ClimbWatch.first(watch.staleTurns(), 3))
            .add("fell (service count)", report.count(npcs, Event.FELL))
            .add("line steps walked as local legs", npcs.service().gapLegs())
            .add("stuck spells", report.count(npcs, Event.STUCK_ENGINE) + report.count(npcs, Event.STUCK_NO_PROGRESS))
            .add("trespasses", report.count(npcs, Event.TRESPASS));
        if (group != null) {
            report.add("stuck spells opened", ClimbWatch.first(spellsOpened(npcs, watch), 6));
            report.add("width now", group.width().describe()).add("lost", group.lost())
                .add("leader falls kept out of the group path", group.leaderFalls());
        }
        return report.crowd(npcs);
    }

    /**
     * The run-log lines of the followers that ended lost (why each could not get back, and the spells before it), at
     * most {@code n}: a failed walk says where and why without the NPC log files.
     */
    static String lostLines(ScenarioNpcs npcs, int n) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (ScenarioNpcs.LogLine line : npcs.log()) {
            if (line.text().contains("could not get back to the group's path")) {
                names.add(line.text().substring(0, line.text().indexOf(' ')));
            }
        }
        List<String> lines = new ArrayList<>();
        for (ScenarioNpcs.LogLine line : npcs.log()) {
            String text = line.text();
            if (!text.startsWith("trace ") && names.stream().anyMatch(name -> text.startsWith(name + " "))) {
                lines.add(line.tick() + ": " + text);
            }
        }
        StringBuilder out = new StringBuilder();
        for (String line : lines.subList(Math.max(0, lines.size() - n), lines.size())) {
            out.append("\n  ").append(line);
        }
        return out.toString();
    }

    private static final java.util.regex.Pattern OWN_LEG = java.util.regex.Pattern.compile(
        "^(\\S+) \\(group \\d+\\) (?:is off its lane|is stuck|is still stuck \\(steps used up\\)) "
            + "at (-?\\d+) (-?\\d+) (-?\\d+): its own leg back");

    /**
     * Link B5 (PandiO/knk-workspace#699): the followers' own legs back to the group's path, from the run log. Returns
     * the ones a follower took because it was off its lane on the stair house's flights and landings (real block and
     * place), and the looping ones: a follower that took three or more own legs back from the same block (the smoke
     * test of 2026-11-17: every 5.5 s from 1413 67 -539 while its leader stood a flight above).
     */
    static List<List<String>> ownLegs(ScenarioNpcs npcs, ScenarioScene scene, SnapshotReplay replay) {
        List<String> offLane = new ArrayList<>();
        java.util.Map<String, Integer> from = new java.util.LinkedHashMap<>();
        for (ScenarioNpcs.LogLine line : npcs.log()) {
            java.util.regex.Matcher m = OWN_LEG.matcher(line.text());
            if (!m.find()) {
                continue;
            }
            double[] real = replay.real(scene, new NavPoint(scene.world().getName(), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
            int x = (int) Math.floor(real[0] + 0.01);
            int y = (int) Math.floor(real[1] + 0.01);
            int z = (int) Math.floor(real[2] + 0.01);
            String block = x + " " + y + " " + z;
            from.merge(m.group(1) + " from " + block, 1, Integer::sum);
            int place = net.knightsandkings.knk.paper.scenario.NpcMovementScene.climbPlaceAt(x, y, z);
            if (line.text().contains("is off its lane") && place > 0) {
                offLane.add(m.group(1) + " at " + block + " ("
                    + net.knightsandkings.knk.paper.scenario.NpcMovementScene.climbPlaceName(place) + ")");
            }
        }
        List<String> looping = new ArrayList<>();
        from.forEach((who, n) -> {
            if (n >= LOOPING_LEGS) {
                looping.add(who + " " + n + " times");
            }
        });
        return List.of(offLane, looping);
    }

    /** Own legs back from one block that make a follower a looping one (link B5). */
    static final int LOOPING_LEGS = 3;

    private static final java.util.regex.Pattern STUCK_AT = java.util.regex.Pattern.compile(
        "^(\\S+) \\(group \\d+\\) stuck at (\\S+) (\\S+) (\\S+), (\\S+) blocks behind its slot .*\\((\\S+) away\\)");

    /**
     * Where the group's stuck spells opened (link B4): who, the real block, how far behind its slot along the path and
     * how far from its slot's point, from the group's spell lines in the run log.
     */
    static List<String> spellsOpened(ScenarioNpcs npcs, ClimbWatch watch) {
        List<String> out = new ArrayList<>();
        for (ScenarioNpcs.LogLine line : npcs.log()) {
            java.util.regex.Matcher m = STUCK_AT.matcher(line.text());
            if (m.find()) {
                NavPoint at = new NavPoint("scene", Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)),
                    Double.parseDouble(m.group(4))); // the frame needs the coordinates only
                out.add(String.format(Locale.ROOT, "%s at %s, %s behind its slot along the path, %s from its point",
                    m.group(1), watch.realBlock(at), m.group(5), m.group(6)));
            }
        }
        return out;
    }
}
