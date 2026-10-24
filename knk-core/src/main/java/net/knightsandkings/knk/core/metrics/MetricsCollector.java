package net.knightsandkings.knk.core.metrics;

/** Something that adds its current samples to a scrape. Must be safe to call from the HTTP thread. */
@FunctionalInterface
public interface MetricsCollector {
    void collect(PrometheusWriter out);
}
