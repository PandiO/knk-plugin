package net.knightsandkings.knk.core.npc.nav;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Spreads NPC spawns over ticks (BEHAVIOR_MODEL NAV-13: twenty player-shaped NPCs spawned in one tick cost a 48.8 ms
 * tick): at most a fixed number per tick, in the order asked. Main thread.
 */
public final class NpcSpawnQueue {

    private final int perTick;
    private final Deque<Runnable> pending = new ArrayDeque<>();

    public NpcSpawnQueue(int perTick) {
        if (perTick < 1) {
            throw new IllegalArgumentException("perTick must be at least 1");
        }
        this.perTick = perTick;
    }

    public void add(Runnable spawn) {
        pending.addLast(Objects.requireNonNull(spawn, "spawn"));
    }

    public int pending() {
        return pending.size();
    }

    /** Drops what has not spawned yet; returns how many. */
    public int clear() {
        int n = pending.size();
        pending.clear();
        return n;
    }

    /** Runs this tick's share; returns how many ran. A spawn that throws is dropped and the tick's share goes on. */
    public int tick() {
        int ran = 0;
        RuntimeException first = null;
        while (ran < perTick && !pending.isEmpty()) {
            Runnable spawn = pending.pollFirst();
            ran++;
            try {
                spawn.run();
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first != null) {
            throw first;
        }
        return ran;
    }
}
