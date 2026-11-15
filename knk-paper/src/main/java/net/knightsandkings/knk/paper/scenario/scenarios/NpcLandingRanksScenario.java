package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.npc.nav.NavGeometry;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavState;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcLedgeScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * The #492 landing in its real geometry (PandiO/knk-workspace#592): the approach at the landing's level, the landing's
 * inside corner against the building's walls, a wooden floor overhanging the corner (2.5 blocks of headroom), then a
 * flight up at 90°. A group of 12 in {@code ranks} walks up through it once, as on {@code quarry-to-keep}: the leader
 * arrives, every follower closes up to its slot, no follower stands in one block at the landing for more than 3 s
 * while the leader walks on, every stuck spell is freed within 2 recovery steps and followers rejoin rarely. Needs
 * Citizens.
 */
public final class NpcLandingRanksScenario implements Scenario<ScenarioScene> {

    static final int MAX_TICKS = 90 * 20;
    /** Ticks a follower may stand in one block at the landing while the leader walks on. */
    static final int MAX_STILL = 60;
    /** Blocks a follower may be behind its slot once the group has arrived and closed up. */
    static final double MAX_LAG = 2.5;
    static final int MEMBERS = 12;

    @Override
    public String name() {
        return "npc-landing-ranks";
    }

    @Override
    public String description() {
        return "The #492 landing as built: a group of 12 in ranks walks up through it, nobody stays at the landing";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcLedgeScene ledge = NpcLedgeScene.in(scene);
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<NavGroup> group = new AtomicReference<>();
        AtomicReference<NavigationResult> result = new AtomicReference<>();
        Map<Integer, int[]> lastBlock = new HashMap<>();
        Map<Integer, Integer> still = new HashMap<>();
        AtomicReference<String> longest = new AtomicReference<>("-");
        int[] longestTicks = {0};
        plan.act("build the landing", ledge::buildLanding)
            .act("start NPC navigation on Citizens", () -> npcs.set(ScenarioNpcs.start(scene, ledge.landingLine(),
                NavigationSettings.defaults())))
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .act("a group of 12 in ranks walks up through the landing", () -> {
                List<NpcMover> members = new ArrayList<>();
                for (int i = 0; i < MEMBERS; i++) {
                    members.add(npcs.get().spawn(ledge.feet(24.5 + (i % 3), 3, 22.5 + (i / 3) * 1.5)));
                }
                group.set(npcs.get().group(members, Formation.Type.RANKS));
                group.get().navigate(ledge.landingEnd(), result::set);
                npcs.get().everyTick(() -> {
                    // only while the leader walks on: a follower waiting in its slot while the leader waits for it
                    // is not stuck
                    boolean leaderWalks = !group.get().holding() && npcs.get().service()
                        .state(group.get().leader().id()) == NpcNavState.MOVING;
                    for (NpcMover mover : npcs.get().movers()) {
                        NavPoint at = mover.position().orElse(null);
                        if (at == null || mover.id() == group.get().leader().id()) {
                            continue;
                        }
                        int[] block = {at.blockX(), at.blockY(), at.blockZ()};
                        int[] last = lastBlock.put(mover.id(), block);
                        int ticks = leaderWalks && ledge.onTheLanding(at) && last != null
                            && java.util.Arrays.equals(last, block) ? still.getOrDefault(mover.id(), 0) + 1 : 0;
                        still.put(mover.id(), ticks);
                        if (ticks > longestTicks[0]) {
                            longestTicks[0] = ticks;
                            longest.set(mover.name() + " at " + NavGeometry.block(at));
                        }
                    }
                });
            })
            .await("the group's trip ends", MAX_TICKS, () -> result.get() != null)
            .check("the leader arrived", () -> result.get() instanceof NavigationResult.Arrived ? null
                : "the trip ended " + result.get() + ": " + npcs.get().stats() + npcs.get().tail(40))
            .waitTicks(100)
            .check("every follower closed up to its slot", () -> group.get().maxLag() <= MAX_LAG && group.get().lost() == 0
                ? null : "furthest behind its slot " + group.get().maxLag() + " blocks, lost " + group.get().lost() + "; "
                    + npcs.get().stats() + npcs.get().tail(40))
            .check("no follower stood in one block at the landing for more than 3 s while the leader walked",
                () -> longestTicks[0] <= MAX_STILL ? null : "longest " + longestTicks[0] + " ticks: " + longest.get()
                    + "; " + npcs.get().stats() + npcs.get().tail(40))
            .check("every stuck spell was freed within 2 recovery steps", () -> {
                var spells = npcs.get().metrics().spells();
                return spells.maxSteps() <= 2 && npcs.get().metrics().total(Event.NOT_FREED) == 0 ? null
                    : npcs.get().stats() + npcs.get().tail(40);
            })
            // followers spawned behind the leader walk an own leg back onto its path at the start (a rejoin each)
            .check("followers rejoined rarely", () -> npcs.get().metrics().total(Event.GROUP_REJOIN) <= MEMBERS / 2 ? null
                : "rejoins " + npcs.get().metrics().total(Event.GROUP_REJOIN) + ": " + npcs.get().stats()
                    + npcs.get().tail(40))
            .check("nobody fell or trespassed", () -> npcs.get().metrics().total(Event.FELL) == 0
                && npcs.get().metrics().total(Event.TRESPASS) == 0 ? null : npcs.get().stats() + npcs.get().tail(40))
            .check("the counters add up", () -> npcs.get().countersAddUp());
    }
}
