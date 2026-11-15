package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.npc.nav.group.GroupSettings;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A group of three keeps its spacing (NPC navigation Phase 4 developer test, PandiO/knk-workspace#543): a leader and
 * two followers in a column with {@link #SPACING} blocks between neighbours walk a straight road. While they walk,
 * neighbours stay between {@link #MIN_GAP} and {@link #MAX_GAP} blocks apart (no bunching, nobody left behind); once
 * the group has arrived, every gap is within {@link #END_TOLERANCE} of the spacing.
 */
public final class NpcGroupSpacingScenario implements Scenario<ScenarioScene> {

    static final double SPACING = 2.0;
    /** Half the spacing: closer is bunching. */
    static final double MIN_GAP = SPACING / 2;
    /** Twice the spacing: further is a follower left behind. */
    static final double MAX_GAP = SPACING * 2;
    static final double END_TOLERANCE = 0.75;
    static final int TIMEOUT_TICKS = 20 * 45;

    @Override
    public String name() {
        return "npc-group-keeps-spacing";
    }

    @Override
    public String description() {
        return "A group of three NPCs in a column walks a road with its spacing kept and arrives in formation";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<List<NpcMover>> members = new AtomicReference<>();
        AtomicReference<NavigationResult> result = new AtomicReference<>();
        double[] gaps = {Double.MAX_VALUE, 0}; // smallest and largest gap seen while walking
        AtomicReference<String> where = new AtomicReference<>("");
        plan.act("lay a straight road from x 2 to x 29 and start NPC navigation on it", () ->
                npcs.set(new ScenarioNpcs(scene, new int[] {2, 16}, new int[] {29, 16}).start()))
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) npcs.get().close();
            })
            .act("spawn a leader and two followers in a column, " + SPACING + " blocks apart, and send the group east", () -> {
                NpcMover leader = npcs.get().spawn(8.5, 16.5);
                NpcMover first = npcs.get().spawn(8.5 - SPACING, 16.5);
                NpcMover second = npcs.get().spawn(8.5 - 2 * SPACING, 16.5);
                members.set(List.of(leader, first, second));
                npcs.get().group(leader, List.of(first, second),
                        GroupSettings.defaults().withFormation(Formation.column(SPACING)))
                    .navigate(npcs.get().feet(27.5, 16.5), result::set);
            })
            .await("the group's order ended (the gaps checked every tick)", TIMEOUT_TICKS, () -> {
                List<NavPoint> at = positions(members.get());
                if (at != null) {
                    for (int i = 0; i + 1 < at.size(); i++) {
                        double gap = gap(at.get(i), at.get(i + 1));
                        if (gap < gaps[0] || gap > gaps[1]) {
                            where.set("member " + i + " at " + npcs.get().describe(at.get(i)) + ", member " + (i + 1)
                                + " at " + npcs.get().describe(at.get(i + 1)));
                        }
                        gaps[0] = Math.min(gaps[0], gap);
                        gaps[1] = Math.max(gaps[1], gap);
                    }
                }
                return result.get() != null;
            })
            .check("the group arrived", () -> result.get() instanceof NavigationResult.Arrived ? null
                : "the order ended as " + result.get() + "; log " + npcs.get().log())
            .check("neighbours kept between " + MIN_GAP + " and " + MAX_GAP + " blocks apart while walking", () -> {
                scene.services().plugin().getLogger().info("[Scenario] " + name() + ": gaps while walking "
                    + round(gaps[0]) + " to " + round(gaps[1]) + " blocks");
                return gaps[0] >= MIN_GAP && gaps[1] <= MAX_GAP ? null
                    : "gaps from " + round(gaps[0]) + " to " + round(gaps[1]) + " blocks (last extreme: " + where.get() + ")";
            })
            .check("at the goal every gap is " + SPACING + " blocks, give or take " + END_TOLERANCE, () -> {
                List<NavPoint> at = positions(members.get());
                if (at == null) return "a member is not spawned";
                StringBuilder seen = new StringBuilder();
                boolean ok = true;
                for (int i = 0; i + 1 < at.size(); i++) {
                    double gap = gap(at.get(i), at.get(i + 1));
                    seen.append(i == 0 ? "" : ", ").append(round(gap));
                    ok &= Math.abs(gap - SPACING) <= END_TOLERANCE;
                }
                scene.services().plugin().getLogger().info("[Scenario] " + name() + ": gaps at the goal " + seen);
                return ok ? null : "gaps at the goal " + seen + " blocks";
            });
    }

    private static List<NavPoint> positions(List<NpcMover> members) {
        List<NavPoint> out = new java.util.ArrayList<>();
        for (NpcMover member : members) {
            NavPoint at = member.position().orElse(null);
            if (at == null) return null;
            out.add(at);
        }
        return out;
    }

    private static double gap(NavPoint a, NavPoint b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
