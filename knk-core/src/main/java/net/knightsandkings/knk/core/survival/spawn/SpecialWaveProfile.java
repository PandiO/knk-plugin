package net.knightsandkings.knk.core.survival.spawn;

import java.util.OptionalInt;

/**
 * A special-wave enemy profile (S14, S53, §7 "Special profiles can specify different archetypes, amounts and
 * scaling"). By default it replaces the normal composition; only with {@code additive} on is it added on top of
 * the normal wave (boss events). The global wave index advances either way.
 *
 * @param id              the profile's id, referenced by the schedule
 * @param mix             its monster mix; an empty mix means "the scenario's normal mix"
 * @param countOverride   a fixed enemy count, or empty to derive it from the normal count
 * @param countMultiplier factor on the normal count when there is no override (1 = same count)
 * @param additive        add to the normal composition instead of replacing it (explicit setting)
 */
public record SpecialWaveProfile(String id, MonsterMix mix, OptionalInt countOverride, double countMultiplier, boolean additive) {

    public SpecialWaveProfile {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (mix == null) mix = new MonsterMix(java.util.List.of());
        if (countOverride == null) countOverride = OptionalInt.empty();
        if (countOverride.isPresent() && countOverride.getAsInt() < 1) throw new IllegalArgumentException("countOverride must be >= 1");
        if (!Double.isFinite(countMultiplier) || countMultiplier <= 0) throw new IllegalArgumentException("countMultiplier must be > 0");
    }

    /** A replacing profile with its own mix and the normal count. */
    public static SpecialWaveProfile replacing(String id, MonsterMix mix) {
        return new SpecialWaveProfile(id, mix, OptionalInt.empty(), 1, false);
    }

    /** The profile's count for a round, given the normal count. Never below 1. */
    public int count(int normalCount) {
        if (countOverride.isPresent()) return countOverride.getAsInt();
        return Math.max(1, (int) Math.floor(normalCount * countMultiplier));
    }
}
