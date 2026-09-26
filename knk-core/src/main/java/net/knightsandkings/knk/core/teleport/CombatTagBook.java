package net.knightsandkings.knk.core.teleport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last PvP hit per player (docs/specs/teleport/DESIGN.md §3.4.3): a player who hit or was hit by
 * another player within the tag window can't start a player teleport - v1's 10 s
 * {@code Main.combat} tag, which v1 set but never checked. Staff teleports ignore it.
 */
public final class CombatTagBook {

    private final Map<UUID, Long> lastCombatMillis = new ConcurrentHashMap<>();
    private volatile int tagSeconds;

    public CombatTagBook(int tagSeconds) {
        this.tagSeconds = Math.max(0, tagSeconds);
    }

    public void setTagSeconds(int tagSeconds) {
        this.tagSeconds = Math.max(0, tagSeconds);
    }

    public int tagSeconds() {
        return tagSeconds;
    }

    public void tag(UUID player, long nowMillis) {
        lastCombatMillis.put(player, nowMillis);
    }

    public boolean isTagged(UUID player, long nowMillis) {
        return remainingSeconds(player, nowMillis) > 0;
    }

    /** Whole seconds until the tag runs out, rounded up; 0 when untagged. */
    public int remainingSeconds(UUID player, long nowMillis) {
        Long last = lastCombatMillis.get(player);
        if (last == null) {
            return 0;
        }
        long left = last + tagSeconds * 1000L - nowMillis;
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    /** Whole seconds since the last PvP hit (rounded down), or -1 when there was none. */
    public int secondsSinceCombat(UUID player, long nowMillis) {
        Long last = lastCombatMillis.get(player);
        return last == null ? -1 : (int) Math.max(0, (nowMillis - last) / 1000);
    }

    public void clear(UUID player) {
        lastCombatMillis.remove(player);
    }

    /** Drop tags that ran out (called from the engine's periodic tick). */
    public void purgeExpired(long nowMillis) {
        long window = tagSeconds * 1000L;
        lastCombatMillis.values().removeIf(last -> last + window <= nowMillis);
    }
}
