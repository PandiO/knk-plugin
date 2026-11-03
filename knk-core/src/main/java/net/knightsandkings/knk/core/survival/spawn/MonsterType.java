package net.knightsandkings.knk.core.survival.spawn;

/**
 * An admin-selected vanilla monster type of a scenario (S42, S56).
 *
 * @param id          the vanilla type's identifier, e.g. {@code ZOMBIE} (opaque to the core)
 * @param weight      draw weight; equal chance per type by default ({@link #DEFAULT_WEIGHT})
 * @param unlockRound the first round in which the type can appear (default 1)
 */
public record MonsterType(String id, double weight, int unlockRound) {

    public static final double DEFAULT_WEIGHT = 1.0;
    public static final int DEFAULT_UNLOCK_ROUND = 1;

    public MonsterType {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (!Double.isFinite(weight) || weight <= 0) throw new IllegalArgumentException("weight must be > 0: " + weight);
        if (unlockRound < 1) throw new IllegalArgumentException("unlockRound must be >= 1: " + unlockRound);
    }

    public static MonsterType of(String id) {
        return new MonsterType(id, DEFAULT_WEIGHT, DEFAULT_UNLOCK_ROUND);
    }

    public MonsterType withWeight(double weight) {
        return new MonsterType(id, weight, unlockRound);
    }

    public MonsterType unlockedAt(int round) {
        return new MonsterType(id, weight, round);
    }

    public boolean unlockedIn(int round) {
        return round >= unlockRound;
    }
}
