package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.navigation.shape.NavGeometry;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcOffRoadScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.scenario.SizedScene;

/**
 * Link C1 (PandiO/knk-workspace#700): an off-road start or goal walked with the walk navigator's search, on a scene
 * where the straight line cut into hops (before C1) does not get there ({@link NpcOffRoadScene}):
 * <ol>
 *   <li>{@code npc-offroad-detour}: a goal in a walled courtyard whose gate is on the far side from the road;</li>
 *   <li>{@code npc-offroad-upstairs}: a goal on top of a tower, up a spiral stair, with a group of 5 after the
 *       single NPC (its followers take the leader's walked path up the stair);</li>
 *   <li>{@code npc-offroad-ravine}: a start across a ravine from the road's nearest point; the road is joined where the
 *       walk reaches it, on the bridge (the road join by reachability).</li>
 * </ol>
 * Settings: the code defaults with the developer's {@code local-legs-clearance} and {@code corridor-steering} on (the
 * dev server's of 2026-11-17). Each walk fails when the NPC does not arrive in time, falls into the ravine, gets stuck
 * on the way (a stuck spell), or when no off-road part was planned with the walk search. With
 * {@code -Dknk.scenario.offRoadHops=true} the NPCs walk the off-road parts in hops, as before C1 (the before run).
 */
public final class NpcOffRoadScenario implements Scenario<ScenarioScene>, SizedScene {

    static final int WALK_TICKS = 90 * 20;
    static final int GROUP_TICKS = 120 * 20;
    /** Followers keep walking this long after the leader arrived. */
    static final int SETTLE_TICKS = 15 * 20;
    static final int FOLLOWERS = 4;

    private final NpcOffRoadScene.Kind kind;

    public NpcOffRoadScenario(NpcOffRoadScene.Kind kind) {
        this.kind = kind;
    }

    @Override
    public String name() {
        return "npc-offroad-" + kind.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String description() {
        return switch (kind) {
            case DETOUR -> "An off-road goal in a courtyard gated on the far side, walked round with the walk search";
            case UPSTAIRS -> "An off-road goal up a tower's spiral stair, an NPC and a group of 5, with the walk search";
            case RAVINE -> "An off-road start across a ravine from the road: the road joined where the walk reaches it";
        };
    }

    @Override
    public ScenarioScene.Box box() {
        return NpcOffRoadScene.BOX;
    }

    static NavigationSettings settings() {
        return NavigationSettings.builder().global(NavigationSettings.Overrides
            .of(NavigationSettings.LOCAL_LEGS_CLEARANCE, true)
            .with(NavigationSettings.CORRIDOR_STEERING, true)).build();
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcOffRoadScene offRoad = NpcOffRoadScene.in(scene, kind);
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        List<String> fell = new ArrayList<>();
        plan.act("build the scene", offRoad::build)
            .act("start NPC navigation on Citizens", () -> {
                npcs.set(ScenarioNpcs.start(scene, offRoad.loop(), settings()));
                npcs.get().everyTick(() -> {
                    for (NpcMover mover : npcs.get().movers()) {
                        NavPoint at = mover.position().orElse(null);
                        if (at != null && offRoad.inTheRavine(at) && fell.size() < 10
                            && fell.stream().noneMatch(f -> f.startsWith(mover.name() + " "))) {
                            fell.add(mover.name() + " at " + NavGeometry.block(at));
                        }
                    }
                });
            })
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            });

