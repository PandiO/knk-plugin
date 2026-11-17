package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcMovementScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.scenario.SizedScene;

/**
 * Link B1 (PandiO/knk-workspace#670), report mode, the light scene: the climb through the developer's whole stair house
 * on the real road line of the traces of 2026-11-16 ({@link NpcMovementScene#climbLine}), entering from the street in
 * the east and going up all five flights of the stacked hairpins to the top hall (chain report decisions 33 to 35).
 * One phase: a group of 20 in ranks of 3 with 20 singles beside it (the smoke test's crowd), with short stretches
 * ({@value #STRETCH_BLOCKS} blocks) and the movement keys as on the developer's server during the failed smoke test
 * (decision 35.3). The leader alone and the group of 20 on the real blocks, regions and settings run in
 * {@code npc-replay-quarry-to-keep}.
 * <p>
 * It reports what {@link ClimbWatch} sees: falls (from where to where), places of the climb skipped or walked back to,
 * stretch handovers and those onto another level, the queue releases by the crowd's 30 s guard ("let go"), stuck
 * spells and where each NPC got to. On {@code next} before link B2 these showed the smoke test's failures: a fall under
 * a landing, a skipped flight, and the queue let go by the guard. Since link B2 (PandiO/knk-workspace#671) it fails on
 * a fall from a flight or landing, a handover onto another level, a turn released by the 30 s guard after its holder
 * fell, a group leader that does not arrive and a lost follower ({@link ClimbWatch#failures}); since link B4
 * (PandiO/knk-workspace#673) also on a real jump (by upward speed) on a flight or its entry; the rest stays report
 * only. Needs Citizens.
 */
public final class NpcStairhouseClimbScenario implements Scenario<ScenarioScene>, SizedScene {

    /** Short stretches: the flights are 10 to 13 blocks long, so a handover falls on most hairpins. */
    static final double STRETCH_BLOCKS = 8;
    static final int MEMBERS = 20;
    static final int GROUP_TICKS = 150 * 20;
    /** Singles sent behind the group in the last phase (the smoke test's 39-NPC queue: 19 followers and these). */
    static final int SINGLES = 20;
    /**
     * With the singles beside the group, everybody keeps walking this long after the leader's trip ended, so a queue
     * held longer than the crowd's 30 s guard shows ({@code let go}).
     */
    static final int CROWD_SETTLE_TICKS = 75 * 20;

    @Override
    public String name() {
        return "npc-stairhouse-climb";
    }

    @Override
    public String description() {
        return "A group of 20 with 20 singles from the street east of the stair house up all five flights "
            + "with short stretches (the smoke test's crowd of 2026-11-16)";
    }

    @Override
    public ScenarioScene.Box box() {
        return NpcMovementScene.STAIRHOUSE_BOX;
    }

