package net.knightsandkings.knk.core.crossplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Ghost blocks on Bedrock clients (alpha test run 1, PandiO/knk-workspace#161), Bukkit-free bookkeeping.
 * <p>
 * A Bedrock client behind Geyser breaks a block on its own when its local mining progress says so: Geyser's
 * {@code BlockBreakHandler} removes the block client-side at 65 % of its own estimate and only then tells the
 * server. When the server refused the break, the client is set right only if a block update reaches it
 * <i>after</i> that. Paper sends one when it cancels a {@code BlockBreakEvent}, but a break refused at its
 * <b>start</b> (a cancelled left-click or {@code BlockDamageEvent}) gets its single update at the start, while
 * the client goes on mining; and on Spigot/Paper Geyser does not roll a predicted block back when the server
 * acknowledges the action (its sequence rollback is for other platforms). The block then stays gone for that
 * player only - they can see and walk through it.
 * <p>
 * So a refused break is <b>watched</b>: the real block is sent to the player again every {@code intervalTicks}
 * while they keep swinging at it, and once more when the watch ends {@code windowTicks} after the last swing.
 * Positions are opaque keys (the paper side uses world + block coordinates). Main thread only.
 */
public final class BlockResyncWatch {

    /** A block position in a world. */
    public record Pos(String world, int x, int y, int z) {
        public Pos {
            Objects.requireNonNull(world, "world");
        }
    }

    /** One resend: this player gets the real state of this block now. */
    public record Resend(UUID playerId, Pos pos) {
    }

    private static final class Entry {
        long nextAt;
        long until;

        Entry(long nextAt, long until) {
            this.nextAt = nextAt;
            this.until = until;
        }
    }

    private final int windowTicks;
    private final int intervalTicks;
    private final int maxPerPlayer;
    private final Map<UUID, LinkedHashMap<Pos, Entry>> watched = new HashMap<>();

    /**
     * @param windowTicks   how long after the refusal, or the player's last swing, a block is watched
     * @param intervalTicks how often a watched block is sent again
     * @param maxPerPlayer  how many blocks one player has watched at once (the oldest gives way)
     */
    public BlockResyncWatch(int windowTicks, int intervalTicks, int maxPerPlayer) {
        if (windowTicks < 1 || intervalTicks < 1 || maxPerPlayer < 1) {
            throw new IllegalArgumentException("window, interval and max must be at least 1");
        }
        this.windowTicks = windowTicks;
        this.intervalTicks = intervalTicks;
        this.maxPerPlayer = maxPerPlayer;
    }

    /** A break of {@code pos} by this player was refused at {@code tick}: send it again from the next tick on. */
    public void refused(UUID playerId, Pos pos, long tick) {
        LinkedHashMap<Pos, Entry> entries = watched.computeIfAbsent(playerId, id -> new LinkedHashMap<>());
        Entry entry = entries.get(pos);
        if (entry != null) {
            entry.until = Math.max(entry.until, tick + windowTicks);
            entry.nextAt = Math.min(entry.nextAt, tick + 1);
            return;
        }
        if (entries.size() >= maxPerPlayer) {
            Iterator<Pos> oldest = entries.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        entries.put(pos, new Entry(tick + 1, tick + windowTicks));
    }

    /** The player swung (is still mining): their watched blocks stay watched for another window. */
    public void swung(UUID playerId, long tick) {
        LinkedHashMap<Pos, Entry> entries = watched.get(playerId);
        if (entries != null) {
            entries.values().forEach(e -> e.until = Math.max(e.until, tick + windowTicks));
        }
    }

    /** The resends due at {@code tick}; a block whose window ended gets its last one and is forgotten. */
    public List<Resend> due(long tick) {
        List<Resend> out = new ArrayList<>();
        Iterator<Map.Entry<UUID, LinkedHashMap<Pos, Entry>>> players = watched.entrySet().iterator();
        while (players.hasNext()) {
            Map.Entry<UUID, LinkedHashMap<Pos, Entry>> player = players.next();
            Iterator<Map.Entry<Pos, Entry>> it = player.getValue().entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Pos, Entry> e = it.next();
                Entry entry = e.getValue();
                if (tick >= entry.until) {
                    out.add(new Resend(player.getKey(), e.getKey()));
                    it.remove();
                } else if (tick >= entry.nextAt) {
                    out.add(new Resend(player.getKey(), e.getKey()));
                    entry.nextAt = tick + intervalTicks;
                }
            }
            if (player.getValue().isEmpty()) {
                players.remove();
            }
        }
        return out;
    }

    public boolean isWatching(UUID playerId) {
        return watched.containsKey(playerId);
    }

    public void forget(UUID playerId) {
        watched.remove(playerId);
    }
}
