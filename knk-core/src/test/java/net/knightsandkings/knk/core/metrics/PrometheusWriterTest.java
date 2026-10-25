package net.knightsandkings.knk.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The text exposition format a Prometheus scraper parses. */
class PrometheusWriterTest {

    @Test
    void groupsSamplesPerFamilyWithOneHelpAndType() {
        String text = new PrometheusWriter()
            .gauge("knk_a", "A.", 1, "w", "x")
            .gauge("knk_b", "B.", 2)
            .gauge("knk_a", "A.", 3, "w", "y")
            .render();

        assertEquals("""
            # HELP knk_a A.
            # TYPE knk_a gauge
            knk_a{w="x"} 1
            knk_a{w="y"} 3
            # HELP knk_b B.
            # TYPE knk_b gauge
            knk_b 2
            """, text);
    }

    @Test
    void escapesLabelValuesAndHelp() {
        String text = new PrometheusWriter().gauge("knk_a", "line\nbreak", 1, "world", "a\"b\\c\nd").render();

        assertTrue(text.contains("# HELP knk_a line\\nbreak"));
        assertTrue(text.contains("knk_a{world=\"a\\\"b\\\\c\\nd\"} 1"));
    }

    @Test
    void writesCountersAndFractions() {
        String text = new PrometheusWriter().counter("knk_c_total", "C.", 0.25).render();

        assertTrue(text.contains("# TYPE knk_c_total counter"));
        assertTrue(text.contains("knk_c_total 0.25"));
    }

    @Test
    void writesNonFiniteValuesTheWayPrometheusReadsThem() {
        String text = new PrometheusWriter().gauge("knk_n", "N.", Double.NaN).gauge("knk_p", "P.", Double.POSITIVE_INFINITY).render();

        assertTrue(text.contains("knk_n NaN"));
        assertTrue(text.contains("knk_p +Inf"));
    }

    @Test
    void writesAHistogramAsCumulativeBucketsSumAndCount() {
        String text = new PrometheusWriter()
            .histogram("knk_h_seconds", "H.", new double[]{0.1, 1}, new long[]{2, 5, 6}, 3.5, "route", "/Users")
            .render();

        assertEquals("""
            # HELP knk_h_seconds H.
            # TYPE knk_h_seconds histogram
            knk_h_seconds_bucket{route="/Users",le="0.1"} 2
            knk_h_seconds_bucket{route="/Users",le="1"} 5
            knk_h_seconds_bucket{route="/Users",le="+Inf"} 6
            knk_h_seconds_sum{route="/Users"} 3.5
            knk_h_seconds_count{route="/Users"} 6
            """, text);
    }

    @Test
    void labelsMustComeInPairs() {
        assertThrows(IllegalArgumentException.class, () -> new PrometheusWriter().gauge("knk_a", "A.", 1, "odd"));
    }
}
