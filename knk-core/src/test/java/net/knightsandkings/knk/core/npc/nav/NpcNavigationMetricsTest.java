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
}
