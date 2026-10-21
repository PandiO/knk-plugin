package net.knightsandkings.knk.paper.duel;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Executor;

/** The server clock and scheduler as the duel runtime uses them (a fake in tests). Main thread. */
public interface DuelScheduler {

    /** The server tick ({@code Bukkit.getCurrentTick()}): the duel rules' clock (P3 {@code DuelTicks}). */
    long currentTick();

    /** Run on the next tick. */
    void nextTick(Runnable task);

    /** Run after {@code ticks}. */
    void later(long ticks, Runnable task);

    /** Run on the main thread (now when already on it). */
    Executor mainThread();

    static DuelScheduler bukkit(Plugin plugin) {
        Executor main = task -> {
            if (Bukkit.isPrimaryThread()) task.run();
            else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, task);
        };
        return new DuelScheduler() {
            @Override
            public long currentTick() {
                return Bukkit.getCurrentTick();
            }

            @Override
            public void nextTick(Runnable task) {
                if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, task);
            }

            @Override
            public void later(long ticks, Runnable task) {
                if (plugin.isEnabled()) Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1, ticks));
            }

            @Override
            public Executor mainThread() {
                return main;
            }
        };
    }
}
