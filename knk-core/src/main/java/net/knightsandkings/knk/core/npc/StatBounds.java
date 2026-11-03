package net.knightsandkings.knk.core.npc;

import java.util.Objects;
import java.util.Optional;

/**
 * The bounds the resolver applies to one stat: a floor and a ceiling that clamp (reported), and an
 * optional comfort limit that only warns (reported with {@code applied = false}) so an adapter can
 * log a value that is legal but above what was measured safe (speed 0.4 vs hard cap 0.5, §10 of the
 * implementation checks).
 *
 * @param floor   lowest value allowed ({@code -Infinity} for none)
 * @param ceiling highest value allowed ({@code +Infinity} for none)
 * @param comfort advisory upper limit, never clamps; empty for none
 */
public record StatBounds(Bound floor, Bound ceiling, Optional<Bound> comfort) {

    public static final StatBounds UNBOUNDED = new StatBounds(
        new Bound("no-floor", Double.NEGATIVE_INFINITY), new Bound("no-ceiling", Double.POSITIVE_INFINITY),
        Optional.empty());

    public StatBounds {
        Objects.requireNonNull(floor, "floor");
        Objects.requireNonNull(ceiling, "ceiling");
        Objects.requireNonNull(comfort, "comfort");
        if (floor.limit() > ceiling.limit()) {
            throw new IllegalArgumentException("floor " + floor + " above ceiling " + ceiling);
        }
    }

    public StatBounds(Bound floor, Bound ceiling) {
        this(floor, ceiling, Optional.empty());
    }

    /**
     * A floor only. {@code name} is the rule name shown in clamp reports; the open ceiling is named
     * after it ({@code melee-cooldown-floor} → {@code melee-cooldown-ceiling}) so a ceiling that
     * configuration later closes reports under a sensible name.
     */
    public static StatBounds floorOnly(String name, double limit) {
        String ceilingName = name.endsWith("-floor")
            ? name.substring(0, name.length() - "-floor".length()) + "-ceiling"
            : name + "-ceiling";
        return new StatBounds(new Bound(name, limit), new Bound(ceilingName, Double.POSITIVE_INFINITY));
    }

    public static StatBounds of(String floorName, double floor, String ceilingName, double ceiling) {
        return new StatBounds(new Bound(floorName, floor), new Bound(ceilingName, ceiling));
    }

    public StatBounds withFloor(double limit) {
        return new StatBounds(floor.withLimit(limit), ceiling, comfort);
    }

    public StatBounds withCeiling(double limit) {
        return new StatBounds(floor, ceiling.withLimit(limit), comfort);
    }

    public StatBounds withComfort(Bound bound) {
        return new StatBounds(floor, ceiling, Optional.ofNullable(bound));
    }
}
