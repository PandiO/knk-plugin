package net.knightsandkings.knk.core.npc;

import net.knightsandkings.knk.core.npc.cooldown.CooldownJitter;
import net.knightsandkings.knk.core.npc.modifier.StatModifier;
import net.knightsandkings.knk.core.npc.profile.ProfileOverrides;
import net.knightsandkings.knk.core.npc.profile.ProfileRegistry;
import net.knightsandkings.knk.core.npc.profile.TargetTypes;
import net.knightsandkings.knk.core.npc.resolve.ResolvedCombat;
import net.knightsandkings.knk.core.npc.resolve.ResolvedSpeed;
import net.knightsandkings.knk.core.npc.resolve.VanillaBases;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End to end: a Survival-like consumer feeds round and per-player multipliers; an adapter reads the result. */
class NpcStatPipelineTest {
    private final NpcStatPipeline pipeline = NpcStatPipeline.vanilla();
    private final UUID zombie = UUID.randomUUID();
    private final UUID skeleton = UUID.randomUUID();

    @Test
    void roundOneIsVanilla() {
        ResolvedCombat c = pipeline.resolveCombat(zombie, "zombie", TargetTypes.PLAYER, VanillaBases.melee(3.0));
        assertEquals(20, c.meleeCooldownTicks());
        assertEquals(3.0, c.meleeDamageValue());
        assertFalse(c.wasClamped());
        ResolvedSpeed s = pipeline.resolveSpeed(zombie, "zombie");
        assertEquals(0.23, s.value());
        assertFalse(s.aboveComfort());
        assertEquals(0, pipeline.revision(zombie));
        assertEquals(0, pipeline.modifiers().size(), "resolving does not allocate actor state");
    }

    @Test
    void survivalRoundAndPerPlayerModifiersCombineAndClampWithReports() {
        // round 12 of a hybrid curve, say ×1.6 on damage and speed, cooldown ×0.45; two players ×1.1
        List<StatModifier> round = List.of(
            StatModifier.multiply("survival:round", NpcStat.MELEE_DAMAGE, 1.6),
            StatModifier.multiply("survival:round", NpcStat.PROJECTILE_DAMAGE, 1.6),
            StatModifier.multiply("survival:round", NpcStat.MOVEMENT_SPEED, 1.6),
            StatModifier.multiply("survival:round", NpcStat.MELEE_COOLDOWN, 0.45),
            StatModifier.multiply("survival:round", NpcStat.RANGED_COOLDOWN, 0.45));
        List<StatModifier> perPlayer = List.of(
            StatModifier.multiply("survival:per-player", NpcStat.MELEE_DAMAGE, 1.1),
            StatModifier.multiply("survival:per-player", NpcStat.MOVEMENT_SPEED, 1.1));
        pipeline.modifiers().putAll(List.of(zombie, skeleton), round);
        pipeline.modifiers().putAll(List.of(zombie, skeleton), perPlayer);
        assertEquals(7, pipeline.revision(zombie));

        ResolvedCombat z = pipeline.resolveCombat(zombie, "zombie", TargetTypes.PLAYER, VanillaBases.melee(3.0));
        assertEquals(10, z.meleeCooldownTicks(), "20 × 0.45 = 9 → floor 10");
        assertEquals(3.0 * 1.6 * 1.1, z.meleeDamageValue(), 1e-12);
        assertTrue(z.wasClamped());
        assertEquals(List.of("melee-cooldown-floor", "ranged-cooldown-floor"),
            z.reports().stream().map(r -> r.bound().name()).toList());

        ResolvedCombat s = pipeline.resolveCombat(skeleton, "skeleton", TargetTypes.OBJECTIVE, VanillaBases.projectile(4.0));
        assertEquals(20, s.rangedCooldownTicks(), "40 × 0.45 = 18 → floor 20");
        assertEquals(20, s.drawTicks(), "draw unmodified, equal to the cooldown: allowed");
        assertEquals(6.4, s.projectileDamageValue(), 1e-12);

        ResolvedSpeed zs = pipeline.resolveSpeed(zombie, "zombie");
        assertEquals(0.23 * 1.6 * 1.1, zs.value(), 1e-12); // 0.4048: above comfort, under the cap
        assertTrue(zs.aboveComfort());
        assertFalse(zs.wasClamped());
        ResolvedSpeed ss = pipeline.resolveSpeed(skeleton, "spider");
        assertEquals(0.5, ss.value(), "0.3 × 1.6 = 0.48 → ... with per-player 0.528 → hard cap 0.5");
        assertTrue(ss.wasClamped());
    }

