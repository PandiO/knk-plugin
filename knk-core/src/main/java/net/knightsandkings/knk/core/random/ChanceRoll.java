package net.knightsandkings.knk.core.random;

import java.util.Objects;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * Decides whether something with a chance happens for one subject (a player), such as a custom enchantment going
 * off on a hit. {@link #independent} rolls every time on its own; {@link MarbleBagChanceRoll} remembers earlier
 * outcomes per subject so streaks stay short (PandiO/knk-workspace#476).
 */
@FunctionalInterface
public interface ChanceRoll {
    /**
     * @param subject whose luck this is, usually a player's id; may be null when unknown
     * @param key     what is rolled for, such as an enchantment id; one subject has a separate memory per key
     * @param chance  0-1
     * @return whether it happens this time
     */
    boolean roll(UUID subject, String key, double chance);

    /** A new roll every time, with no memory: happens when {@code roll} (0 inclusive to 1 exclusive) is below the chance. */
    static ChanceRoll independent(DoubleSupplier roll) {
        Objects.requireNonNull(roll, "roll must not be null");
        return (subject, key, chance) -> roll.getAsDouble() < chance;
    }
}
