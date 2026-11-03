package net.knightsandkings.knk.core.npc.modifier;

import net.knightsandkings.knk.core.npc.NpcStat;

import java.util.Locale;
import java.util.Objects;

/**
 * One modifier a consumer registered on an actor, keyed by its source so the same consumer can
 * replace or withdraw it later ({@code survival:round}, {@code survival:per-player},
 * {@code effect:slowness}, ...). One modifier per (source, stat): putting another replaces it.
 *
 * @param source consumer-chosen key, lower-cased; the convention is {@code <consumer>:<reason>}
 * @param stat   the stat it changes
 * @param op     addition or multiplication
 * @param value  the amount; must be finite (a NaN or infinite modifier is a programming error and is
 *               rejected here, not clamped later)
 */
public record StatModifier(String source, NpcStat stat, ModifierOp op, double value) {

    public StatModifier {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(stat, "stat");
        Objects.requireNonNull(op, "op");
        source = source.trim().toLowerCase(Locale.ROOT);
        if (source.isEmpty()) throw new IllegalArgumentException("modifier source must not be blank");
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("modifier " + source + "/" + stat.id() + " must be finite: " + value);
        }
    }

    public static StatModifier add(String source, NpcStat stat, double amount) {
        return new StatModifier(source, stat, ModifierOp.ADD, amount);
    }

    public static StatModifier multiply(String source, NpcStat stat, double factor) {
        return new StatModifier(source, stat, ModifierOp.MULTIPLY, factor);
    }

    /** {@code true} for a modifier that would leave the value unchanged. */
    public boolean isIdentity() {
        return op == ModifierOp.ADD ? value == 0.0 : value == 1.0;
    }
}