    /**
     * The developer's server during the smoke test of 2026-11-16 (chain report decision 35.3): stationary and
     * no-progress ticks 60, group spacing 2.0, formation keeping {@code levels} (unset), corridor steering, local legs
     * with clearance, plaza lines, crowd awareness and stop-on-trespass on, collision off; plus short stretches and
     * ranks of 3 (as link M1's group).
     */
    static NavigationSettings smokeTestSettings() {
        return NavigationSettings.builder()
            .global(NavigationSettings.Overrides.of(NavigationSettings.COLLIDABLE, false)
                .with(NavigationSettings.STRETCH_BLOCKS, STRETCH_BLOCKS)
                .with(NavigationSettings.STATIONARY_TICKS, 60)
                .with(NavigationSettings.NO_PROGRESS_TICKS, 60)
                .with(NavigationSettings.GROUP_SPACING, 2.0)
                .with(NavigationSettings.GROUP_RANKS_WIDTH, 3)
                .with(NavigationSettings.GROUP_FORMATION_KEEPING, "levels")
                .with(NavigationSettings.CORRIDOR_STEERING, true)
                .with(NavigationSettings.LOCAL_LEGS_CLEARANCE, true)
                .with(NavigationSettings.PLAZA_LINES, true)
                .with(NavigationSettings.CROWD_AWARENESS, true)
                .with(NavigationSettings.STOP_ON_TRESPASS, true)).build();
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcMovementScene house = NpcMovementScene.stairHouse(scene);
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<ClimbWatch> watch = new AtomicReference<>();
        AtomicReference<String> built = new AtomicReference<>();
        AtomicReference<NpcMovementReport> report = new AtomicReference<>();
        plan.act("paste the whole stair house", () -> built.set(house.build()))
            .act("start NPC navigation on Citizens with the smoke test's settings", () -> {
                npcs.set(ScenarioNpcs.start(scene, house.climbLoop(), smokeTestSettings()));
                npcs.get().everyTick(() -> {
                    if (watch.get() != null) {
                        watch.get().look(npcs.get().movers());
                    }
                });
            })
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            });
        groupPhase(plan, scene, house, npcs, watch, report, built, SINGLES, CROWD_SETTLE_TICKS,
            "a group of 20 in ranks with " + SINGLES + " singles beside it");
    }

    /**
     * One group phase: {@value #MEMBERS} NPCs in ranks from the street east of the house to the top hall, with
     * {@code singles} single NPCs sent the same way at the same time (the smoke test's 39 at the top landing: a group
     * of 20 and the singles walking with it). The phase ends {@code settle} ticks after the leader's trip ended, or once
     * everybody is in the top hall, or after {@value #GROUP_TICKS} ticks; then the group is dismissed.
     */
    private void groupPhase(ScenarioPlan plan, ScenarioScene scene, NpcMovementScene house,
                            AtomicReference<ScenarioNpcs> npcs, AtomicReference<ClimbWatch> watch,
                            AtomicReference<NpcMovementReport> report, AtomicReference<String> built, int singles,
                            int settle, String label) {
        List<NpcMover> members = new ArrayList<>();
        List<NpcMover> walkers = new ArrayList<>();
        AtomicReference<NavigationResult> groupResult = new AtomicReference<>();
        AtomicReference<NavGroup> group = new AtomicReference<>();
        long[] started = {0};
        long[] took = {-1};
        plan.act(label + " walks the same way", () -> {
                report.set(new NpcMovementReport(scene, name()).phaseStarts(npcs.get()));
                watch.set(new ClimbWatch(house, npcs.get().service()).stairHopsIn(scene.world()));
                for (int i = 0; i < MEMBERS; i++) {
                    members.add(npcs.get().spawn(house.climbStart(i)));
                }
                for (int i = 0; i < singles; i++) {
                    NpcMover single = npcs.get().spawn(house.climbBeside(i));
                    walkers.add(single);
                    npcs.get().service().navigate(single.id(), house.climbGoal(), r -> { });
                }
                started[0] = npcs.get().ticks();
                group.set(npcs.get().group(members, Formation.Type.RANKS));
                group.get().navigate(house.climbGoal(), r -> {
                    groupResult.set(r);
                    took[0] = npcs.get().ticks() - started[0];
                });
            })
            .await("everybody is in the top hall, or the leader's trip ended " + settle / 20
                + " s ago, or the time is up", GROUP_TICKS + 20, () -> {
                    long now = npcs.get().ticks();
                    List<NpcMover> all = new ArrayList<>(members);
                    all.addAll(walkers);
                    return watch.get().reached(all, NpcMovementScene.climbPlaces() - 1) == all.size()
                        || (groupResult.get() != null && now - started[0] - took[0] >= settle)
                        || now - started[0] >= GROUP_TICKS;
                })
            .check("report: " + label, () -> {
                List<NpcMover> all = new ArrayList<>(members);
                all.addAll(walkers);
                report(report.get(), npcs.get(), watch.get(), all, groupResult.get(), took[0], GROUP_TICKS,
                    built.get(), group.get(), house).log(label);
                List<String> failures = new ArrayList<>(watch.get().failures());
                if (!(groupResult.get() instanceof NavigationResult.Arrived)) {
                    failures.add("the group's leader did not arrive: " + groupResult.get());
                }
                if (group.get().lost() > 0) {
                    failures.add(group.get().lost() + " followers lost");
                }
                if (!watch.get().jumpsOnClimb().isEmpty()) {
                    // link B4 (PandiO/knk-workspace#673): waypoints at the real surface, no jump toward a waypoint; the
                    // engine's own hops off a stair's step are reported, not failed
                    failures.add("jumps toward a waypoint on the flights and their entries "
                        + ClimbWatch.first(watch.get().jumpsOnClimb(), 3));
                }
                npcs.get().dismiss(group.get());
                npcs.get().remove(walkers);
                return failures.isEmpty() ? null : String.join("; ", failures);
            });
    }

    private static NpcMovementReport report(NpcMovementReport report, ScenarioNpcs npcs, ClimbWatch watch,
                                            List<NpcMover> movers, NavigationResult result, long took, int maxTicks,
                                            String built, NavGroup group, NpcMovementScene house) {
        var crowd = npcs.service().crowd();
        report.add("scene", built)
            .add("stretch blocks", STRETCH_BLOCKS)
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
            .add("jumps toward a waypoint on the flights and their entries", ClimbWatch.first(watch.jumpsOnClimb(), 4))
            .add("queue let go by the 30 s guard", crowd.overdue())
            .add("why the guard let them go (B3)", ClimbWatch.first(watch.releases(npcs::nameOf), 6))
            .add("turns dropped by the 30 s guard", ClimbWatch.first(watch.staleTurns(), 3))
            .add("fell (service count)", report.count(npcs, Event.FELL))
            .add("line steps walked as local legs", npcs.service().gapLegs())
            .add("stuck spells", report.count(npcs, Event.STUCK_ENGINE) + report.count(npcs, Event.STUCK_NO_PROGRESS))
            .add("longest still", String.format(Locale.ROOT, "%.1f s, %s", npcs.longestStillTicks() / 20.0,
                house.realPosition(npcs.longestStillWho())));
        if (group != null) {
            report.add("width now", group.width().describe()).add("lost", group.lost())
                .add("leader falls kept out of the group path", group.leaderFalls());
        }
        return report.crowd(npcs);
    }
}
