package net.knightsandkings.knk.core.npc.resolve;

import net.knightsandkings.knk.core.npc.Bound;
import net.knightsandkings.knk.core.npc.ClampReport;
import net.knightsandkings.knk.core.npc.NpcStat;
import net.knightsandkings.knk.core.npc.ResolvedStat;
import net.knightsandkings.knk.core.npc.StatBounds;
import net.knightsandkings.knk.core.npc.modifier.ModifierSet;
import net.knightsandkings.knk.core.npc.modifier.StatModifier;
import net.knightsandkings.knk.core.npc.profile.CombatProfile;
import net.knightsandkings.knk.core.npc.profile.SpeedProfile;
import net.knightsandkings.knk.core.npc.profile.TargetTypes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Combines a base with an actor's modifiers and clamps the result to the stat's bounds, reporting
 * every bound that acted (NPC platform §5b, "Modifier pipeline"). Pure and deterministic.
 *
 * <p><b>Resolution order</b> (decided in link S2, flag S2-3):
 * <ol>
 *   <li>{@code sum = base + Σ ADD modifiers} — additions first, so a flat bonus is scaled like the base;</li>
 *   <li>{@code unclamped = sum × Π MULTIPLY modifiers} — multipliers are commutative, so the order
 *       between sources does not matter; the set iterates sources by name anyway;</li>
 *   <li>tick stats: {@code unclamped = rint(unclamped)} (round half to even), so bounds see whole ticks;</li>
 *   <li>a non-finite value (overflow, a zero base multiplied to NaN) is replaced by the floor (or 0
 *       when there is none) and reported under the rule {@code non-finite-value};</li>
 *   <li>floor, then ceiling, each reported when it changes the value;</li>
 *   <li>the comfort limit, reported as an advisory ({@code applied = false}) when exceeded.</li>
 * </ol>
 * For speed, the profile's terrain factors multiply the base <em>before</em> step 1, so a terrain
 * penalty scales a flat bonus too; for the draw time, a cross-stat rule caps it at the resolved
 * ranged cooldown after step 6 and reports under {@link CombatProfile#DRAW_WITHIN_COOLDOWN_RULE}.
 */
public final class StatResolver {
    public static final String NON_FINITE_RULE = "non-finite-value";

    private StatResolver() {}

    /** Resolves one stat; modifiers for other stats in the list are ignored. */
    public static ResolvedStat resolve(NpcStat stat, double base, Collection<StatModifier> modifiers, StatBounds bounds) {
        Objects.requireNonNull(stat, "stat");
        Objects.requireNonNull(bounds, "bounds");
        double sum = base;
        double product = 1.0;
        for (StatModifier m : modifiers) {
            if (!m.stat().equals(stat)) continue;
            switch (m.op()) {
                case ADD -> sum += m.value();
                case MULTIPLY -> product *= m.value();
            }
        }
        double unclamped = sum * product;
        if (stat.isTicks() && Double.isFinite(unclamped)) {
            unclamped = Math.rint(unclamped);
        }
        List<ClampReport> reports = new ArrayList<>(2);
        double value = unclamped;
        if (!Double.isFinite(value)) {
            double fallback = Double.isFinite(bounds.floor().limit()) ? bounds.floor().limit() : 0.0;
            Bound rule = new Bound(NON_FINITE_RULE, fallback);
            reports.add(ClampReport.clamped(stat, value, rule));
            value = fallback;
        }
        if (value < bounds.floor().limit()) {
            reports.add(ClampReport.clamped(stat, value, bounds.floor()));
            value = bounds.floor().limit();
        }
        if (value > bounds.ceiling().limit()) {
            reports.add(ClampReport.clamped(stat, value, bounds.ceiling()));
            value = bounds.ceiling().limit();
        }
        if (bounds.comfort().isPresent() && value > bounds.comfort().get().limit()) {
            reports.add(ClampReport.advisory(stat, value, bounds.comfort().get()));
        }
        return new ResolvedStat(stat, base, unclamped, value, reports);
    }

    /** Resolves one stat against an actor's modifier set. */
    public static ResolvedStat resolve(NpcStat stat, double base, ModifierSet modifiers, StatBounds bounds) {
        return resolve(stat, base, modifiers.forStat(stat), bounds);
    }

    /**
     * Resolves the five combat stats of {@code profile} for {@code targetType} against the actor's
     * modifiers. Damages the profile leaves vanilla take their base from {@code bases}.
     */
    public static ResolvedCombat resolveCombat(CombatProfile profile, String targetType, ModifierSet modifiers,
                                               VanillaBases bases) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(modifiers, "modifiers");
        Objects.requireNonNull(bases, "bases");
        String type = TargetTypes.normalize(targetType);
        CombatProfile p = profile.forTarget(type);

        ResolvedStat meleeCooldown = resolve(NpcStat.MELEE_COOLDOWN, p.meleeCooldownTicks(), modifiers,
            p.boundsFor(NpcStat.MELEE_COOLDOWN));
        ResolvedStat meleeDamage = resolve(NpcStat.MELEE_DAMAGE, p.meleeDamage().orElse(bases.meleeDamage()), modifiers,
            p.boundsFor(NpcStat.MELEE_DAMAGE));
        ResolvedStat rangedCooldown = resolve(NpcStat.RANGED_COOLDOWN, p.rangedCooldownTicks(), modifiers,
            p.boundsFor(NpcStat.RANGED_COOLDOWN));
        ResolvedStat draw = resolve(NpcStat.RANGED_DRAW, p.drawTicks(), modifiers, p.boundsFor(NpcStat.RANGED_DRAW));
        if (draw.value() > rangedCooldown.value()) {
            Bound rule = new Bound(CombatProfile.DRAW_WITHIN_COOLDOWN_RULE, rangedCooldown.value());
            List<ClampReport> reports = new ArrayList<>(draw.reports());
            reports.add(ClampReport.clamped(NpcStat.RANGED_DRAW, draw.value(), rule));
            draw = new ResolvedStat(draw.stat(), draw.base(), draw.unclamped(), rangedCooldown.value(), reports);
        }
        ResolvedStat projectileDamage = resolve(NpcStat.PROJECTILE_DAMAGE,
            p.projectileDamage().orElse(bases.projectileDamage()), modifiers, p.boundsFor(NpcStat.PROJECTILE_DAMAGE));
        return new ResolvedCombat(profile.id(), type, meleeCooldown, meleeDamage, rangedCooldown, draw, projectileDamage);
    }

    /**
     * Resolves the movement speed of {@code profile} against the actor's modifiers. Every key in
     * {@code factorKeys} that the profile knows multiplies the base first; unknown keys count as 1.
     */
    public static ResolvedSpeed resolveSpeed(SpeedProfile profile, Collection<String> factorKeys, ModifierSet modifiers) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(modifiers, "modifiers");
        double factorProduct = 1.0;
        if (factorKeys != null) {
            for (String key : factorKeys) factorProduct *= profile.factor(key);
        }
        ResolvedStat speed = resolve(NpcStat.MOVEMENT_SPEED, profile.baseSpeed() * factorProduct, modifiers, profile.bounds());
        return new ResolvedSpeed(profile.id(), factorProduct, speed);
    }
}
