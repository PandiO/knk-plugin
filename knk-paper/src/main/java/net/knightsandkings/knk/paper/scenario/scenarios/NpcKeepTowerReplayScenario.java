package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.navigation.shape.NavGeometry;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.HopPlanner;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.scenario.SizedScene;
import net.knightsandkings.knk.paper.scenario.SnapshotReplay;

/**
 * Link C1 (PandiO/knk-workspace#700): the developer's snapshot of {@code northern-wearway-upper-keep-tower} (dev server,
 * 2026-11-17 19:37, plugin {@code 0.1.0+e69dba6a}) replayed. The route's road ends in the keep at 1409 84 -516; its
 * goal is 1401 113 -502, through the throne room and up the tower's spiral staircase. In the smoke test of 2026-11-17
 * one NPC walked the road well and then stuck at 1405 85 -510: its hop to the goal, 28 blocks up, found no path.
 * <p>
 * {@value #SNAPSHOT} is that zip trimmed to the window over the keep and the tower
 * ({@code tools/scenario-scenes/trim_snapshot.py}: x 1391..1454, y 66..130, z -559..-480; the region files hold whole
 * columns, so the goal above the snapshot's own box (y 96) is there). The whole 17.6 MB zip is on knk-plugin
 * {@code evidence/smoke-2026-11-16}, folder {@code knk-paper/src/test/resources/scenario/evidence/2026-11-17/}.
 * <p>
 * With the stored settings (the developer's dev config) a leader alone, then a group of 20 in ranks, walk from the
 * keep's road (1420 82 -531) to the goal. Each fails when the leader does not arrive in time; the group also when a
 * follower is lost or has not come up into the tower (above the throne room's floor, within 24 blocks of the goal).
 */
public final class NpcKeepTowerReplayScenario implements Scenario<ScenarioScene>, SizedScene {

    /** The committed, trimmed snapshot, in the jar's {@value SnapshotReplay#RESOURCE_FOLDER}. */
    static final String SNAPSHOT = "keep-tower.zip";
    static final int MEMBERS = 20;
    static final int LEADER_TICKS = 150 * 20;
    static final int GROUP_TICKS = 240 * 20;
    /** Followers keep walking this long after the leader arrived. */
    static final int SETTLE_TICKS = 30 * 20;
    /** The start on the keep's road and the goal at the top of the spiral staircase (real feet). */
    static final double[] START = {1420.5, 82, -530.5};
    static final double[] GOAL = {1401.5, 113, -502.5};
    /** The throne room's floor level (real feet): a follower above it has come into the tower. */
    static final double TOWER_FEET = 86;
    static final double NEAR_GOAL = 24;

    private SnapshotReplay replay;

    private SnapshotReplay replay() {
        if (replay == null) {
            replay = SnapshotReplay.fromResource(SNAPSHOT);
        }
        return replay;
    }

    @Override
    public String name() {
        return "npc-replay-keep-tower";
    }

    @Override
    public String description() {
        return "The developer's snapshot of northern-wearway-upper-keep-tower replayed: a leader alone and a group of 20 "
            + "from the keep's road up the tower's spiral staircase to the goal";
    }

    @Override
    public ScenarioScene.Box box() {
        return NpcSnapshotReplayScenario.boxOf(replay().snapshot());
    }

