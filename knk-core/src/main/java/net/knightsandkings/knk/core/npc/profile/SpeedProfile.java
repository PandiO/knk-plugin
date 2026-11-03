package net.knightsandkings.knk.core.npc.profile;

import net.knightsandkings.knk.core.npc.Bound;
import net.knightsandkings.knk.core.npc.StatBounds;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Movement speed of one NPC type or named profile (NPC platform §5b, "Speed profile"). Values are
 * {@code MOVEMENT_SPEED} attribute values; ground speed grows roughly with their square (checks §10).
 *
 * <p>Defaults (checks §10): zombie 0.23, skeleton and creeper 0.25, spider 0.3; comfort limit 0.4
 * effective (≈ 5 blocks/s, a sprinting player) and hard cap 0.5. Both limits are estimates to measure
 * live, hence configurable per profile.
 *
 * @param id              profile id, lower-case
 * @param baseSpeed       the base attribute value
 * @param comfortLimit    advisory: a resolved speed above it is reported but not clamped
 * @param hardCap         the resolved speed never exceeds it (clamped and reported)
 * @param movementFactors optional multipliers by terrain or movement-profile key ({@code "road"},
 *                        {@code "water"}, {@code "stairs"}, ...), applied to the base before modifiers;
 *                        unknown keys count as 1
 */
public record SpeedProfile(
    String id,
    double baseSpeed,
    double comfortLimit,
    double hardCap,
    Map<String, Double> movementFactors
) {
    public static final double DEFAULT_COMFORT_LIMIT = 0.4;
    public static final double DEFAULT_HARD_CAP = 0.5;
    /** A speed below zero is meaningless; zero freezes the actor and is allowed. */
    public static final double SPEED_FLOOR = 0.0;

    public static final String FLOOR_RULE = "speed-floor";
    public static final String HARD_CAP_RULE = "speed-hard-cap";
    public static final String COMFORT_RULE = "speed-comfort-limit";

    public SpeedProfile {
        Objects.requireNonNull(id, "id");
        id = id.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) throw new IllegalArgumentException("profile id must not be blank");
        if (!Double.isFinite(baseSpeed) || baseSpeed < 0) throw new IllegalArgumentException("baseSpeed must be ≥ 0: " + baseSpeed);
        if (Double.isNaN(comfortLimit) || comfortLimit < 0) throw new IllegalArgumentException("comfortLimit must be ≥ 0: " + comfortLimit);
        if (Double.isNaN(hardCap) || hardCap < 0) throw new IllegalArgumentException("hardCap must be ≥ 0: " + hardCap);
        if (comfortLimit > hardCap) throw new IllegalArgumentException("comfortLimit " + comfortLimit + " above hardCap " + hardCap);
        Map<String, Double> factors = new TreeMap<>();
        if (movementFactors != null) {
            movementFactors.forEach((key, factor) -> {
                if (key == null || factor == null) return;
                if (!Double.isFinite(factor) || factor < 0) throw new IllegalArgumentException("factor " + key + " must be ≥ 0: " + factor);
                factors.put(key.trim().toLowerCase(Locale.ROOT), factor);
            });
        }
        movementFactors = Map.copyOf(factors);
    }

    /** A profile with the default limits and no terrain factors. */
    public static SpeedProfile of(String id, double baseSpeed) {
        return new SpeedProfile(id, baseSpeed, DEFAULT_COMFORT_LIMIT, DEFAULT_HARD_CAP, Map.of());
    }

    /** The bounds the resolver applies: floor 0, ceiling the hard cap, comfort limit advisory. */
    public StatBounds bounds() {
        return new StatBounds(new Bound(FLOOR_RULE, SPEED_FLOOR), new Bound(HARD_CAP_RULE, hardCap))
            .withComfort(new Bound(COMFORT_RULE, comfortLimit));
    }

    /** The factor for a terrain / movement-profile key; 1 when none is configured. */
    public double factor(String key) {
        if (key == null) return 1.0;
        Double f = movementFactors.get(key.trim().toLowerCase(Locale.ROOT));
        return f == null ? 1.0 : f;
    }

    public SpeedProfile withId(String newId) {
        return new SpeedProfile(newId, baseSpeed, comfortLimit, hardCap, movementFactors);
    }

    public SpeedProfile withBaseSpeed(double speed) {
        return new SpeedProfile(id, speed, comfortLimit, hardCap, movementFactors);
    }

    public SpeedProfile withComfortLimit(double limit) {
        return new SpeedProfile(id, baseSpeed, limit, hardCap, movementFactors);
    }

    public SpeedProfile withHardCap(double cap) {
        return new SpeedProfile(id, baseSpeed, comfortLimit, cap, movementFactors);
    }

    public SpeedProfile withFactor(String key, double factor) {
        Map<String, Double> factors = new LinkedHashMap<>(movementFactors);
        factors.put(key, factor);
        return new SpeedProfile(id, baseSpeed, comfortLimit, hardCap, factors);
    }
}
