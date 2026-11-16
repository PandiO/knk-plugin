package net.knightsandkings.knk.core.npc.nav.push;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Repeated pushes by the same player (implementation plan 4c.4 item 7, research report § 6.5 item 3): the pushes each
 * player gave each NPC within a window, and the players whose collision with an NPC is off for a while because of them.
 * The ledger only counts and remembers; the navigation service asks the {@code NpcCollisionPolicy} to exempt and to
 * restore. Main thread.
 */
public final class PushLedger {

    /** One player's collision with one NPC is off until {@code untilTick}. */
    public record Exemption(int moverId, String playerId, long untilTick) {
        public Exemption {
            Objects.requireNonNull(playerId, "playerId");
        }
    }

    private record Pair(int moverId, String playerId) {
    }

    private final Map<Pair, Deque<Long>> pushes = new HashMap<>();
    private final Map<Pair, Exemption> exempt = new HashMap<>();
    private long exemptions;

    /**
     * Records one push of an NPC by a player at {@code tick}; true when that makes {@code after} pushes within the last
     * {@code windowTicks} ticks and the player is not exempt from that NPC yet (the caller exempts it then).
     */
    public boolean record(int moverId, String playerId, long tick, int after, int windowTicks) {
        Pair key = new Pair(moverId, Objects.requireNonNull(playerId, "playerId"));
        if (exempt.containsKey(key)) {
            return false;
        }
        Deque<Long> times = pushes.computeIfAbsent(key, k -> new ArrayDeque<>());
        times.addLast(tick);
        while (!times.isEmpty() && times.peekFirst() <= tick - windowTicks) {
            times.pollFirst();
        }
        return times.size() >= after;
    }

    /** The pushes of an NPC by a player within the last {@code windowTicks} ticks, for the log. */
    public int count(int moverId, String playerId, long tick, int windowTicks) {
        Deque<Long> times = pushes.get(new Pair(moverId, playerId));
        if (times == null) {
            return 0;
        }
        int n = 0;
        for (long t : times) {
            if (t > tick - windowTicks) {
                n++;
            }
        }
        return n;
    }

    /** Remembers that a player's collision with an NPC is off until {@code untilTick}; its push count starts again. */
    public void exempted(int moverId, String playerId, long untilTick) {
        Pair key = new Pair(moverId, playerId);
        exempt.put(key, new Exemption(moverId, playerId, untilTick));
        pushes.remove(key);
        exemptions++;
    }

    public boolean isExempt(int moverId, String playerId) {
        return exempt.containsKey(new Pair(moverId, playerId));
    }

    /** The exemptions whose time is up at {@code tick}, removed from the ledger (the caller restores them). */
    public List<Exemption> expire(long tick) {
        List<Exemption> out = new ArrayList<>();
        for (Iterator<Exemption> it = exempt.values().iterator(); it.hasNext(); ) {
            Exemption e = it.next();
            if (e.untilTick() <= tick) {
                out.add(e);
                it.remove();
            }
        }
        return out;
    }

    /** Forgets an NPC (unregistered): its exemptions, removed from the ledger (the caller restores them if it can). */
    public List<Exemption> forget(int moverId) {
        pushes.keySet().removeIf(p -> p.moverId() == moverId);
        List<Exemption> out = new ArrayList<>();
        for (Iterator<Exemption> it = exempt.values().iterator(); it.hasNext(); ) {
            Exemption e = it.next();
            if (e.moverId() == moverId) {
                out.add(e);
                it.remove();
            }
        }
        return out;
    }

    /** Exemptions in force now. */
    public int active() {
        return exempt.size();
    }

    /** Exemptions given so far. */
    public long given() {
        return exemptions;
    }
}
