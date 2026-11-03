package net.knightsandkings.knk.core.npc.profile;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Per-target-type overrides of a {@link CombatProfile}'s values. Every field is optional; a present
 * field replaces the profile's value when the actor attacks that target type (a gate may take more
 * melee damage per hit, a player may be shot with a longer draw). An empty override changes nothing.
 */
public record TargetOverride(
    OptionalInt meleeCooldownTicks,
    OptionalDouble meleeDamage,
    OptionalInt rangedCooldownTicks,
    OptionalInt drawTicks,
    OptionalDouble projectileDamage
) {
    public static final TargetOverride NONE = new TargetOverride(
        OptionalInt.empty(), OptionalDouble.empty(), OptionalInt.empty(), OptionalInt.empty(), OptionalDouble.empty());

    public TargetOverride {
        meleeCooldownTicks = meleeCooldownTicks == null ? OptionalInt.empty() : meleeCooldownTicks;
        meleeDamage = meleeDamage == null ? OptionalDouble.empty() : meleeDamage;
        rangedCooldownTicks = rangedCooldownTicks == null ? OptionalInt.empty() : rangedCooldownTicks;
        drawTicks = drawTicks == null ? OptionalInt.empty() : drawTicks;
        projectileDamage = projectileDamage == null ? OptionalDouble.empty() : projectileDamage;
    }

    public boolean isEmpty() {
        return meleeCooldownTicks.isEmpty() && meleeDamage.isEmpty() && rangedCooldownTicks.isEmpty()
            && drawTicks.isEmpty() && projectileDamage.isEmpty();
    }

    public TargetOverride withMeleeCooldownTicks(int ticks) {
        return new TargetOverride(OptionalInt.of(ticks), meleeDamage, rangedCooldownTicks, drawTicks, projectileDamage);
    }

    public TargetOverride withMeleeDamage(double damage) {
        return new TargetOverride(meleeCooldownTicks, OptionalDouble.of(damage), rangedCooldownTicks, drawTicks, projectileDamage);
    }

    public TargetOverride withRangedCooldownTicks(int ticks) {
        return new TargetOverride(meleeCooldownTicks, meleeDamage, OptionalInt.of(ticks), drawTicks, projectileDamage);
    }

    public TargetOverride withDrawTicks(int ticks) {
        return new TargetOverride(meleeCooldownTicks, meleeDamage, rangedCooldownTicks, OptionalInt.of(ticks), projectileDamage);
    }

    public TargetOverride withProjectileDamage(double damage) {
        return new TargetOverride(meleeCooldownTicks, meleeDamage, rangedCooldownTicks, drawTicks, OptionalDouble.of(damage));
    }
}
