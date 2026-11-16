package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.snapshot.NavSnapshot;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.scenario.SizedScene;
import net.knightsandkings.knk.paper.scenario.SnapshotReplay;

/**
 * Link B1 (PandiO/knk-workspace#670): a snapshot of {@code /knk npc nav snapshot} replayed on a real server. Loads
 * {@value #SNAPSHOT} (made from the developer's stair house by {@code tools/scenario-scenes/make_snapshot_sample.py}
 * until the developer's own snapshot of {@code quarry-to-keep} is committed), places its blocks from the region files,
 * builds its domains' WorldGuard regions and fake access data, and lets a leader alone walk the stored line with the
 * stored settings.
 * <p>
 * Checks the replay itself: every block placed, the regions built with their parents and found at the route's start
 * by the NPC rule checks' world, every stored setting known. The walk is report mode (one {@code KNK-NPC-REPORT} line):
 * links B2 to B4 judge it.
 */
public final class NpcSnapshotReplayScenario implements Scenario<ScenarioScene>, SizedScene {

    /** The committed snapshot, in the jar's {@value SnapshotReplay#RESOURCE_FOLDER}. */
    static final String SNAPSHOT = "stairhouse-sample.zip";
    static final int WALK_TICKS = 90 * 20;

    @Override
    public String name() {
        return "npc-snapshot-replay";
    }

    @Override
    public String description() {
        return "A snapshot zip replayed: blocks from its region files, its domains as regions with fake access data, "
            + "a leader on the stored line (report)";
    }

    private SnapshotReplay replay;

    /** The committed snapshot, read once. */
    private SnapshotReplay replay() {
        if (replay == null) {
            replay = SnapshotReplay.fromResource(SNAPSHOT);
        }
        return replay;
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

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        SnapshotReplay replay = replay();
        AtomicReference<SnapshotReplay.Placed> placed = new AtomicReference<>();
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<NavigationResult> result = new AtomicReference<>();
        List<String> regions = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        long[] started = {0};
        long[] took = {-1};
        AtomicReference<NpcMovementReport> report = new AtomicReference<>();
        plan.act("place the snapshot's blocks", () -> placed.set(replay.placeBlocks(scene)))
            .check("every block of the region files is placed in the scene", () -> {
                SnapshotReplay.Placed p = placed.get();
                if (p.placed() == 0 || p.outsideTheScene() != 0 || p.unknownStates() != 0 || p.skippedChunks() != 0
                    || !p.problems().isEmpty()) {
                    return "placed " + p.describe() + " " + p.problems();
                }
                NavPoint start = replay.from(scene);
                if (scene.world().getBlockAt(start.blockX(), start.blockY(), start.blockZ()).getType().isAir()) {
                    return "no floor under the route's start at " + start;
                }
                return null;
            })
            .act("build the snapshot's regions", () -> regions.addAll(replay.buildRegions(scene)))
            .cleanup("remove the snapshot's regions", () -> replay.removeRegions(scene))
            .check("the regions are built with their parents and the NPC checks see them at the start", () -> {
                if (regions.size() != replay.snapshot().domains().size()) {
                    return regions.size() + " regions built for " + replay.snapshot().domains().size() + " domains";
                }
                NavPoint start = replay.from(scene);
                Set<String> here = scene.services().npcRules().regionsAt(start.world(), start.blockX(),
                    start.blockY() + 1, start.blockZ());
                for (String id : List.of("town_1", "district_1000004")) {
                    if (!here.contains(replay.regionId(scene, id))) {
                        return "the NPC rule checks see " + here + " at the start, not " + id;
                    }
                }
                return null;
            })
            .act("start NPC navigation with the snapshot's settings, rules and line", () -> {
                npcs.set(ScenarioNpcs.start(scene, replay.loop(scene), replay.settings(rejected),
                    replay.accessRules(scene), replay.gates(scene)));
                npcs.get().plazas(replay.plazas(scene));
            })
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .check("every stored setting is known", () -> rejected.isEmpty() ? null : "rejected " + rejected)
            .act("a leader alone walks the stored line", () -> {
                report.set(new NpcMovementReport(scene, name()).phaseStarts(npcs.get()));
                NavPoint from = replay.from(scene);
                NavPoint to = replay.to(scene);
                NpcMover leader = npcs.get().spawn(new NavPoint(from.world(), from.x(), from.y() + 1, from.z()));
                started[0] = npcs.get().ticks();
                if (!npcs.get().service().navigate(leader.id(), new NavPoint(to.world(), to.x(), to.y() + 1, to.z()),
                    r -> {
                        result.set(r);
                        took[0] = npcs.get().ticks() - started[0];
                    })) {
                    throw new ScenarioAssertionError("the navigation service refused the leader's order");
                }
            })
            .await("the leader's trip ends or the time is up", WALK_TICKS + 20,
                () -> result.get() != null || npcs.get().ticks() - started[0] >= WALK_TICKS)
            .check("report: the leader on the replayed route", () -> {
                NpcMovementReport r = report.get();
                r.add("snapshot", SNAPSHOT + " (" + replay.snapshot().manifest().route() + ", "
                        + replay.snapshot().route().line().size() + " points)")
                    .add("blocks", placed.get().describe())
                    .add("regions", regions.size())
                    .add("leader", result.get() == null ? "still walking after " + WALK_TICKS / 20 + " s"
                        : result.get().getClass().getSimpleName() + String.format(Locale.ROOT, " after %.1f s", took[0] / 20.0))
                    .add("fell", r.count(npcs.get(), Event.FELL))
                    .add("stuck spells", r.count(npcs.get(), Event.STUCK_ENGINE) + r.count(npcs.get(), Event.STUCK_NO_PROGRESS))
                    .add("trespasses", r.count(npcs.get(), Event.TRESPASS))
                    .log("a leader alone on the replay");
                return null;
            });
    }
}
