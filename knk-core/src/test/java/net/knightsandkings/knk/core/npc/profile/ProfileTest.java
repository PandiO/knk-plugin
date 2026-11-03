package net.knightsandkings.knk.core.npc.profile;

import net.knightsandkings.knk.core.npc.NpcStat;
import net.knightsandkings.knk.core.npc.StatBounds;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileTest {

    @Test
    void vanillaCombatProfileCarriesTheReportedTimingsAndVanillaDamage() {
        CombatProfile p = CombatProfile.vanilla("Zombie");
        assertEquals("zombie", p.id());
        assertEquals(20, p.meleeCooldownTicks());
        assertEquals(40, p.rangedCooldownTicks());
        assertEquals(20, p.drawTicks());
        assertTrue(p.meleeDamage().isEmpty(), "damage stays vanilla: the report gives no number");
        assertTrue(p.projectileDamage().isEmpty());
        assertTrue(p.targetOverrides().isEmpty());
        assertEquals(10.0, p.floorOf(NpcStat.MELEE_COOLDOWN).limit());
        assertEquals(20.0, p.floorOf(NpcStat.RANGED_COOLDOWN).limit());
        assertEquals(1.0, p.floorOf(NpcStat.RANGED_DRAW).limit());
        assertEquals(0.0, p.floorOf(NpcStat.MELEE_DAMAGE).limit());
        assertEquals(0.0, p.floorOf(NpcStat.PROJECTILE_DAMAGE).limit());
        assertTrue(p.boundsFor(NpcStat.MELEE_COOLDOWN).ceiling().isUnbounded());
        assertEquals(StatBounds.UNBOUNDED, p.boundsFor(NpcStat.named("other")));
    }

    @Test
    void forTargetMergesOnlyPresentOverrideFieldsAndNormalizesTheKey() {
        CombatProfile p = CombatProfile.vanilla("zombie")
            .withMeleeDamage(OptionalDouble.of(3.0))
            .withTargetOverride(" GATE ", TargetOverride.NONE.withMeleeDamage(12.0));
        assertSame(p, p.forTarget("player"));
        assertSame(p, p.forTarget(null));
        CombatProfile gate = p.forTarget("gate");
        assertEquals(12.0, gate.meleeDamage().getAsDouble());
        assertEquals(20, gate.meleeCooldownTicks());
        assertEquals(40, gate.rangedCooldownTicks());
        assertTrue(gate.targetOverrides().isEmpty(), "a target-resolved profile has no further overrides");
        assertEquals(p.bounds(), gate.bounds());
    }

    @Test
    void emptyTargetOverridesAreDropped() {
        CombatProfile p = CombatProfile.vanilla("zombie").withTargetOverride("gate", TargetOverride.NONE);
        assertTrue(p.targetOverrides().isEmpty());
        assertTrue(TargetOverride.NONE.isEmpty());
        assertFalse(TargetOverride.NONE.withDrawTicks(5).isEmpty());
    }

    @Test
    void combatProfileRejectsBlankIds() {
        assertThrows(IllegalArgumentException.class, () -> CombatProfile.vanilla(" "));
        assertThrows(NullPointerException.class, () -> CombatProfile.vanilla(null));
    }

    @Test
    void speedProfileDefaultsAndBounds() {
        SpeedProfile p = SpeedProfile.of("Spider", 0.3);
        assertEquals("spider", p.id());
        assertEquals(0.3, p.baseSpeed());
        assertEquals(0.4, p.comfortLimit());
        assertEquals(0.5, p.hardCap());
        StatBounds b = p.bounds();
        assertEquals(SpeedProfile.FLOOR_RULE, b.floor().name());
        assertEquals(0.0, b.floor().limit());
        assertEquals(SpeedProfile.HARD_CAP_RULE, b.ceiling().name());
        assertEquals(0.5, b.ceiling().limit());
        assertEquals(SpeedProfile.COMFORT_RULE, b.comfort().orElseThrow().name());
        assertEquals(0.4, b.comfort().orElseThrow().limit());
        assertEquals(1.0, p.factor("road"));
        assertEquals(1.0, p.factor(null));
        assertEquals(0.5, p.withFactor("Water", 0.5).factor("water"));
    }

    @Test
    void speedProfileValidatesItsNumbers() {
        assertThrows(IllegalArgumentException.class, () -> SpeedProfile.of("x", -0.1));
        assertThrows(IllegalArgumentException.class, () -> SpeedProfile.of("x", 0.2).withComfortLimit(0.6));
        assertThrows(IllegalArgumentException.class, () -> SpeedProfile.of("x", 0.2).withHardCap(0.3));
        assertThrows(IllegalArgumentException.class, () -> SpeedProfile.of("x", 0.2).withFactor("w", -1));
        assertEquals(0.6, SpeedProfile.of("x", 0.2).withHardCap(0.6).withComfortLimit(0.6).comfortLimit());
    }

    @Test
    void vanillaProfilesCoverTheReportedTypesWithTheirSpeeds() {
        Map<String, SpeedProfile> speed = VanillaProfiles.speed();
        assertEquals(0.23, speed.get("zombie").baseSpeed());
        assertEquals(0.25, speed.get("skeleton").baseSpeed());
        assertEquals(0.25, speed.get("creeper").baseSpeed());
        assertEquals(0.3, speed.get("spider").baseSpeed());
        assertEquals(0.23, speed.get("default").baseSpeed());
        Map<String, CombatProfile> combat = VanillaProfiles.combat();
        assertEquals(speed.keySet(), combat.keySet());
        combat.values().forEach(p -> assertEquals(CombatProfile.vanilla(p.id()), p));
    }

    @Test
    void registryFallsBackToTheDefaultProfileForUnknownIds() {
        ProfileRegistry r = ProfileRegistry.vanilla();
        assertEquals("spider", r.speed("SPIDER").id());
        assertEquals("default", r.combat("enderman").id());
        assertEquals("default", r.speed(null).id());
        assertTrue(r.findCombat("enderman").isEmpty());
        assertTrue(r.findSpeed("zombie").isPresent());
        assertEquals(5, r.combatIds().size());
    }

    @Test
    void registryAlwaysHasADefaultProfile() {
        ProfileRegistry r = new ProfileRegistry(Map.of("a", CombatProfile.vanilla("a")), Map.of());
        assertEquals("default", r.combat("b").id());
        assertEquals(0.23, r.speed("b").baseSpeed());
        assertEquals("a", r.combat("A").id());
    }
}
