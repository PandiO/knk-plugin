package net.knightsandkings.knk.core.npc;

import java.util.Objects;

/**
 * One limit a resolved value is held to (or warned about), named so a clamp report can say which
 * rule acted: {@code "melee-cooldown-floor"} (10 ticks, invulnerability frames), {@code "speed-hard-cap"}
 * (0.5), {@code "speed-comfort-limit"} (0.4, advisory), ...
 *
 * @param name  stable, human-readable rule name for logs and telemetry
 * @param limit the limit value; infinite means "no limit"
 */
public record Bound(String name, double limit) {

    public Bound {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("bound name must not be blank");
        if (Double.isNaN(limit)) throw new IllegalArgumentException("bound limit must not be NaN: " + name);
    }

    public Bound withLimit(double newLimit) {
        return new Bound(name, newLimit);
    }

    public boolean isUnbounded() {
        return Double.isInfinite(limit);
    }
}
