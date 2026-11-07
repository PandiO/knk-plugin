package net.knightsandkings.knk.paper.siege.survival.economy;

import net.knightsandkings.knk.core.survival.economy.KillBlow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The last player hit per enemy (DESIGN §8: the killing blow decides, ×1.1 for a headshot or a critical hit). The damage
 * listener records every player hit on a match actor; the death handler asks for the hit that killed it. Entries are
 * dropped after {@link #TTL_MILLIS} (a hit long before the death is not the killing blow) and when the enemy dies.
 */
public final class KillBlows {

    public static final long TTL_MILLIS = 5_000L;

    private record Entry(KillBlow blow, long atMillis) { }

    private final Map<UUID, Entry> lastByMob = new LinkedHashMap<>();

    public void record(UUID mobId, KillBlow blow, long nowMillis) {
        lastByMob.put(mobId, new Entry(blow, nowMillis));
    }

    /**
     * The killing blow of a mob killed by {@code killer}: the recorded hit when it is the killer's and fresh, else a plain
     * blow by the killer (Bukkit's {@code getKiller()} is authoritative; the details only add the bonus).
     */
    public KillBlow consume(UUID mobId, UUID killer, long nowMillis) {
        Entry entry = lastByMob.remove(mobId);
        if (entry != null && entry.blow().attacker().equals(killer) && nowMillis - entry.atMillis() <= TTL_MILLIS) {
            return entry.blow();
        }
        return new KillBlow(killer, false, false);
    }

    public Optional<KillBlow> peek(UUID mobId) {
        return Optional.ofNullable(lastByMob.get(mobId)).map(Entry::blow);
    }

    public void forget(UUID mobId) {
        lastByMob.remove(mobId);
    }

    /** Drops stale entries (called from the match tick). */
    public void prune(long nowMillis) {
        lastByMob.values().removeIf(e -> nowMillis - e.atMillis() > TTL_MILLIS);
    }

    public int size() {
        return lastByMob.size();
    }
}