    private static NavPoint feet(ScenarioScene scene, SnapshotReplay replay, double[] real) {
        return replay.inScene(scene, real);
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        SnapshotReplay replay = replay();
        AtomicReference<SnapshotReplay.Placed> placed = new AtomicReference<>();
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        List<String> rejected = new ArrayList<>();
        plan.act("place the snapshot's blocks", () -> placed.set(replay.placeBlocks(scene)))
            .check("the blocks are placed, the goal's floor and the start's are there", () -> {
                SnapshotReplay.Placed p = placed.get();
                if (p.placed() == 0 || p.skippedChunks() != 0 || !p.problems().isEmpty()) {
                    return "placed " + p.describe() + " " + p.problems();
                }
                for (double[] real : List.of(START, GOAL)) {
                    NavPoint under = feet(scene, replay, new double[] {real[0], real[1] - 1, real[2]});
                    if (scene.world().getBlockAt(under.blockX(), under.blockY(), under.blockZ()).getType().isAir()) {
                        return "no floor under " + real[0] + " " + real[1] + " " + real[2];
                    }
                }
                return null;
            })
            .act("build the snapshot's regions", () -> replay.buildRegions(scene))
            .cleanup("remove the snapshot's regions", () -> replay.removeRegions(scene))
            .act("start NPC navigation with the snapshot's settings", () -> {
                npcs.set(ScenarioNpcs.start(scene, replay.loop(scene), replay.settings(rejected),
                    replay.accessRules(scene), replay.gates(scene)));
                npcs.get().plazas(replay.plazas(scene));
            })
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .check("every stored setting is known", () -> rejected.isEmpty() ? null : "rejected " + rejected);

        // a leader alone
        AtomicReference<NavigationResult> leaderResult = new AtomicReference<>();
        List<NpcMover> alone = new ArrayList<>();
        long[] t = {0, -1};
        plan.act("a leader alone walks from the keep's road up the spiral staircase", () -> {
                NpcMover leader = npcs.get().spawn(feet(scene, replay, START));
                alone.add(leader);
                t[0] = npcs.get().ticks();
                if (!npcs.get().service().navigate(leader.id(), feet(scene, replay, GOAL), r -> {
                    leaderResult.set(r);
                    t[1] = npcs.get().ticks() - t[0];
                })) {
                    throw new ScenarioAssertionError("the navigation service refused the leader's order");
                }
            })
            .await("the leader's trip ends or the time is up", LEADER_TICKS + 20,
                () -> leaderResult.get() != null || npcs.get().ticks() - t[0] >= LEADER_TICKS)
            .check("report: the leader alone arrives", () -> {
                report(scene, replay, npcs.get(), "a leader alone", alone, leaderResult.get(), t[1], null);
                String failure = leaderResult.get() instanceof NavigationResult.Arrived ? null
                    : "the leader did not arrive: " + (leaderResult.get() == null ? "still walking after "
                        + LEADER_TICKS / 20 + " s at " + realBlock(scene, replay, alone.get(0)) : leaderResult.get())
                        + "; blocks round it: " + around(scene, replay, alone.get(0)) + "; line round it: "
                        + lineAround(scene, replay, npcs.get(), alone.get(0)) + npcs.get().tail(16);
                npcs.get().remove(alone);
                return failure;
            });

        // a group of 20 in ranks
        AtomicReference<NavigationResult> groupResult = new AtomicReference<>();
        AtomicReference<NavGroup> group = new AtomicReference<>();
        List<NpcMover> members = new ArrayList<>();
        long[] g = {0, -1};
        plan.act("a group of 20 in ranks walks the same way", () -> {
                for (NavPoint p : startsBehind(scene, replay)) {
                    members.add(npcs.get().spawn(p));
                }
                g[0] = npcs.get().ticks();
                group.set(npcs.get().group(members, Formation.Type.RANKS));
                group.get().navigate(feet(scene, replay, GOAL), r -> {
                    groupResult.set(r);
                    g[1] = npcs.get().ticks() - g[0];
                });
            })
            .await("the leader arrived " + SETTLE_TICKS / 20 + " s ago, or the time is up", GROUP_TICKS + 20, () -> {
                long now = npcs.get().ticks();
                return (groupResult.get() != null && now - g[0] - g[1] >= SETTLE_TICKS) || now - g[0] >= GROUP_TICKS;
            })
            .check("report: the group arrives and its followers come up into the tower", () -> {
                report(scene, replay, npcs.get(), "a group of 20 in ranks", members, groupResult.get(), g[1],
                    group.get());
                List<String> failures = new ArrayList<>();
                if (!(groupResult.get() instanceof NavigationResult.Arrived)) {
                    failures.add("the group's leader did not arrive: " + groupResult.get());
                }
                if (group.get().lost() > 0) {
                    failures.add(group.get().lost() + " followers lost");
                }
                NavPoint goal = feet(scene, replay, GOAL);
                List<String> behind = new ArrayList<>();
                for (NpcMover m : members.subList(1, members.size())) {
                    NavPoint at = m.position().orElse(null);
                    double[] real = at == null ? null : replay.real(scene, at);
                    if (real == null || real[1] < TOWER_FEET || Trail3.dist(at, goal) > NEAR_GOAL) {
                        behind.add(m.name() + " at " + realBlock(scene, replay, m));
                    }
                }
                if (!behind.isEmpty()) {
                    failures.add(behind.size() + " followers not up in the tower: "
                        + String.join(", ", behind.subList(0, Math.min(6, behind.size()))));
                }
                return failures.isEmpty() ? null : String.join("; ", failures) + npcs.get().tail(16);
            });
    }

