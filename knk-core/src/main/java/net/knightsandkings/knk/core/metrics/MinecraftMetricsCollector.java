package net.knightsandkings.knk.core.metrics;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * Game-server figures for scaling curves (KNG-232): TPS, tick duration, players, and loaded chunks / entities per
 * world. The server is read by {@link #sample(Source)} on the main thread every few seconds; scrapes (any thread)
 * only read the last snapshot, so a scrape never touches Bukkit. {@code knk_mc_sample_age_seconds} grows when the
 * main thread stops sampling, which is how a freeze shows up even though nothing can answer from inside it.
 */
public final class MinecraftMetricsCollector implements MetricsCollector {

    /** Read on the main thread. */
    public interface Source {
        /** Recent TPS over 1, 5 and 15 minutes. */
        double[] tps();

        /** Durations of the last 100 ticks in nanoseconds. */
        long[] tickTimes();

        int playersOnline();

        List<WorldCounts> worlds();
    }

    public record WorldCounts(String world, int chunks, int entities, int tileEntities, int players) {
    }

    private record Snapshot(double[] tps, TickStats ticks, int players, List<WorldCounts> worlds,
                            long takenAtMillis, double intervalSeconds) {
    }

    private final LongSupplier clockMillis;
    private volatile Snapshot snapshot;

    public MinecraftMetricsCollector() {
        this(System::currentTimeMillis);
    }

    public MinecraftMetricsCollector(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** Call on the main thread. */
    public void sample(Source source) {
        long now = clockMillis.getAsLong();
        Snapshot previous = snapshot;
        double interval = previous == null ? 0 : (now - previous.takenAtMillis) / 1000.0;
        snapshot = new Snapshot(source.tps(), TickStats.fromNanos(source.tickTimes()), source.playersOnline(),
            List.copyOf(source.worlds()), now, interval);
    }

    @Override
    public void collect(PrometheusWriter out) {
        Snapshot s = snapshot;
        if (s == null) {
            return;
        }
        String[] windows = {"1m", "5m", "15m"};
        for (int i = 0; i < windows.length && s.tps != null && i < s.tps.length; i++) {
            out.gauge("knk_mc_tps", "Server ticks per second over the last 1, 5 and 15 minutes (20 is healthy).",
                s.tps[i], "window", windows[i]);
        }
        if (s.ticks.samples() > 0) {
            String help = "Server tick duration over the last 100 ticks; 0.05 s is the whole budget at 20 TPS.";
            out.gauge("knk_mc_tick_duration_seconds", help, s.ticks.avgSeconds(), "stat", "avg");
            out.gauge("knk_mc_tick_duration_seconds", help, s.ticks.p95Seconds(), "stat", "p95");
            out.gauge("knk_mc_tick_duration_seconds", help, s.ticks.maxSeconds(), "stat", "max");
        }
        out.gauge("knk_mc_players_online", "Players online.", s.players);
        for (WorldCounts w : s.worlds) {
            out.gauge("knk_mc_chunks_loaded", "Loaded chunks per world.", w.chunks(), "world", w.world());
            out.gauge("knk_mc_entities", "Entities per world.", w.entities(), "world", w.world());
            out.gauge("knk_mc_tile_entities", "Tile entities (chests, furnaces...) per world.", w.tileEntities(), "world", w.world());
            out.gauge("knk_mc_world_players", "Players per world.", w.players(), "world", w.world());
        }
        out.gauge("knk_mc_sample_timestamp_seconds", "When the main thread last sampled the server (unix seconds).",
            s.takenAtMillis / 1000.0);
        out.gauge("knk_mc_sample_age_seconds", "Seconds since the last sample: grows while the main thread is stuck.",
            Math.max(0, (clockMillis.getAsLong() - s.takenAtMillis) / 1000.0));
        if (s.intervalSeconds > 0) {
            out.gauge("knk_mc_sample_interval_seconds", "Actual time between the last two samples; above the configured interval means the scheduler is late.",
                s.intervalSeconds);
        }
    }
}