        AtomicReference<NavigationResult> result = new AtomicReference<>();
        long[] t = {0, -1};
        plan.act("one NPC walks from " + where(offRoad.start()) + " to the goal", () -> {
                NpcMover npc = npcs.get().spawn(offRoad.start());
                t[0] = npcs.get().ticks();
                if (!npcs.get().service().navigate(npc.id(), offRoad.goal(), r -> {
                    result.set(r);
                    t[1] = npcs.get().ticks() - t[0];
                })) {
                    throw new ScenarioAssertionError("the navigation service refused the order");
                }
            })
            .await("the trip ends or the time is up", WALK_TICKS + 20,
                () -> result.get() != null || npcs.get().ticks() - t[0] >= WALK_TICKS)
            .check("the NPC arrived on the walk search's path", () -> {
                List<String> failures = new ArrayList<>();
                report(npcs.get(), "one NPC", result.get(), t[1], fell);
                if (!(result.get() instanceof NavigationResult.Arrived)) {
                    failures.add("the NPC did not arrive: " + (result.get() == null ? "still walking after "
                        + WALK_TICKS / 20 + " s at " + npcs.get().movers().get(0).position().map(NavGeometry::block)
                        .orElse("-") : result.get()));
                }
                if (!fell.isEmpty()) {
                    failures.add("fell into the ravine: " + fell);
                }
                long stuck = stuckSpells(npcs.get());
                if (stuck > 0) {
                    failures.add(stuck + " stuck spells on the way");
                }
                if (npcs.get().service().offRoadWalks() == 0) {
                    failures.add("no off-road part was planned with the walk search ("
                        + npcs.get().service().offRoadHops() + " in hops)");
                }
                npcs.get().remove(new ArrayList<>(npcs.get().movers()));
                return failures.isEmpty() ? null : String.join("; ", failures) + npcs.get().tail(20);
            });
        if (kind == NpcOffRoadScene.Kind.UPSTAIRS) {
            group(plan, scene, offRoad, npcs);
        }
    }

    /** A group of 5 in a column up the same stair: the leader arrives, every follower ends up the stair, none lost. */
    private void group(ScenarioPlan plan, ScenarioScene scene, NpcOffRoadScene offRoad,
                       AtomicReference<ScenarioNpcs> npcs) {
        AtomicReference<NavigationResult> result = new AtomicReference<>();
        AtomicReference<NavGroup> group = new AtomicReference<>();
        List<NpcMover> members = new ArrayList<>();
        long[] t = {0, -1};
        plan.act("a group of 5 walks the same way", () -> {
                members.add(npcs.get().spawn(offRoad.start()));
                for (int i = 0; i < FOLLOWERS; i++) {
                    members.add(npcs.get().spawn(offRoad.followerStart(i)));
                }
                t[0] = npcs.get().ticks();
                group.set(npcs.get().group(members, Formation.Type.COLUMN));
                group.get().navigate(offRoad.goal(), r -> {
                    result.set(r);
                    t[1] = npcs.get().ticks() - t[0];
                });
            })
            .await("the leader arrived " + SETTLE_TICKS / 20 + " s ago, or the time is up", GROUP_TICKS + 20, () -> {
                long now = npcs.get().ticks();
                return (result.get() != null && now - t[0] - t[1] >= SETTLE_TICKS) || now - t[0] >= GROUP_TICKS;
            })
            .check("the group went up the stair after its leader", () -> {
                List<String> failures = new ArrayList<>();
                report(npcs.get(), "a group of 5", result.get(), t[1], List.of());
                if (!(result.get() instanceof NavigationResult.Arrived)) {
                    failures.add("the leader did not arrive: " + result.get());
                }
                int upHigh = scene.worldY(NpcOffRoadScene.GROUND + 9);
                for (NpcMover m : members.subList(1, members.size())) {
                    NavPoint at = m.position().orElse(null);
                    if (at == null || at.y() < upHigh) {
                        failures.add(m.name() + " is not up the stair: " + (at == null ? "-" : NavGeometry.block(at)));
                    }
                }
                if (group.get().lost() > 0) {
                    failures.add(group.get().lost() + " followers lost");
                }
                long stuck = stuckSpells(npcs.get());
                if (stuck > 0) {
                    failures.add(stuck + " stuck spells in both walks");
                }
                return failures.isEmpty() ? null : String.join("; ", failures) + npcs.get().tail(20);
            });
    }

    private static long stuckSpells(ScenarioNpcs npcs) {
        return npcs.metrics().total(Event.STUCK_ENGINE) + npcs.metrics().total(Event.STUCK_NO_PROGRESS);
    }

    private static String where(NavPoint feet) {
        return NavGeometry.block(feet);
    }

    private void report(ScenarioNpcs npcs, String phase, NavigationResult result, long took, List<String> fell) {
        String line = String.format(Locale.ROOT, "KNK-NPC-REPORT %s %s: %s; off-road walks %d, in hops %d; stuck spells "
                + "%d; falls %d; local legs %s; fell into the ravine %s", name(), phase,
            result == null ? "still walking" : result.getClass().getSimpleName() + String.format(Locale.ROOT,
                " after %.1f s", took / 20.0), npcs.service().offRoadWalks(), npcs.service().offRoadHops(),
            npcs.metrics().total(Event.STUCK_ENGINE) + npcs.metrics().total(Event.STUCK_NO_PROGRESS),
            npcs.metrics().total(Event.FELL), npcs.localLegs(), fell.isEmpty() ? "none" : fell.toString());
        org.bukkit.Bukkit.getLogger().info(line);
    }
}
