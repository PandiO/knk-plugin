package net.knightsandkings.knk.core.npc;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A stat the NPC pipeline resolves. The five combat stats and movement speed are the fixed channel
 * (NPC platform §5b); {@link #named(String)} opens a generic channel for a consumer's own stat so the
 * same modifier pipeline and clamping serve it without widening this API.
 *
 * @param id   stable identifier, also the key used by configuration overrides
 *             ({@code bounds.<id>.floor}); lower-case kebab-case
 * @param unit how values are interpreted: tick counts are rounded to whole ticks before clamping
 */
public record NpcStat(String id, Unit unit) implements Comparable<NpcStat> {

    /** How a stat's value is read. */
    public enum Unit {
        /** Whole server ticks; resolved values are rounded to the nearest tick before clamping. */
        TICKS,
        /** Damage in half-hearts, continuous. */
        DAMAGE,
        /** A {@code MOVEMENT_SPEED}-style attribute value, continuous. */
        SPEED,
        /** Anything else, continuous. */
        CUSTOM
    }

    public static final NpcStat MELEE_COOLDOWN = new NpcStat("melee-cooldown-ticks", Unit.TICKS);
    public static final NpcStat MELEE_DAMAGE = new NpcStat("melee-damage", Unit.DAMAGE);
    public static final NpcStat RANGED_COOLDOWN = new NpcStat("ranged-cooldown-ticks", Unit.TICKS);
    public static final NpcStat RANGED_DRAW = new NpcStat("ranged-draw-ticks", Unit.TICKS);
    public static final NpcStat PROJECTILE_DAMAGE = new NpcStat("projectile-damage", Unit.DAMAGE);
    public static final NpcStat MOVEMENT_SPEED = new NpcStat("movement-speed", Unit.SPEED);

    /** The combat stats a {@code CombatProfile} carries, in resolution order. */
    public static final List<NpcStat> COMBAT = List.of(
        MELEE_COOLDOWN, MELEE_DAMAGE, RANGED_COOLDOWN, RANGED_DRAW, PROJECTILE_DAMAGE);

    public NpcStat {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(unit, "unit");
        id = id.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("stat id must not be blank");
        }
    }

    /** A consumer-defined stat on the generic channel (continuous values). */
    public static NpcStat named(String id) {
        return new NpcStat(id, Unit.CUSTOM);
    }

    /** The fixed stat with this id, or {@code null} when the id is not one of the six built-ins. */
    public static NpcStat builtIn(String id) {
        if (id == null) return null;
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (NpcStat stat : COMBAT) {
            if (stat.id.equals(key)) return stat;
        }
        return MOVEMENT_SPEED.id.equals(key) ? MOVEMENT_SPEED : null;
    }

    public boolean isTicks() {
        return unit == Unit.TICKS;
    }

    @Override
    public int compareTo(NpcStat other) {
        return id.compareTo(other.id);
    }
}
