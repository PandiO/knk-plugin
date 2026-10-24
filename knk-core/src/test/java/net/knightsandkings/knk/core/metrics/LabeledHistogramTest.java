package net.knightsandkings.knk.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LabeledHistogramTest {

    private static String scrape(LabeledHistogram histogram) {
        PrometheusWriter out = new PrometheusWriter();
        histogram.collect(out);
        return out.render();
    }

    @Test
    void bucketsAreCumulativeAndTheSumIsKept() {
        LabeledHistogram h = new LabeledHistogram("knk_t_seconds", "T.", new double[]{0.01, 0.1}, "task");
        h.observe(0.005, "a");
        h.observe(0.05, "a");
        h.observe(2.0, "a");

        String text = scrape(h);

        assertTrue(text.contains("knk_t_seconds_bucket{task=\"a\",le=\"0.01\"} 1"), text);
        assertTrue(text.contains("knk_t_seconds_bucket{task=\"a\",le=\"0.1\"} 2"), text);
        assertTrue(text.contains("knk_t_seconds_bucket{task=\"a\",le=\"+Inf\"} 3"), text);
        assertTrue(text.contains("knk_t_seconds_count{task=\"a\"} 3"), text);
        assertTrue(text.contains("knk_t_seconds_sum{task=\"a\"} 2.055"), text);
    }

    @Test
    void aBoundIsInclusive() {
        LabeledHistogram h = new LabeledHistogram("knk_t_seconds", "T.", new double[]{0.1}, "task");
        h.observe(0.1, "a");

        assertTrue(scrape(h).contains("le=\"0.1\"} 1"));
    }

    @Test
    void separateLabelValuesAreSeparateSeries() {
        LabeledHistogram h = new LabeledHistogram("knk_t_seconds", "T.", "task");
        h.observe(0.001, "a");
        h.observe(0.001, "b");

        String text = scrape(h);

        assertTrue(text.contains("task=\"a\""));
        assertTrue(text.contains("task=\"b\""));
    }

    @Test
    void whileDisabledNothingIsRecorded() {
        LabeledHistogram h = new LabeledHistogram("knk_t_seconds", "T.", "task");
        h.setEnabled(false);
        h.observe(1, "a");
        h.time(() -> { }, "a");

        assertEquals("", scrape(h));
        assertFalse(h.isEnabled());
    }

    @Test
    void timeRecordsEvenWhenTheTaskThrows() {
        LabeledHistogram h = new LabeledHistogram("knk_t_seconds", "T.", "task");

        assertThrows(IllegalStateException.class, () -> h.time(() -> { throw new IllegalStateException("boom"); }, "a"));

        assertTrue(scrape(h).contains("knk_t_seconds_count{task=\"a\"} 1"));
    }

    @Test
    void labelCountMustMatch() {
        LabeledHistogram h = new LabeledHistogram("knk_t_seconds", "T.", "task");

        assertThrows(IllegalArgumentException.class, () -> h.observe(1, "a", "b"));
    }

    @Test
    void seriesAreCappedSoAnIdInALabelCannotGrowMemoryWithoutBound() {
        LabeledHistogram h = new LabeledHistogram("knk_t_seconds", "T.", "task");
        for (int i = 0; i < LabeledHistogram.MAX_SERIES + 50; i++) {
            h.observe(0.001, "id-" + i);
        }

        String text = scrape(h);

        long series = text.lines().filter(l -> l.startsWith("knk_t_seconds_count")).count();
        assertEquals(LabeledHistogram.MAX_SERIES + 1, series);
        assertTrue(text.contains("knk_t_seconds_count{task=\"other\"} 50"), text);
    }
}