    /** The group's start: the leader at {@link #START}, the others on the road behind it, a block apart along it. */
    private static List<NavPoint> startsBehind(ScenarioScene scene, SnapshotReplay replay) {
        List<NavPoint> line = HopPlanner.resampleKeepingCorners(replay.line(scene), 1.0);
        NavPoint start = feet(scene, replay, START);
        int at = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < line.size(); i++) {
            double d = Trail3.dist(HopPlanner.feet(line.get(i)), start);
            if (d < best) {
                best = d;
                at = i;
            }
        }
        List<NavPoint> out = new ArrayList<>();
        out.add(start);
        for (int i = at - 2; i >= 0 && out.size() < MEMBERS; i -= 1) {
            NavPoint p = line.get(i);
            out.add(new NavPoint(p.world(), p.blockX() + 0.5, p.blockY() + 1, p.blockZ() + 0.5));
        }
        return out;
    }

    /** The blocks that are not air round an NPC's feet (real x y z), for a failure that says why it stopped there. */
    private static String around(ScenarioScene scene, SnapshotReplay replay, NpcMover m) {
        NavPoint at = m.position().orElse(null);
        if (at == null) {
            return "-";
        }
        StringBuilder out = new StringBuilder();
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    org.bukkit.block.Block b = scene.world().getBlockAt(at.blockX() + dx, at.blockY() + dy,
                        at.blockZ() + dz);
                    if (!b.getType().isAir()) {
                        double[] r = replay.real(scene, new NavPoint(at.world(), b.getX(), b.getY(), b.getZ()));
                        out.append(String.format(Locale.ROOT, " %d,%d,%d=%s", Math.round(r[0]), Math.round(r[1]),
                            Math.round(r[2]), b.getType().name().toLowerCase(Locale.ROOT)));
                    }
                }
            }
        }
        return out.toString();
    }

    /** The route line's points within 4 blocks of an NPC (real floor x y z, w for a walk search's cell). */
    private static String lineAround(ScenarioScene scene, SnapshotReplay replay, ScenarioNpcs npcs, NpcMover m) {
        NavPoint at = m.position().orElse(null);
        net.knightsandkings.knk.core.npc.nav.RouteFollower f = npcs.service().followerOf(m.id());
        if (at == null || f == null) {
            return "-";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < f.line().size(); i++) {
            NavPoint p = f.line().get(i);
            if (Math.abs(p.x() - at.x()) <= 4 && Math.abs(p.z() - at.z()) <= 4 && Math.abs(p.y() + 1 - at.y()) <= 4) {
                double[] r = replay.real(scene, p);
                out.append(String.format(Locale.ROOT, " %d:%.1f,%.1f,%.1f%s", i, r[0], r[1], r[2],
                    f.walked(i) ? "w" : ""));
            }
        }
        return out.toString();
    }

    private static String realBlock(ScenarioScene scene, SnapshotReplay replay, NpcMover m) {
        NavPoint at = m.position().orElse(null);
        if (at == null) {
            return "-";
        }
        double[] r = replay.real(scene, at);
        return String.format(Locale.ROOT, "%d %d %d", (int) Math.floor(r[0]), (int) Math.floor(r[1] + 0.01),
            (int) Math.floor(r[2]));
    }

    private void report(ScenarioScene scene, SnapshotReplay replay, ScenarioNpcs npcs, String phase,
                        List<NpcMover> movers, NavigationResult result, long took, NavGroup group) {
        StringBuilder where = new StringBuilder();
        for (NpcMover m : movers.subList(0, Math.min(movers.size(), 6))) {
            where.append(where.length() == 0 ? "" : ", ").append(m.name()).append(' ')
                .append(realBlock(scene, replay, m));
        }
        org.bukkit.Bukkit.getLogger().info(String.format(Locale.ROOT, "KNK-NPC-REPORT %s %s: %s; off-road walks %d, in "
                + "hops %d; stuck spells %d; falls %d; local legs %s; lost %s; got to %s", name(), phase,
            result == null ? "still walking" : result.getClass().getSimpleName() + String.format(Locale.ROOT,
                " after %.1f s", took / 20.0), npcs.service().offRoadWalks(), npcs.service().offRoadHops(),
            npcs.metrics().total(Event.STUCK_ENGINE) + npcs.metrics().total(Event.STUCK_NO_PROGRESS),
            npcs.metrics().total(Event.FELL), npcs.localLegs(), group == null ? "-" : String.valueOf(group.lost()),
            where));
        for (ScenarioNpcs.LogLine line : npcs.log()) {
            if (line.text().contains("off-road walk") || line.text().contains("walk-only")) {
                org.bukkit.Bukkit.getLogger().info("KNK-NPC-WALK " + line.text());
            }
        }
    }

    /** 3D distance. */
    private static final class Trail3 {
        static double dist(NavPoint a, NavPoint b) {
            return Math.sqrt((a.x() - b.x()) * (a.x() - b.x()) + (a.y() - b.y()) * (a.y() - b.y())
                + (a.z() - b.z()) * (a.z() - b.z()));
        }
    }

    static String block(NavPoint p) {
        return NavGeometry.block(p);
    }
}
