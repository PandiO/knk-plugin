package net.knightsandkings.knk.core.targeting;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The scoring and stability settings of one NPC profile / behaviour (NPC platform DESIGN §5a; Siege Survival DESIGN §4
 * "target preference and decision stability"). Every value is a code default the consumer may replace; none is a
 * design value (the design leaves the numbers to balance tests).
 *
 * <p>Score = {@code preference(kind) × proximity}, with {@code proximity = 1 / (1 + distance / proximityScale)}:
 * closer candidates score higher and a far target is never rewarded (§5a rule 2). An actor keeps its target for
 * {@code minCommitmentMillis}; afterwards a challenger must beat the current score by the relative
 * {@code switchMargin} (rule 4). Players are only candidates within {@code aggroRadius} of the actor, and a chased
 * player farther than {@code leashDistance} from the actor's anchor (its structural target) is dropped (DESIGN §4
 * aggro range and leash distance).
 *
 * @param preferences         per-kind preference weights (missing kind = 0 = never chosen)
 * @param proximityScale      the distance (blocks) at which proximity has fallen to 1/2
 * @param minCommitmentMillis minimum commitment before ordinary score changes may replace the target
 * @param switchMargin        relative margin a challenger must exceed after the commitment (0.25 = 25 % higher)
 * @param aggroRadius         players beyond this distance from the actor are not candidates
 * @param leashDistance       a player farther than this from the anchor releases the chase
 */
public record TargetPolicy(Map<TargetKind, Double> preferences, double proximityScale, long minCommitmentMillis,
                           double switchMargin, double aggroRadius, double leashDistance) {

    public static final double DEFAULT_PLAYER_PREFERENCE = 1.0;
    public static final double DEFAULT_GATE_PREFERENCE = 0.9;
    public static final double DEFAULT_OBJECTIVE_PREFERENCE = 1.0;
    public static final double DEFAULT_PROXIMITY_SCALE = 16.0;
    public static final long DEFAULT_MIN_COMMITMENT_MILLIS = 4_000L;
    public static final double DEFAULT_SWITCH_MARGIN = 0.25;
    public static final double DEFAULT_AGGRO_RADIUS = 12.0;
    public static final double DEFAULT_LEASH_DISTANCE = 24.0;

    public TargetPolicy {
        Objects.requireNonNull(preferences, "preferences");
        Map<TargetKind, Double> copy = new EnumMap<>(TargetKind.class);
        preferences.forEach((k, v) -> {
            if (v == null || !Double.isFinite(v) || v < 0) throw new IllegalArgumentException("preference of " + k + " must be >= 0");
            copy.put(k, v);
        });
        preferences = Map.copyOf(copy);
        if (!(proximityScale > 0) || !Double.isFinite(proximityScale)) throw new IllegalArgumentException("proximityScale must be > 0");
        if (minCommitmentMillis < 0) throw new IllegalArgumentException("minCommitmentMillis must be >= 0");
        if (!(switchMargin >= 0) || !Double.isFinite(switchMargin)) throw new IllegalArgumentException("switchMargin must be >= 0");
        if (!(aggroRadius >= 0) || !Double.isFinite(aggroRadius)) throw new IllegalArgumentException("aggroRadius must be >= 0");
        if (!(leashDistance >= 0) || !Double.isFinite(leashDistance)) throw new IllegalArgumentException("leashDistance must be >= 0");
    }

    public static TargetPolicy defaults() {
        return new TargetPolicy(Map.of(
                TargetKind.PLAYER, DEFAULT_PLAYER_PREFERENCE,
                TargetKind.GATE, DEFAULT_GATE_PREFERENCE,
                TargetKind.OBJECTIVE, DEFAULT_OBJECTIVE_PREFERENCE),
            DEFAULT_PROXIMITY_SCALE, DEFAULT_MIN_COMMITMENT_MILLIS, DEFAULT_SWITCH_MARGIN,
            DEFAULT_AGGRO_RADIUS, DEFAULT_LEASH_DISTANCE);
    }

    public double preference(TargetKind kind) {
        return preferences.getOrDefault(kind, 0.0);
    }

    public TargetPolicy withAggroRadius(double radius) {
        return new TargetPolicy(preferences, proximityScale, minCommitmentMillis, switchMargin, radius, leashDistance);
    }

    public TargetPolicy withLeashDistance(double distance) {
        return new TargetPolicy(preferences, proximityScale, minCommitmentMillis, switchMargin, aggroRadius, distance);
    }

    public TargetPolicy withCommitment(long millis, double margin) {
        return new TargetPolicy(preferences, proximityScale, millis, margin, aggroRadius, leashDistance);
    }

    public TargetPolicy withPreference(TargetKind kind, double preference) {
        Map<TargetKind, Double> copy = new EnumMap<>(TargetKind.class);
        copy.putAll(preferences);
        copy.put(kind, preference);
        return new TargetPolicy(copy, proximityScale, minCommitmentMillis, switchMargin, aggroRadius, leashDistance);
    }

    /** The score of a candidate at {@code distance} blocks (0 for an unknown kind). */
    public double score(TargetKind kind, double distance) {
        double preference = preference(kind);
        if (preference <= 0) return 0;
        return preference / (1 + Math.max(0, distance) / proximityScale);
    }
}
