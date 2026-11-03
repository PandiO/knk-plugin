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
import net.knightsandkings.knk.core.npc.profile.TargetOverride;
import net.knightsandkings.knk.core.npc.profile.TargetTypes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatResolverTest {
    private static final NpcStat DMG = NpcStat.MELEE_DAMAGE;
    private static final StatBounds DMG_BOUNDS = StatBounds.floorOnly("melee-damage-floor", 0.0);

    @Test
    void baseWithoutModifiersIsUnchangedAndUnreported() {
        ResolvedStat r = StatResolver.resolve(DMG, 3.0, List.of(), DMG_BOUNDS);
        assertEquals(3.0, r.value());
        assertEquals(3.0, r.unclamped());
        assertEquals(3.0, r.base());
        assertTrue(r.reports().isEmpty());
        assertFalse(r.wasClamped());
    }

    @Test
    void additionsApplyBeforeMultipliers() {
        // (3 + 2) × 1.5 = 7.5, not 3 × 1.5 + 2 = 6.5
        List<StatModifier> mods = List.of(
            StatModifier.multiply("survival:round", DMG, 1.5),
            StatModifier.add("enchant:sharpness", DMG, 2.0));
        assertEquals(7.5, StatResolver.resolve(DMG, 3.0, mods, DMG_BOUNDS).value(), 1e-12);
    }

    @Test
    void resultIsIndependentOfModifierOrder() {
        List<StatModifier> a = List.of(
            StatModifier.multiply("survival:round", DMG, 1.35),
            StatModifier.add("a:flat", DMG, 1.0),
            StatModifier.multiply("survival:per-player", DMG, 1.2),
            StatModifier.add("b:flat", DMG, 0.5));
        List<StatModifier> b = List.of(a.get(3), a.get(2), a.get(1), a.get(0));
        assertEquals(StatResolver.resolve(DMG, 4.0, a, DMG_BOUNDS).value(),
            StatResolver.resolve(DMG, 4.0, b, DMG_BOUNDS).value(), 1e-12);
        assertEquals((4.0 + 1.5) * 1.35 * 1.2, StatResolver.resolve(DMG, 4.0, a, DMG_BOUNDS).value(), 1e-12);
    }

    @Test
    void modifiersForOtherStatsAreIgnored() {
        List<StatModifier> mods = List.of(StatModifier.multiply("x", NpcStat.MOVEMENT_SPEED, 10.0));
        assertEquals(3.0, StatResolver.resolve(DMG, 3.0, mods, DMG_BOUNDS).value());
    }

    @Test
    void floorClampIsReportedWithRuleAndValues() {
        StatBounds bounds = CombatProfile.DEFAULT_BOUNDS.get(NpcStat.MELEE_COOLDOWN);
        List<StatModifier> mods = List.of(StatModifier.multiply("survival:round", NpcStat.MELEE_COOLDOWN, 0.25));
        ResolvedStat r = StatResolver.resolve(NpcStat.MELEE_COOLDOWN, 20, mods, bounds);
        assertEquals(10.0, r.value());
        assertEquals(5.0, r.unclamped());
        assertTrue(r.wasClamped());
        assertEquals(1, r.reports().size());
        ClampReport report = r.reports().get(0);
        assertEquals(NpcStat.MELEE_COOLDOWN, report.stat());
        assertEquals(5.0, report.from());
        assertEquals(10.0, report.to());
        assertEquals("melee-cooldown-floor", report.bound().name());
        assertEquals(10.0, report.bound().limit());
        assertTrue(report.applied());
        assertEquals("melee-cooldown-ticks clamped 5.0 -> 10.0 by melee-cooldown-floor (10.0)", report.describe());
    }

    @Test
    void ceilingClampIsReported() {
        StatBounds bounds = StatBounds.of("f", 0, "damage-ceiling", 10);
        ResolvedStat r = StatResolver.resolve(DMG, 4.0, List.of(StatModifier.multiply("s", DMG, 5)), bounds);
        assertEquals(10.0, r.value());
        assertEquals("damage-ceiling", r.reports().get(0).bound().name());
        assertEquals(20.0, r.reports().get(0).from());
    }

    @Test
    void negativeDamageClampsToZero() {
        ResolvedStat r = StatResolver.resolve(DMG, 2.0, List.of(StatModifier.add("curse", DMG, -5)), DMG_BOUNDS);
        assertEquals(0.0, r.value());
        assertEquals(-3.0, r.unclamped());
        assertTrue(r.wasClamped());
    }

    @Test
    void tickStatsAreRoundedToWholeTicksBeforeBounds() {
        StatBounds bounds = CombatProfile.DEFAULT_BOUNDS.get(NpcStat.MELEE_COOLDOWN);
        ResolvedStat r = StatResolver.resolve(NpcStat.MELEE_COOLDOWN, 20,
            List.of(StatModifier.multiply("s", NpcStat.MELEE_COOLDOWN, 0.73)), bounds);
        assertEquals(15.0, r.unclamped()); // 14.6 → 15
        assertEquals(15, r.ticks());
        assertTrue(r.reports().isEmpty());
        // 21 × 0.5 = 10.5 rounds half to even → 10, which is the floor, so no clamp
        ResolvedStat half = StatResolver.resolve(NpcStat.MELEE_COOLDOWN, 21,
            List.of(StatModifier.multiply("s", NpcStat.MELEE_COOLDOWN, 0.5)), bounds);
        assertEquals(10, half.ticks());
        assertFalse(half.wasClamped());
        // 20 × 0.47 = 9.4 → 9 < floor 10 → clamped, reported from 9 (the rounded value)
        ResolvedStat low = StatResolver.resolve(NpcStat.MELEE_COOLDOWN, 20,
            List.of(StatModifier.multiply("s", NpcStat.MELEE_COOLDOWN, 0.47)), bounds);
        assertEquals(10, low.ticks());
        assertEquals(9.0, low.reports().get(0).from());
    }

    @Test
    void continuousStatsAreNotRounded() {
        ResolvedStat r = StatResolver.resolve(DMG, 3.0, List.of(StatModifier.multiply("s", DMG, 1.1)), DMG_BOUNDS);
        assertEquals(3.3, r.value(), 1e-12);
    }

    @Test
    void comfortLimitIsAnAdvisoryNotAClamp() {
        SpeedProfile profile = SpeedProfile.of("zombie", 0.23);
        ModifierSet mods = new ModifierSet();
        mods.put(StatModifier.multiply("survival:round", NpcStat.MOVEMENT_SPEED, 2.0)); // 0.46
        ResolvedSpeed r = StatResolver.resolveSpeed(profile, List.of(), mods);
        assertEquals(0.46, r.value(), 1e-12);
        assertTrue(r.aboveComfort());
        assertFalse(r.wasClamped());
        assertEquals(1, r.reports().size());
        ClampReport advisory = r.reports().get(0);
        assertFalse(advisory.applied());
        assertEquals(SpeedProfile.COMFORT_RULE, advisory.bound().name());
        assertEquals(0.4, advisory.bound().limit());
        assertEquals(advisory.from(), advisory.to());
        assertEquals("movement-speed = 0.46 above speed-comfort-limit (0.4)", advisory.describe());
    }

    @Test
    void hardCapClampsSpeedAndStillReportsComfort() {
        SpeedProfile profile = SpeedProfile.of("spider", 0.3);
        ModifierSet mods = new ModifierSet();
        mods.put(StatModifier.multiply("survival:round", NpcStat.MOVEMENT_SPEED, 3.0)); // 0.9
        ResolvedSpeed r = StatResolver.resolveSpeed(profile, null, mods);
        assertEquals(0.5, r.value());
        assertTrue(r.wasClamped());
        assertTrue(r.aboveComfort());
        assertEquals(2, r.reports().size());
        assertEquals(SpeedProfile.HARD_CAP_RULE, r.reports().get(0).bound().name());
        assertEquals(0.9, r.reports().get(0).from(), 1e-12);
        assertEquals(SpeedProfile.COMFORT_RULE, r.reports().get(1).bound().name());
    }

    @Test
    void terrainFactorsMultiplyTheBaseBeforeModifiers() {
        SpeedProfile profile = SpeedProfile.of("zombie", 0.2).withFactor("water", 0.5).withFactor("road", 1.5);
        ModifierSet mods = new ModifierSet();
        mods.put(StatModifier.add("buff", NpcStat.MOVEMENT_SPEED, 0.1));
        // (0.2 × 0.5 × 1.5 + 0.1) = 0.25; an unknown key counts as 1
        ResolvedSpeed r = StatResolver.resolveSpeed(profile, List.of("water", "ROAD", "lava"), mods);
        assertEquals(0.75, r.factorProduct(), 1e-12);
        assertEquals(0.25, r.value(), 1e-12);
        assertEquals(0.15, r.speed().base(), 1e-12);
    }

    @Test
    void combatResolutionUsesProfileTimingsAndVanillaBasesForDamage() {
        CombatProfile skeleton = CombatProfile.vanilla("skeleton");
        ResolvedCombat c = StatResolver.resolveCombat(skeleton, null, new ModifierSet(), new VanillaBases(2.0, 4.5));
        assertEquals("skeleton", c.profileId());
        assertEquals(TargetTypes.NONE, c.targetType());
        assertEquals(20, c.meleeCooldownTicks());
        assertEquals(2.0, c.meleeDamageValue());
        assertEquals(40, c.rangedCooldownTicks());
        assertEquals(20, c.drawTicks());
        assertEquals(4.5, c.projectileDamageValue());
        assertTrue(c.reports().isEmpty());
        assertFalse(c.wasClamped());
    }

    @Test
    void profileDamageOverridesVanillaBase() {
        CombatProfile brute = CombatProfile.vanilla("brute").withMeleeDamage(OptionalDouble.of(6.0));
        ResolvedCombat c = StatResolver.resolveCombat(brute, "player", new ModifierSet(), VanillaBases.melee(3.0));
        assertEquals(6.0, c.meleeDamageValue());
        assertEquals(6.0, c.meleeDamage().base());
    }

    @Test
    void targetOverridesApplyPerTargetType() {
        CombatProfile zombie = CombatProfile.vanilla("zombie")
            .withTargetOverride("gate", TargetOverride.NONE.withMeleeDamage(10.0).withMeleeCooldownTicks(30));
        ResolvedCombat gate = StatResolver.resolveCombat(zombie, "Gate", new ModifierSet(), VanillaBases.melee(3.0));
        assertEquals(10.0, gate.meleeDamageValue());
        assertEquals(30, gate.meleeCooldownTicks());
        assertEquals("gate", gate.targetType());
        ResolvedCombat player = StatResolver.resolveCombat(zombie, "player", new ModifierSet(), VanillaBases.melee(3.0));
        assertEquals(3.0, player.meleeDamageValue());
        assertEquals(20, player.meleeCooldownTicks());
    }

    @Test
    void survivalRoundModifiersScaleEveryCombatStatAndClampReportsOnce() {
        ModifierSet mods = new ModifierSet();
        for (NpcStat stat : NpcStat.COMBAT) {
            // cooldowns shrink, damage grows: a Survival consumer picks the direction per stat
            mods.put(StatModifier.multiply("survival:round", stat, stat.isTicks() ? 0.4 : 1.4));
        }
        ResolvedCombat c = StatResolver.resolveCombat(CombatProfile.vanilla("skeleton"), "player", mods,
            new VanillaBases(2.0, 4.0));
        assertEquals(10, c.meleeCooldownTicks());   // 8 → floor 10
        assertEquals(20, c.rangedCooldownTicks());  // 16 → floor 20
        assertEquals(8, c.drawTicks());             // 8, within cooldown
        assertEquals(2.8, c.meleeDamageValue(), 1e-12);
        assertEquals(5.6, c.projectileDamageValue(), 1e-12);
        List<ClampReport> reports = c.reports();
        assertEquals(2, reports.size());
        assertEquals("melee-cooldown-floor", reports.get(0).bound().name());
        assertEquals("ranged-cooldown-floor", reports.get(1).bound().name());
    }

    @Test
    void drawTimeIsCappedAtTheResolvedRangedCooldown() {
        CombatProfile archer = CombatProfile.vanilla("archer").withRangedCooldownTicks(25).withDrawTicks(30);
        ResolvedCombat c = StatResolver.resolveCombat(archer, "player", new ModifierSet(), VanillaBases.ZERO);
        assertEquals(25, c.rangedCooldownTicks());
        assertEquals(25, c.drawTicks());
        assertEquals(30.0, c.draw().unclamped());
        ClampReport report = c.draw().reports().get(0);
        assertEquals(CombatProfile.DRAW_WITHIN_COOLDOWN_RULE, report.bound().name());
        assertEquals(25.0, report.bound().limit());
        assertEquals(30.0, report.from());
        assertEquals(25.0, report.to());
    }

    @Test
    void drawFloorAppliesBeforeTheCooldownRule() {
        ModifierSet mods = new ModifierSet();
        mods.put(StatModifier.multiply("s", NpcStat.RANGED_DRAW, 0.0));
        ResolvedCombat c = StatResolver.resolveCombat(CombatProfile.vanilla("skeleton"), "", mods, VanillaBases.ZERO);
        assertEquals(1, c.drawTicks());
        assertEquals("ranged-draw-floor", c.draw().reports().get(0).bound().name());
    }

    @Test
    void nonFiniteValueFallsBackToTheFloorAndIsReported() {
        List<StatModifier> mods = List.of(StatModifier.multiply("s", DMG, Double.MAX_VALUE),
            StatModifier.multiply("t", DMG, Double.MAX_VALUE));
        ResolvedStat r = StatResolver.resolve(DMG, 2.0, mods, DMG_BOUNDS);
        assertEquals(0.0, r.value());
        assertEquals(StatResolver.NON_FINITE_RULE, r.reports().get(0).bound().name());
        assertTrue(Double.isInfinite(r.reports().get(0).from()));
        // without a finite floor the fallback is 0
        NpcStat custom = NpcStat.named("x");
        List<StatModifier> customMods = List.of(StatModifier.multiply("s", custom, Double.MAX_VALUE),
            StatModifier.multiply("t", custom, Double.MAX_VALUE));
        ResolvedStat unbounded = StatResolver.resolve(custom, 2.0, customMods, StatBounds.UNBOUNDED);
        assertEquals(0.0, unbounded.value());
        assertEquals(0.0, unbounded.reports().get(0).bound().limit());
    }

    @Test
    void customBoundsOnAProfileReplaceTheDefaults() {
        CombatProfile capped = CombatProfile.vanilla("capped")
            .withBounds(NpcStat.MELEE_COOLDOWN, StatBounds.of("melee-floor", 15, "melee-ceiling", 25));
        ModifierSet mods = new ModifierSet();
        mods.put(StatModifier.multiply("s", NpcStat.MELEE_COOLDOWN, 2.0));
        ResolvedCombat c = StatResolver.resolveCombat(capped, "", mods, VanillaBases.ZERO);
        assertEquals(25, c.meleeCooldownTicks());
        assertEquals("melee-ceiling", c.meleeCooldown().reports().get(0).bound().name());
        assertEquals("ranged-cooldown-ceiling", capped.boundsFor(NpcStat.RANGED_COOLDOWN).ceiling().name());
        assertEquals(new Bound("melee-floor", 15), capped.floorOf(NpcStat.MELEE_COOLDOWN));
        // the other stats keep their code defaults
        assertEquals(CombatProfile.DEFAULT_BOUNDS.get(NpcStat.RANGED_COOLDOWN), capped.boundsFor(NpcStat.RANGED_COOLDOWN));
        assertEquals(Map.copyOf(CombatProfile.DEFAULT_BOUNDS).size(), CombatProfile.vanilla("v").bounds().size());
    }
}
