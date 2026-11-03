package net.knightsandkings.knk.core.npc.modifier;

import net.knightsandkings.knk.core.npc.NpcStat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The modifiers currently on one actor, keyed by source then stat. Mutable; a Paper adapter consults
 * it on every attack, shot or speed refresh through the resolver. {@link #revision()} rises on every
 * effective change so an adapter can re-apply an attribute or log a clamp only when something moved.
 *
 * <p>Iteration order is deterministic: sources sorted by name, stats in insertion order per source.
 * The arithmetic is commutative anyway; the ordering keeps reports and tests stable.
 *
 * <p>Methods are synchronized: the main thread resolves while a consumer may update from an async
 * round scheduler. Contention is negligible (a handful of entries per actor).
 */
public final class ModifierSet {
    private final Map<String, Map<NpcStat, StatModifier>> bySource = new TreeMap<>();
    private long revision;

    /** Adds or replaces the modifier for its (source, stat); returns {@code true} when anything changed. */
    public synchronized boolean put(StatModifier modifier) {
        Objects.requireNonNull(modifier, "modifier");
        Map<NpcStat, StatModifier> stats = bySource.computeIfAbsent(modifier.source(), s -> new LinkedHashMap<>());
        StatModifier previous = stats.put(modifier.stat(), modifier);
        if (modifier.equals(previous)) return false;
        revision++;
        return true;
    }

    /** Puts several modifiers (one source typically sets a few stats at once). */
    public synchronized boolean putAll(Collection<StatModifier> modifiers) {
        boolean changed = false;
        for (StatModifier modifier : modifiers) changed |= put(modifier);
        return changed;
    }

    /** Removes every modifier of a source; returns {@code true} when the source had any. */
    public synchronized boolean remove(String source) {
        Map<NpcStat, StatModifier> removed = bySource.remove(key(source));
        if (removed == null || removed.isEmpty()) return false;
        revision++;
        return true;
    }

    /** Removes one (source, stat) modifier; returns {@code true} when it existed. */
    public synchronized boolean remove(String source, NpcStat stat) {
        String k = key(source);
        Map<NpcStat, StatModifier> stats = bySource.get(k);
        if (stats == null || stats.remove(stat) == null) return false;
        if (stats.isEmpty()) bySource.remove(k);
        revision++;
        return true;
    }

    public synchronized void clear() {
        if (bySource.isEmpty()) return;
        bySource.clear();
        revision++;
    }

    /** The modifiers that apply to {@code stat}, sources in name order. */
    public synchronized List<StatModifier> forStat(NpcStat stat) {
        List<StatModifier> out = new ArrayList<>();
        for (Map<NpcStat, StatModifier> stats : bySource.values()) {
            StatModifier m = stats.get(stat);
            if (m != null) out.add(m);
        }
        return List.copyOf(out);
    }

    /** Every modifier, sources in name order. */
    public synchronized List<StatModifier> all() {
        List<StatModifier> out = new ArrayList<>();
        for (Map<NpcStat, StatModifier> stats : bySource.values()) out.addAll(stats.values());
        return List.copyOf(out);
    }

    public synchronized List<String> sources() {
        return List.copyOf(bySource.keySet());
    }

    public synchronized boolean isEmpty() {
        return bySource.isEmpty();
    }

    /** Rises by one on every effective change; starts at 0. */
    public synchronized long revision() {
        return revision;
    }

    private static String key(String source) {
        return Objects.requireNonNull(source, "source").trim().toLowerCase(Locale.ROOT);
    }
}
