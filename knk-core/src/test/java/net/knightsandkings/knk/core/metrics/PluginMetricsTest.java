package net.knightsandkings.knk.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PluginMetricsTest {

    @AfterEach
    void off() {
        PluginMetrics.setEnabled(false);
    }

    @Test
    void outcomeIsTheStatusClassOrErrorWhenNoResponseArrived() {
        assertEquals("2xx", PluginMetrics.outcome(204));
        assertEquals("4xx", PluginMetrics.outcome(404));
        assertEquals("5xx", PluginMetrics.outcome(503));
        assertEquals("error", PluginMetrics.outcome(0));
    }

    @Test
    void timedWrapsATaskAndRecordsItOnlyWhileEnabled() {
        int[] runs = {0};
        Runnable wrapped = PluginMetrics.timed("test.timed_task", () -> runs[0]++);

        wrapped.run();
        PluginMetrics.setEnabled(true);
        wrapped.run();

        PrometheusWriter out = new PrometheusWriter();
        PluginMetrics.TASKS.collect(out);
        assertEquals(2, runs[0]);
        assertTrue(out.render().contains("knk_task_duration_seconds_count{task=\"test.timed_task\"} 1"), out.render());
    }
}
