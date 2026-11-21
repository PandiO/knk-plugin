package net.knightsandkings.knk.core.production.harvest;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A rule's cooldown per player and area (PRODUCTION_KINDS § 6.1, anti-farming), in memory for the server run. */
public final class HarvestCooldowns {

    private record Key(UUID player, int areaId, int ruleId) {
    }

    private final Map<Key, Long> until = new ConcurrentHashMap<>();

    /** Milliseconds left before {@code player} may harvest under {@code rule} in {@code areaId} again (0 = now). */
    public long remaining(UUID player, int areaId, HarvestRule rule, long nowMillis) {
        if (rule.cooldownSeconds() <= 0) {
            return 0;
        }
        Long end = until.get(new Key(player, areaId, rule.id()));
        return end == null ? 0 : Math.max(0, end - nowMillis);
    }

    public void started(UUID player, int areaId, HarvestRule rule, long nowMillis) {
        if (rule.cooldownSeconds() > 0) {
            until.put(new Key(player, areaId, rule.id()), nowMillis + rule.cooldownSeconds() * 1000L);
        }
    }

    /** Drops cooldowns that ended (called now and then). */
    public void prune(long nowMillis) {
        for (Iterator<Long> it = until.values().iterator(); it.hasNext(); ) {
            if (it.next() <= nowMillis) {
                it.remove();
            }
        }
    }

    public int size() {
        return until.size();
    }
}
