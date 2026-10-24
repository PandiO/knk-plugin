package net.knightsandkings.knk.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class MinecraftMetricsCollectorTest {

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final MinecraftMetricsCollector collector = new MinecraftMetricsCollector(clock::get);

    private static MinecraftMetricsCollector.Source source(int players) {
        return new MinecraftMetricsCollector.Source() {
            public double[] tps() { return new double[]{19.9, 19.5, 19.8}; }
            public long[] tickTimes() { return new long[]{10_000_000L, 30_000_000L}; }
            public int playersOnline() { return players; }
            public List<MinecraftMetricsCollector.WorldCounts> worlds() {
                return List.of(new MinecraftMetricsCollector.WorldCounts("world", 1200, 3400, 56, players));
            }
        };
    }

    private String scrape() {
        PrometheusWriter out = new PrometheusWriter();
        collector.collect(out);
        return out.render();
    }

    @Test
    void beforeTheFirstSampleThereIsNothing() {
        assertEquals("", scrape());
    }

    @Test
    void exposesTpsTickTimePlayersAndWorldCounts() {
        collector.sample(source(12));

        String text = scrape();

        assertTrue(text.contains("knk_mc_tps{window=\"1m\"} 19.9"), text);
        assertTrue(text.contains("knk_mc_tps{window=\"15m\"} 19.8"), text);
        assertTrue(text.contains("knk_mc_tick_duration_seconds{stat=\"avg\"} 0.02"), text);
        assertTrue(text.contains("knk_mc_tick_duration_seconds{stat=\"max\"} 0.03"), text);
        assertTrue(text.contains("knk_mc_players_online 12"), text);
        assertTrue(text.contains("knk_mc_chunks_loaded{world=\"world\"} 1200"), text);
        assertTrue(text.contains("knk_mc_entities{world=\"world\"} 3400"), text);
        assertTrue(text.contains("knk_mc_sample_age_seconds 0"), text);
        assertFalse(text.contains("knk_mc_sample_interval_seconds"), "no interval before a second sample");
    }

    @Test
    void theSampleAgeGrowsWhenTheMainThreadStopsSampling() {
        collector.sample(source(1));
        clock.addAndGet(45_000);

        assertTrue(scrape().contains("knk_mc_sample_age_seconds 45"));
    }

    @Test
    void theIntervalIsTheTimeBetweenTheLastTwoSamples() {
        collector.sample(source(1));
        clock.addAndGet(5_250);
        collector.sample(source(1));

        assertTrue(scrape().contains("knk_mc_sample_interval_seconds 5.25"));
    }
}
