package net.knightsandkings.knk.core.npc.profile;

import net.knightsandkings.knk.core.npc.Bound;
import net.knightsandkings.knk.core.npc.NpcStat;
import net.knightsandkings.knk.core.npc.StatBounds;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * Attack timing and damage of one NPC type or named profile (NPC platform §5b, "Combat profile").
 * Values are the base the modifier pipeline works on; bounds are what the resolver clamps to.
 *
 * <p>Defaults follow the implementation checks report §4: vanilla melee goals attack every 20 ticks,
 * skeleton bows every 40 ticks at minimum with 20 ticks of drawing; victims ignore most of a second
 * hit within 10 ticks, hence the melee cooldown floor of 10.
 *
 * <p>Damage values are {@link OptionalDouble}: empty means <em>vanilla</em>, the adapter supplies the
 * live engine value as the base (the mob's {@code ATTACK_DAMAGE}, the arrow's damage in the shoot
 * event) and the pipeline only scales and clamps it. The report gives no damage numbers, so the
 * code defaults leave every damage vanilla.
 *
 * @param id                  profile id, lower-case (a vanilla type name such as {@code zombie} or a
 *                            consumer's own id)
 * @param meleeCooldownTicks  ticks between melee attacks
 * @param meleeDamage         melee damage, or empty for vanilla
 * @param rangedCooldownTicks ticks between shots (draw included)
 * @param drawTicks           ticks the bow is drawn before release; resolved to at most the ranged cooldown
 * @param projectileDamage    projectile damage, or empty for vanilla
 * @param targetOverrides     per-target-type overrides, keyed by {@link TargetTypes} string
 * @param bounds              floors and ceilings per combat stat; stats absent fall back to {@link #DEFAULT_BOUNDS}
 */
public record CombatProfile(
    String id,
    int meleeCooldownTicks,
    OptionalDouble meleeDamage,
    int rangedCooldownTicks,
    int drawTicks,
    OptionalDouble projectileDamage,
    Map<String, TargetOverride> targetOverrides,
    Map<NpcStat, StatBounds> bounds
) {
    /** Vanilla melee interval ({@code MeleeAttackGoal.adjustedTickDelay(20)}). */
    public static final int VANILLA_MELEE_COOLDOWN_TICKS = 20;
    /** Vanilla skeleton bow minimum interval (40; 20 on Hard). */
    public static final int VANILLA_RANGED_COOLDOWN_TICKS = 40;
    /** Vanilla bow draw before release (≥ 20 ticks). */
    public static final int VANILLA_DRAW_TICKS = 20;

    /** Invulnerability frames: a second hit within 10 ticks is mostly ignored, so faster is wasted. */
    public static final int MELEE_COOLDOWN_FLOOR_TICKS = 10;
    /** The vanilla Hard-difficulty minimum for bows; the lowest rate the design has evidence for. */
    public static final int RANGED_COOLDOWN_FLOOR_TICKS = 20;
    /** A draw of zero ticks is not an attack animation at all. */
    public static final int DRAW_FLOOR_TICKS = 1;

    /** Code-default bounds per combat stat. All ceilings are open; configuration may close them. */
    public static final Map<NpcStat, StatBounds> DEFAULT_BOUNDS = Map.of(
        NpcStat.MELEE_COOLDOWN, StatBounds.floorOnly("melee-cooldown-floor", MELEE_COOLDOWN_FLOOR_TICKS),
        NpcStat.MELEE_DAMAGE, StatBounds.floorOnly("melee-damage-floor", 0.0),
        NpcStat.RANGED_COOLDOWN, StatBounds.floorOnly("ranged-cooldown-floor", RANGED_COOLDOWN_FLOOR_TICKS),
        NpcStat.RANGED_DRAW, StatBounds.floorOnly("ranged-draw-floor", DRAW_FLOOR_TICKS),
        NpcStat.PROJECTILE_DAMAGE, StatBounds.floorOnly("projectile-damage-floor", 0.0));

    /** The cross-stat rule: draw time never exceeds the resolved ranged cooldown. */
    public static final String DRAW_WITHIN_COOLDOWN_RULE = "ranged-draw-within-cooldown";

    public CombatProfile {
        Objects.requireNonNull(id, "id");
        id = id.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) throw new IllegalArgumentException("profile id must not be blank");
        meleeDamage = meleeDamage == null ? OptionalDouble.empty() : meleeDamage;
        projectileDamage = projectileDamage == null ? OptionalDouble.empty() : projectileDamage;
        Map<String, TargetOverride> overrides = new TreeMap<>();
        if (targetOverrides != null) {
            targetOverrides.forEach((type, override) -> {
                if (override != null && !override.isEmpty()) overrides.put(TargetTypes.normalize(type), override);
            });
        }
        targetOverrides = Map.copyOf(overrides);
        Map<NpcStat, StatBounds> merged = new LinkedHashMap<>(DEFAULT_BOUNDS);
        if (bounds != null) merged.putAll(bounds);
        bounds = Map.copyOf(merged);
    }

    /** A profile with the vanilla timings and vanilla (engine-supplied) damage. */
    public static CombatProfile vanilla(String id) {
        return new CombatProfile(id, VANILLA_MELEE_COOLDOWN_TICKS, OptionalDouble.empty(),
            VANILLA_RANGED_COOLDOWN_TICKS, VANILLA_DRAW_TICKS, OptionalDouble.empty(), Map.of(), Map.of());
    }

    /** The bounds for a combat stat (code defaults for stats without an entry). */
    public StatBounds boundsFor(NpcStat stat) {
        StatBounds b = bounds.get(stat);
        return b != null ? b : DEFAULT_BOUNDS.getOrDefault(stat, StatBounds.UNBOUNDED);
    }

    /** The profile's values with the override for {@code targetType} applied (none: this profile). */
    public CombatProfile forTarget(String targetType) {
        TargetOverride o = targetOverrides.get(TargetTypes.normalize(targetType));
        if (o == null) return this;
        return new CombatProfile(id,
            o.meleeCooldownTicks().orElse(meleeCooldownTicks),
            o.meleeDamage().isPresent() ? o.meleeDamage() : meleeDamage,
            o.rangedCooldownTicks().orElse(rangedCooldownTicks),
            o.drawTicks().orElse(drawTicks),
            o.projectileDamage().isPresent() ? o.projectileDamage() : projectileDamage,
            Map.of(), bounds);
    }

    public CombatProfile withId(String newId) {
        return new CombatProfile(newId, meleeCooldownTicks, meleeDamage, rangedCooldownTicks, drawTicks,
            projectileDamage, targetOverrides, bounds);
    }

    public CombatProfile withMeleeCooldownTicks(int ticks) {
        return new CombatProfile(id, ticks, meleeDamage, rangedCooldownTicks, drawTicks, projectileDamage, targetOverrides, bounds);
    }

    public CombatProfile withMeleeDamage(OptionalDouble damage) {
        return new CombatProfile(id, meleeCooldownTicks, damage, rangedCooldownTicks, drawTicks, projectileDamage, targetOverrides, bounds);
    }

    public CombatProfile withRangedCooldownTicks(int ticks) {
        return new CombatProfile(id, meleeCooldownTicks, meleeDamage, ticks, drawTicks, projectileDamage, targetOverrides, bounds);
    }

    public CombatProfile withDrawTicks(int ticks) {
        return new CombatProfile(id, meleeCooldownTicks, meleeDamage, rangedCooldownTicks, ticks, projectileDamage, targetOverrides, bounds);
    }

    public CombatProfile withProjectileDamage(OptionalDouble damage) {
        return new CombatProfile(id, meleeCooldownTicks, meleeDamage, rangedCooldownTicks, drawTicks, damage, targetOverrides, bounds);
    }

    public CombatProfile withTargetOverride(String targetType, TargetOverride override) {
        Map<String, TargetOverride> overrides = new LinkedHashMap<>(targetOverrides);
        overrides.put(TargetTypes.normalize(targetType), override);
        return new CombatProfile(id, meleeCooldownTicks, meleeDamage, rangedCooldownTicks, drawTicks, projectileDamage, overrides, bounds);
    }

    public CombatProfile withBounds(NpcStat stat, StatBounds statBounds) {
        Map<NpcStat, StatBounds> b = new LinkedHashMap<>(bounds);
        b.put(stat, statBounds);
        return new CombatProfile(id, meleeCooldownTicks, meleeDamage, rangedCooldownTicks, drawTicks, projectileDamage, targetOverrides, b);
    }

    /** The floor bound of a stat, for reports and readiness checks. */
    public Bound floorOf(NpcStat stat) {
        return boundsFor(stat).floor();
    }
}