    @Test
    void removingASourceRestoresTheOtherSourcesValues() {
        pipeline.modifiers().forActor(zombie).put(StatModifier.multiply("survival:round", NpcStat.MOVEMENT_SPEED, 1.5));
        pipeline.modifiers().forActor(zombie).put(StatModifier.multiply("effect:slowness", NpcStat.MOVEMENT_SPEED, 0.5));
        assertEquals(0.23 * 0.75, pipeline.resolveSpeed(zombie, "zombie").value(), 1e-12);
        assertEquals(1, pipeline.modifiers().removeSource("effect:slowness"));
        assertEquals(0.23 * 1.5, pipeline.resolveSpeed(zombie, "zombie").value(), 1e-12);
        assertEquals(3, pipeline.revision(zombie));
        pipeline.forget(zombie);
        assertEquals(0.23, pipeline.resolveSpeed(zombie, "zombie").value());
        assertEquals(0, pipeline.revision(zombie));
    }

    @Test
    void genericNamedStatUsesTheSamePipeline() {
        NpcStat gateDamage = NpcStat.named("gate-damage");
        pipeline.modifiers().forActor(zombie).put(StatModifier.multiply("survival:round", gateDamage, 2.5));
        ResolvedStat r = pipeline.resolveNamed(zombie, gateDamage, 4.0, StatBounds.of("gate-damage-floor", 0, "gate-damage-cap", 8));
        assertEquals(8.0, r.value());
        assertEquals(10.0, r.unclamped());
        assertEquals("gate-damage-cap", r.reports().get(0).bound().name());
        assertEquals(NpcStat.Unit.CUSTOM, gateDamage.unit());
    }

    @Test
    void cooldownsPaceAttacksWithTheResolvedTicks() {
        pipeline.modifiers().forActor(zombie).put(StatModifier.multiply("survival:round", NpcStat.MELEE_COOLDOWN, 0.75));
        ResolvedCombat c = pipeline.resolveCombat(zombie, "zombie", TargetTypes.PLAYER, VanillaBases.melee(3.0));
        assertEquals(15, c.meleeCooldownTicks());
        assertTrue(pipeline.tryMelee(zombie, 1000, c));
        assertFalse(pipeline.tryMelee(zombie, 1014, c));
        assertTrue(pipeline.tryMelee(zombie, 1015, c));
        assertTrue(pipeline.tryShot(zombie, 1015, c), "the ranged channel is separate");
        assertFalse(pipeline.tryShot(zombie, 1054, c));
        assertTrue(pipeline.tryShot(zombie, 1055, c));
    }

    @Test
    void reloadingProfilesKeepsActorState() {
        pipeline.modifiers().forActor(zombie).put(StatModifier.multiply("survival:round", NpcStat.MELEE_COOLDOWN, 0.5));
        pipeline.tryMelee(zombie, 0, pipeline.resolveCombat(zombie, "zombie", "", VanillaBases.ZERO));
        ProfileRegistry reloaded = ProfileRegistry.vanilla().withOverrides(new ProfileOverrides(Map.of(
            "zombie", Map.of("combat.melee-cooldown-ticks", 30, "combat.bounds.melee-cooldown-ticks.floor", 12)))).registry();
        pipeline.reloadProfiles(reloaded);
        ResolvedCombat c = pipeline.resolveCombat(zombie, "zombie", "", VanillaBases.ZERO);
        assertEquals(15, c.meleeCooldownTicks(), "30 × 0.5 from the kept modifier, above the new floor 12");
        assertFalse(c.wasClamped());
        assertFalse(pipeline.cooldowns().isReady(zombie, "melee", 5), "cooldown state survived the reload");
        assertEquals(1, pipeline.revision(zombie));
    }

    @Test
    void jitterAndRandomAreInjectable() {
        NpcStatPipeline jittered = new NpcStatPipeline(ProfileRegistry.vanilla(), CooldownJitter.on(0.5), new Random(1));
        ResolvedCombat c = jittered.resolveCombat(zombie, "zombie", "", VanillaBases.ZERO);
        assertTrue(jittered.tryMelee(zombie, 0, c));
        long readyAt = jittered.cooldowns().readyAt(zombie, "melee").getAsLong();
        assertTrue(readyAt >= 20 && readyAt <= 30, "readyAt " + readyAt);
    }
}
