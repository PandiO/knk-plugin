package net.knightsandkings.knk.paper.utils;

import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Per-tick work budgets that back off when the server lags (road navigation plan §2 R11: the one
 * copy of the {@code Bukkit.getTPS()[0] < 15} check that {@code GateBlockScanTaskHandler}'s three
 * scan runnables used to carry each).
 *
 * <p>Pure apart from the {@link #server()} factory: the TPS source is a {@link DoubleSupplier}, so
 * tests inject one. A failing TPS read counts as "not lagging" (the historical behaviour).
 */
public final class TickBudget {
    /** Below this recent TPS the server counts as lagging. */
    public static final double LAG_TPS_THRESHOLD = 15.0;

    private final DoubleSupplier recentTps;

    public TickBudget(DoubleSupplier recentTps) {
        this.recentTps = Objects.requireNonNull(recentTps, "recentTps");
    }

    /** The live server's one-minute TPS. */
    public static TickBudget server() {
        return new TickBudget(() -> org.bukkit.Bukkit.getTPS()[0]);
    }

    /** {@code true} when the recent TPS is below {@link #LAG_TPS_THRESHOLD}; {@code false} when it can't be read. */
    public boolean isLagging() {
        try {
            return recentTps.getAsDouble() < LAG_TPS_THRESHOLD;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code lagging} while the server lags, else {@code normal}. */
    public int perTick(int normal, int lagging) {
        return isLagging() ? lagging : normal;
    }

    /** Convenience for the live server: {@code server().isLagging()}. */
    public static boolean isServerLagging() {
        return server().isLagging();
    }
}
