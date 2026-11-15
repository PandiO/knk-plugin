package net.knightsandkings.knk.core.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.metrics.PrometheusWriter;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;

class NpcNavigationMetricsTest {

    @Test
    void recordsNothingAndWritesNothingWhileDisabled() {
        NpcNavigationMetrics metrics = new NpcNavigationMetrics();
        metrics.count(Event.ARRIVED);
        metrics.observePlan("road", 0.01);
        assertEquals(0, metrics.total(Event.ARRIVED));
        PrometheusWriter out = new PrometheusWriter();
        metrics.collect(out);
        assertEquals("", out.render());
    }

    @Test
    void writesStatesEventsAndHistogramsWhenEnabled() {
        NpcNavigationMetrics metrics = new NpcNavigationMetrics();
        metrics.setEnabled(true);
        metrics.count(Event.STUCK_NO_PROGRESS);
        metrics.count(Event.STUCK_NO_PROGRESS);
        metrics.observePlan("road", 0.02);
        metrics.observeMainThread(0.0003);
        metrics.publishStates(Map.of("world_knk", Map.of(NpcNavState.MOVING, 7, NpcNavState.WAITING, 2)));
        metrics.publishChunksHeld(31);

        PrometheusWriter out = new PrometheusWriter();
        metrics.collect(out);
        String text = out.render();

        assertTrue(text.contains("knk_npc_nav_npcs{world=\"world_knk\",state=\"moving\"} 7"), text);
        assertTrue(text.contains("knk_npc_nav_npcs{world=\"world_knk\",state=\"paused\"} 0"), text);
        assertTrue(text.contains("knk_npc_nav_events_total{event=\"stuck_no_progress\"} 2"), text);
        assertTrue(text.contains("knk_npc_nav_events_total{event=\"trespass\"} 0"), text);
        assertTrue(text.contains("knk_npc_nav_chunks_held 31"), text);
        assertTrue(text.contains("knk_npc_nav_plan_seconds_count{kind=\"road\"} 1"), text);
        assertTrue(text.contains("knk_npc_nav_main_thread_seconds_count 1"), text);
        assertFalse(text.contains("knk_npc_nav_queue_wait_seconds_count"), "no samples, no series: " + text);
    }

    @Test
    void theRouteFollowingEventsHaveTheirOwnSeries() {
        NpcNavigationMetrics metrics = new NpcNavigationMetrics();
        metrics.setEnabled(true);
        metrics.count(Event.STRETCH, 3);
        metrics.count(Event.UNLOADED_WAIT);
        metrics.count(Event.FAILED);

        PrometheusWriter out = new PrometheusWriter();
        metrics.collect(out);
        String text = out.render();

        assertTrue(text.contains("knk_npc_nav_events_total{event=\"stretch\"} 3"), text);
        assertTrue(text.contains("knk_npc_nav_events_total{event=\"unloaded_wait\"} 1"), text);
        assertTrue(text.contains("knk_npc_nav_events_total{event=\"failed\"} 1"), text);
        assertTrue(text.contains("knk_npc_nav_events_total{event=\"examiner_leak\"} 0"), "must stay 0: " + text);
    }

    @Test
    void theGroupEventsHaveTheirOwnSeries() {
        NpcNavigationMetrics metrics = new NpcNavigationMetrics();
        metrics.setEnabled(true);
        metrics.count(Event.GROUP_FOLD);
        metrics.count(Event.GROUP_REFORM);
        metrics.count(Event.GROUP_REJOIN, 2);
        metrics.count(Event.GROUP_REGROUP);

        PrometheusWriter out = new PrometheusWriter();
        metrics.collect(out);
        String text = out.render();

        assertTrue(text.contains("knk_npc_nav_events_total{event=\"group_fold\"} 1"), text);
        assertTrue(text.contains("knk_npc_nav_events_total{event=\"group_reform\"} 1"), text);
        assertTrue(text.contains("knk_npc_nav_events_total{event=\"group_rejoin\"} 2"), text);
        assertTrue(text.contains("knk_npc_nav_events_total{event=\"group_regroup\"} 1"), text);
        assertTrue(text.contains("knk_npc_nav_events_total{event=\"group_lost\"} 0"), text);
    }

    /** Implementation plan 4b.2 items 12 and 14: spells split by where they were, how they ended, and the line. */
    @org.junit.jupiter.api.Test
    void stuckSpellsAreCountedOncePerSpellAndDescribedForTheStatsLine() {
        NpcNavigationMetrics m = new NpcNavigationMetrics();
        m.setEnabled(true);
        NavPoint landing = new NavPoint("world", 1433.5, 44, -516.5);

        m.spellStarted(true, true);
        m.spellStarted(false, true);
        m.spellStarted(false, false);
        m.spellEnded(true, Event.FREED_BACK_OFF, 1, 30, landing);
        m.spellEnded(true, Event.FREED_SIDE_STEP, 2, 50, landing);
        m.spellEnded(false, Event.NOT_FREED, 4, 20, landing);

        org.junit.jupiter.api.Assertions.assertEquals(1, m.total(Event.STUCK_ENGINE));
        org.junit.jupiter.api.Assertions.assertEquals(2, m.total(Event.STUCK_NO_PROGRESS));
        org.junit.jupiter.api.Assertions.assertEquals(2, m.total(Event.RECOVERED), "the sum of the freed_* events");
        org.junit.jupiter.api.Assertions.assertEquals(1, m.total(Event.NOT_FREED));
        org.junit.jupiter.api.Assertions.assertEquals(0, m.spells().open());
        org.junit.jupiter.api.Assertions.assertEquals("stuck near a wall: 2, freed by: back-off 1 / side-step 1 / local leg 0"
            + " / replan 0, not freed 0 | stuck elsewhere: 1 (back-off 0 / side-step 0 / local leg 0 / replan 0, not freed"
            + " 1) | steps per stuck: mean 2.3, max 4 | engine found no path 7 | longest still 2.5 s at 1433 44 -517 |"
            + " falls 0", m.spells().describe(7, 0));

        m.spells().reset();
        org.junit.jupiter.api.Assertions.assertEquals(0, m.spells().started(true));
    }
}
