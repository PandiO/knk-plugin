package net.knightsandkings.knk.core.npc.profile;

import net.knightsandkings.knk.core.npc.NpcStat;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileOverridesTest {

    private static ProfileRegistry.OverrideResult apply(Map<String, Map<String, Object>> raw) {
        return ProfileRegistry.vanilla().withOverrides(new ProfileOverrides(raw));
    }

    @Test
    void emptyOverridesLeaveTheRegistryAsIs() {
        ProfileRegistry.OverrideResult result = ProfileRegistry.vanilla().withOverrides(ProfileOverrides.NONE);
        assertTrue(result.clean());
        assertEquals(ProfileRegistry.vanilla().combatIds(), result.registry().combatIds());
        assertEquals(CombatProfile.vanilla("zombie"), result.registry().combat("zombie"));
        assertTrue(ProfileOverrides.NONE.isEmpty());
    }

    @Test
    void combatFieldsAcceptNumbersAndNumericStrings() {
        ProfileRegistry.OverrideResult result = apply(Map.of("zombie", Map.of(
            "combat.melee-cooldown-ticks", 16,
            "combat.melee-damage", "4.5",
            "combat.ranged-cooldown-ticks", 30L,
            "combat.ranged-draw-ticks", 15.0,
            "combat.projectile-damage", 7)));
        assertTrue(result.clean(), () -> result.rejected().toString());
        CombatProfile z = result.registry().combat("zombie");
        assertEquals(16, z.meleeCooldownTicks());
        assertEquals(4.5, z.meleeDamage().getAsDouble());
        assertEquals(30, z.rangedCooldownTicks());
        assertEquals(15, z.drawTicks());
        assertEquals(7.0, z.projectileDamage().getAsDouble());
        // other profiles untouched
        assertEquals(CombatProfile.vanilla("skeleton"), result.registry().combat("skeleton"));
    }

    @Test
    void vanillaResetsADamageField() {
        ProfileRegistry withDamage = apply(Map.of("zombie", Map.of("combat.melee-damage", 5))).registry();
        ProfileRegistry.OverrideResult result = withDamage.withOverrides(new ProfileOverrides(
            Map.of("zombie", Map.of("combat.melee-damage", "Vanilla"))));
        assertTrue(result.clean());
        assertTrue(result.registry().combat("zombie").meleeDamage().isEmpty());
    }

    @Test
    void targetOverridesAndBoundsAreAddressable() {
        ProfileRegistry.OverrideResult result = apply(Map.of("zombie", Map.of(
            "combat.targets.gate.melee-damage", 12,
            "combat.targets.gate.melee-cooldown-ticks", 30,
            "combat.targets.Player.ranged-draw-ticks", 25,
            "combat.bounds.melee-cooldown-ticks.floor", 12,
            "combat.bounds.melee-damage.ceiling", 20)));
        assertTrue(result.clean(), () -> result.rejected().toString());
        CombatProfile z = result.registry().combat("zombie");
        assertEquals(12.0, z.forTarget("gate").meleeDamage().getAsDouble());
        assertEquals(30, z.forTarget("gate").meleeCooldownTicks());
        assertEquals(25, z.forTarget("player").drawTicks());
        assertEquals(2, z.targetOverrides().size());
        assertEquals(12.0, z.floorOf(NpcStat.MELEE_COOLDOWN).limit());
        assertEquals("melee-cooldown-floor", z.floorOf(NpcStat.MELEE_COOLDOWN).name(), "rule name survives a config limit");
        assertEquals(20.0, z.boundsFor(NpcStat.MELEE_DAMAGE).ceiling().limit());
    }

    @Test
    void speedFieldsAndFactors() {
        ProfileRegistry.OverrideResult result = apply(Map.of("spider", Map.of(
            "speed.base-speed", 0.32,
            "speed.hard-cap", 0.6,
            "speed.comfort-limit", 0.45,
            "speed.factors.web", 0.5,
            "speed.factors.Road", "1.25")));
        assertTrue(result.clean(), () -> result.rejected().toString());
        SpeedProfile s = result.registry().speed("spider");
        assertEquals(0.32, s.baseSpeed());
        assertEquals(0.6, s.hardCap());
        assertEquals(0.45, s.comfortLimit());
        assertEquals(0.5, s.factor("web"));
        assertEquals(1.25, s.factor("road"));
    }

    @Test
    void newProfilesAreCreatedFromBasedOn() {
        ProfileRegistry.OverrideResult result = apply(Map.of("survival:brute", Map.of(
            "based-on", "Zombie",
            "combat.melee-damage", 8,
            "speed.base-speed", 0.2)));
        assertTrue(result.clean(), () -> result.rejected().toString());
        CombatProfile brute = result.registry().combat("survival:brute");
        assertEquals("survival:brute", brute.id());
        assertEquals(8.0, brute.meleeDamage().getAsDouble());
        assertEquals(20, brute.meleeCooldownTicks());
        SpeedProfile speed = result.registry().speed("survival:brute");
        assertEquals("survival:brute", speed.id());
        assertEquals(0.2, speed.baseSpeed());
        assertEquals(6, result.registry().combatIds().size());
    }

    @Test
    void newProfileWithoutBasedOnStartsFromDefault() {
        ProfileRegistry.OverrideResult result = apply(Map.of("archer", Map.of("combat.ranged-cooldown-ticks", 30)));
        assertTrue(result.clean());
        assertEquals(30, result.registry().combat("archer").rangedCooldownTicks());
        assertEquals(VanillaProfiles.ZOMBIE_SPEED, result.registry().speed("archer").baseSpeed());
    }

    @Test
    void unknownBasedOnIsRejectedAndDefaultUsed() {
        ProfileRegistry.OverrideResult result = apply(Map.of("ghost", Map.of("based-on", "phantom")));
        assertEquals(1, result.rejected().size());
        assertTrue(result.rejected().get(0).startsWith("ghost/based-on: unknown profile 'phantom'"), result.rejected().get(0));
        assertEquals("ghost", result.registry().combat("ghost").id());
        assertEquals(VanillaProfiles.ZOMBIE_SPEED, result.registry().speed("ghost").baseSpeed());
    }

    @Test
    void badEntriesAreRejectedWithReasonsAndGoodOnesStillApply() {
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("combat.melee-cooldown-ticks", 16);          // good
        entries.put("combat.melee-cooldown-tick", 5);            // unknown key
        entries.put("combat.melee-damage", "lots");              // not a number
        entries.put("combat.ranged-draw-ticks", 2.5);            // not whole ticks
        entries.put("combat.projectile-damage", -1);             // negative
        entries.put("combat.targets.gate", 1);                   // malformed target key
        entries.put("combat.targets.gate.nope", 1);              // unknown target field
        entries.put("combat.bounds.movement-speed.floor", 1);    // not a combat stat
        entries.put("combat.bounds.melee-damage.middle", 1);     // not floor/ceiling
        entries.put("combat.bounds.melee-damage.floor", 50);     // floor above the (default, infinite) ceiling? no: fine
        entries.put("speed.comfort-limit", 0.9);                 // above hard cap 0.5
        entries.put("speed.factors.", 1);                        // blank factor key
        entries.put("speed.warp", 2);                            // unknown speed key
        entries.put("colour", "red");                            // neither section
        entries.put("combat.melee-cooldown-ticks ", 18);         // trailing space trimmed: last write wins? keys are
                                                                 // normalized, so this replaces the first entry
        ProfileRegistry.OverrideResult result = apply(Map.of("zombie", entries));
        List<String> rejected = result.rejected();
        assertFalse(result.clean());
        assertEquals(12, rejected.size(), () -> rejected.toString());
        rejected.forEach(line -> assertTrue(line.startsWith("zombie/"), line));
        assertTrue(rejected.stream().anyMatch(l -> l.startsWith("zombie/combat.melee-cooldown-tick: unknown combat key")));
        assertTrue(rejected.stream().anyMatch(l -> l.contains("combat.melee-damage: expected a number, got 'lots'")));
        assertTrue(rejected.stream().anyMatch(l -> l.contains("combat.ranged-draw-ticks: expected a whole number of ticks")));
        assertTrue(rejected.stream().anyMatch(l -> l.contains("combat.projectile-damage: must be ≥ 0")));
        assertTrue(rejected.stream().anyMatch(l -> l.contains("combat.targets.gate: expected targets.<type>.<field>")));
        assertTrue(rejected.stream().anyMatch(l -> l.contains("combat.targets.gate.nope: unknown target field")));
        assertTrue(rejected.stream().anyMatch(l -> l.contains("combat.bounds.movement-speed.floor: unknown combat stat")));
        assertTrue(rejected.stream().anyMatch(l -> l.contains("combat.bounds.melee-damage.middle: expected floor or ceiling")));
        assertTrue(rejected.stream().anyMatch(l -> l.contains("speed.comfort-limit: comfortLimit 0.9 above hardCap 0.5")));
        assertTrue(rejected.stream().anyMatch(l -> l.contains("speed.factors.: factor key must not be blank")));
        assertTrue(rejected.stream().anyMatch(l -> l.contains("speed.warp: unknown speed key")));
        assertTrue(rejected.stream().anyMatch(l -> l.contains("colour: unknown key")));
        CombatProfile z = result.registry().combat("zombie");
        assertEquals(18, z.meleeCooldownTicks());
        assertEquals(50.0, z.floorOf(NpcStat.MELEE_DAMAGE).limit());
        assertTrue(z.meleeDamage().isEmpty());
        assertEquals(20, z.drawTicks());
        assertEquals(0.4, result.registry().speed("zombie").comfortLimit());
    }

    @Test
    void crossingBoundsAreRejectedAndThePreviousKept() {
        ProfileRegistry.OverrideResult result = apply(Map.of("zombie", Map.of(
            "combat.bounds.melee-cooldown-ticks.ceiling", 5)));
        assertEquals(1, result.rejected().size());
        assertTrue(result.rejected().get(0).contains("floor Bound[name=melee-cooldown-floor, limit=10.0] above ceiling"),
            result.rejected().get(0));
        assertTrue(result.registry().combat("zombie").boundsFor(NpcStat.MELEE_COOLDOWN).ceiling().isUnbounded());
    }

    @Test
    void overridesNormalizeProfileIdsAndKeysAndIgnoreNulls() {
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put(" Combat.Melee-Cooldown-Ticks ", 12);
        entries.put("combat.melee-damage", null);
        Map<String, Map<String, Object>> raw = new LinkedHashMap<>();
        raw.put(" ZOMBIE ", entries);
        raw.put("  ", Map.of("combat.melee-damage", 1));
        ProfileOverrides overrides = new ProfileOverrides(raw);
        assertEquals(Map.of("zombie", Map.of("combat.melee-cooldown-ticks", 12)), overrides.byProfile());
        assertEquals(12, ProfileRegistry.vanilla().withOverrides(overrides).registry().combat("zombie").meleeCooldownTicks());
    }
}
