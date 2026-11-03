package net.knightsandkings.knk.core.npc.modifier;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-actor runtime state: one {@link ModifierSet} per actor id (the entity's UUID for vanilla mobs
 * and Citizens NPCs alike). Consumers add and remove by source; the adapter resolves against it.
 */
public final class ActorModifiers {
    private final Map<UUID, ModifierSet> byActor = new ConcurrentHashMap<>();

    /** The actor's set, created on first use. */
    public ModifierSet forActor(UUID actor) {
        return byActor.computeIfAbsent(Objects.requireNonNull(actor, "actor"), a -> new ModifierSet());
    }

    /** The actor's set if it has one (no allocation on a read path). */
    public Optional<ModifierSet> find(UUID actor) {
        return Optional.ofNullable(byActor.get(Objects.requireNonNull(actor, "actor")));
    }

    /** Puts the modifiers on every listed actor (a Survival round applies to every live enemy). */
    public void putAll(Iterable<UUID> actors, List<StatModifier> modifiers) {
        for (UUID actor : actors) forActor(actor).putAll(modifiers);
    }

    /** Removes one source from every actor (a round or effect ended). Returns how many actors changed. */
    public int removeSource(String source) {
        int changed = 0;
        for (ModifierSet set : byActor.values()) {
            if (set.remove(source)) changed++;
        }
        return changed;
    }

    /** Drops the actor's state (despawn, death). */
    public void forget(UUID actor) {
        byActor.remove(Objects.requireNonNull(actor, "actor"));
    }

    public void clear() {
        byActor.clear();
    }

    public int size() {
        return byActor.size();
    }
}
