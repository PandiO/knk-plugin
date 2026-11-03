package net.knightsandkings.knk.core.npc.resolve;

import net.knightsandkings.knk.core.npc.ClampReport;
import net.knightsandkings.knk.core.npc.ResolvedStat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The five combat stats of one actor against one target type, resolved and clamped. The adapter
 * reads the tick counts for its attack goals and the damages for its damage hooks; {@link #reports()}
 * is what it logs once per modifier change.
 *
 * @param profileId        the profile the values came from
 * @param targetType       the target type the overrides were applied for ({@code ""} for none)
 * @param meleeCooldown    ticks between melee attacks (floor 10 by default)
 * @param meleeDamage      melee damage per hit
 * @param rangedCooldown   ticks between shots (floor 20 by default)
 * @param draw             draw ticks before release (floor 1, never above the ranged cooldown)
 * @param projectileDamage projectile damage per shot
 */
public record ResolvedCombat(
    String profileId,
    String targetType,
    ResolvedStat meleeCooldown,
    ResolvedStat meleeDamage,
    ResolvedStat rangedCooldown,
    ResolvedStat draw,
    ResolvedStat projectileDamage
) {
    public ResolvedCombat {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(meleeCooldown, "meleeCooldown");
        Objects.requireNonNull(meleeDamage, "meleeDamage");
        Objects.requireNonNull(rangedCooldown, "rangedCooldown");
        Objects.requireNonNull(draw, "draw");
        Objects.requireNonNull(projectileDamage, "projectileDamage");
    }

    public int meleeCooldownTicks() {
        return meleeCooldown.ticks();
    }

    public double meleeDamageValue() {
        return meleeDamage.value();
    }

    public int rangedCooldownTicks() {
        return rangedCooldown.ticks();
    }

    public int drawTicks() {
        return draw.ticks();
    }

    public double projectileDamageValue() {
        return projectileDamage.value();
    }

    public List<ResolvedStat> stats() {
        return List.of(meleeCooldown, meleeDamage, rangedCooldown, draw, projectileDamage);
    }

    /** Every bound that acted on any of the five stats, in resolution order. */
    public List<ClampReport> reports() {
        List<ClampReport> out = new ArrayList<>();
        for (ResolvedStat s : stats()) out.addAll(s.reports());
        return List.copyOf(out);
    }

    public boolean wasClamped() {
        return stats().stream().anyMatch(ResolvedStat::wasClamped);
    }
}
