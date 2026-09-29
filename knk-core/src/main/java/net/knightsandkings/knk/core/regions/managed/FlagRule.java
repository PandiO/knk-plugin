package net.knightsandkings.knk.core.regions.managed;

import java.util.Objects;

/**
 * One flag a category wants on its regions. {@code value} is a {@link String}, an {@link Integer} or a {@link FlagState};
 * the store adapter turns it into the matching WorldGuard flag type.
 */
public record FlagRule(String flag, Object value, FlagMode mode) {

    public FlagRule {
        Objects.requireNonNull(flag, "flag");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(mode, "mode");
        if (!(value instanceof String || value instanceof Integer || value instanceof FlagState)) {
            throw new IllegalArgumentException("Unsupported flag value type " + value.getClass().getSimpleName() + " for " + flag);
        }
    }

    public static FlagRule seed(String flag, Object value) {
        return new FlagRule(flag, value, FlagMode.SEED_IF_ABSENT);
    }

    public static FlagRule enforce(String flag, Object value) {
        return new FlagRule(flag, value, FlagMode.ENFORCE);
    }
}
