package net.knightsandkings.knk.core.metrics;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The collectors behind one {@code /metrics} endpoint. A failing collector is logged and skipped, never fatal. */
public final class MetricsRegistry {
    private static final Logger LOGGER = Logger.getLogger(MetricsRegistry.class.getName());

    private final List<MetricsCollector> collectors = new CopyOnWriteArrayList<>();

    public void register(MetricsCollector collector) {
        collectors.add(collector);
    }

    public String scrape() {
        PrometheusWriter out = new PrometheusWriter();
        for (MetricsCollector collector : collectors) {
            try {
                collector.collect(out);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "A metrics collector failed; skipped for this scrape", e);
            }
        }
        return out.render();
    }
}
