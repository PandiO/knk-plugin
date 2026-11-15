package net.knightsandkings.knk.core.npc.nav.group;

import static net.knightsandkings.knk.core.npc.nav.group.GroupSim.feet;
import static net.knightsandkings.knk.core.npc.nav.group.GroupSim.floor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.gates.target.GateBox;
import net.knightsandkings.knk.core.metrics.PrometheusWriter;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.FoldReason;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.GateCrossing;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.RoadPlan;
import net.knightsandkings.knk.core.npc.nav.group.GroupSim.Results;

/**
 * Fold reasons (implementation plan 4c.3 decision 2.8, link M1, PandiO/knk-workspace#621): every fold and narrowing
 * logs and counts why the wider width did not fit, and nothing else changes (the same widths as before M1).
 */
class NavGroupFoldReasonsTest {

    private final GroupSim sim = new GroupSim();
    private final Results results = new Results();

    private static GroupSettings ranks3() {
        return GroupSettings.defaults().withFormation(new Formation(Formation.Type.RANKS, 1.5, 1.0, 3));
    }

    /** As NavGroupWidthsTest: 3 means z -1..1, 2 means z 0..1, 1 means z 0. */
    private void road(java.util.function.IntUnaryOperator wide) {
        sim.world.noFloor = (x, z) -> {
            int w = wide.applyAsInt(x);
            return w == 3 ? z < -1 || z > 1 : w == 2 ? z < 0 || z > 1 : z != 0;
        };
    }

    private List<String> widthLines() {
        return sim.log.stream().filter(line -> line.startsWith("group ") && (line.contains(" narrows ")
            || line.contains(" folds "))).toList();
    }

    private void walk(NavGroup group, double toX) {
        group.navigate(feet(toX, 0.5), results);
        assertTrue(sim.runUntil(results::ended, 6000));
        assertInstanceOf(NavigationResult.Arrived.class, results.last());
    }

    private long reasons(Event change) {
        long sum = 0;
        for (FoldReason reason : FoldReason.values()) {
            sum += sim.metrics.foldReasonTotal(change, reason);
        }
        return sum;
    }

    @Test
    void aNarrowWalkIsCountedAsNarrowForTheNarrowingAndTheFold() {
        road(x -> x < 40 ? 3 : x < 60 ? 2 : x < 70 ? 1 : 3);
        NavGroup group = sim.group(7, ranks3());

        walk(group, 120.5);

        List<String> lines = widthLines();
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("too narrow for 3 abreast (narrow) at "), lines.get(0));
        assertTrue(lines.get(1).contains("too narrow for 2 abreast (narrow) at "), lines.get(1));
        assertEquals(1, sim.metrics.foldReasonTotal(Event.GROUP_NARROW, FoldReason.NARROW));
        assertEquals(1, sim.metrics.foldReasonTotal(Event.GROUP_FOLD, FoldReason.NARROW));
        assertEquals(sim.metrics.total(Event.GROUP_FOLD), reasons(Event.GROUP_FOLD), "one reason per fold");
        assertEquals(sim.metrics.total(Event.GROUP_NARROW), reasons(Event.GROUP_NARROW), "one reason per narrowing");
    }

    @Test
    void aGateDoorOnTheRouteIsCountedAsDoor() {
        road(x -> x >= 59 && x <= 62 ? 1 : 3); // the gate's walls leave a one-block opening at x 59..62
        sim.road = (subject, from, to) -> RoadPlan.found(List.of(from, to),
            List.of(new GateCrossing(7, floor(60.5, 0.5))));
        sim.rules.doorBoxes.put(7, new GateBox(60, 64, 0, 61, 69, 1));
        NavGroup group = sim.group(7, ranks3());

        walk(group, 120.5);

        assertEquals(1, sim.metrics.total(Event.GROUP_FOLD), widthLines().toString());
        assertEquals(1, sim.metrics.foldReasonTotal(Event.GROUP_FOLD, FoldReason.DOOR), widthLines().toString());
        assertTrue(widthLines().get(0).contains("(door) at "), widthLines().get(0));
        assertEquals(0, sim.metrics.total(Event.GROUP_NARROW), "the column at once at a gate, as before M1");
    }

    @Test
    void theSameOpeningWithoutAGateIsNarrow() {
        road(x -> x >= 59 && x <= 62 ? 1 : 3);
        NavGroup group = sim.group(7, ranks3());

        walk(group, 120.5);

        assertEquals(1, sim.metrics.total(Event.GROUP_FOLD));
        assertEquals(1, sim.metrics.foldReasonTotal(Event.GROUP_FOLD, FoldReason.NARROW));
    }

    @Test
    void theReasonsAreScrapedWithTheirChangeAndReason() {
        NpcNavigationMetrics metrics = new NpcNavigationMetrics();
        metrics.setEnabled(true);
        metrics.countFoldReason(Event.GROUP_FOLD, FoldReason.STAIR);
        metrics.countFoldReason(Event.GROUP_NARROW, FoldReason.EDGE);
        metrics.countFoldReason(Event.GROUP_NARROW, FoldReason.EDGE);
        PrometheusWriter out = new PrometheusWriter();

        metrics.collect(out);

        String text = out.render();
        assertTrue(text.contains("knk_npc_nav_group_width_reasons_total{change=\"fold\",reason=\"stair\"} 1"), text);
        assertTrue(text.contains("knk_npc_nav_group_width_reasons_total{change=\"narrow\",reason=\"edge\"} 2"), text);
        assertEquals(2, metrics.foldReasonTotal(Event.GROUP_NARROW, FoldReason.EDGE));
        assertEquals(0, metrics.foldReasonTotal(Event.GROUP_WIDEN, FoldReason.EDGE), "a widening has no reason");
    }

    @Test
    void nothingIsCountedWhileTheMetricsAreOff() {
        NpcNavigationMetrics metrics = new NpcNavigationMetrics();
        metrics.countFoldReason(Event.GROUP_FOLD, FoldReason.RULES);
        assertEquals(0, metrics.foldReasonTotal(Event.GROUP_FOLD, FoldReason.RULES));
    }
}
