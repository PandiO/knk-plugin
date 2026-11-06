package net.knightsandkings.knk.paper.npc;

import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.entity.ai.MobGoals;
import com.destroystokyo.paper.entity.ai.VanillaGoal;
import net.knightsandkings.knk.core.npc.NpcStat;
import net.knightsandkings.knk.core.npc.NpcStatPipeline;
import net.knightsandkings.knk.core.npc.modifier.StatModifier;
import net.knightsandkings.knk.core.npc.profile.TargetTypes;
import net.knightsandkings.knk.core.npc.resolve.ResolvedCombat;
import net.knightsandkings.knk.core.npc.resolve.ResolvedSpeed;
import net.knightsandkings.knk.core.npc.resolve.VanillaBases;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Zombie;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NPC platform §5b adapters: adopt strips vanilla goals, applies speed as a modifier, re-applies on revision change, reports clamps once. */
class NpcPlatformTest {

    private final Plugin plugin = mock(Plugin.class);
    private final Logger logger = Logger.getLogger("NpcPlatformTest");
    private final List<String> logged = new java.util.ArrayList<>();
    private final FakeMobAttributes attributes = new FakeMobAttributes();
    private final NpcStatPipeline pipeline = NpcStatPipeline.vanilla();
    private final MobGoals goals = mock(MobGoals.class);
    private long tick;
    private final NpcPlatform platform;
    private final Zombie zombie = mock(Zombie.class);
    private final UUID id = UUID.randomUUID();

    NpcPlatformTest() {
        when(plugin.getName()).thenReturn("knk");
        when(plugin.namespace()).thenReturn("knk");
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        when(plugin.getLogger()).thenReturn(logger);
        platform = new NpcPlatform(plugin, pipeline, attributes, goals, () -> tick, 96);
        when(zombie.getUniqueId()).thenReturn(id);
        when(zombie.getType()).thenReturn(EntityType.ZOMBIE);
        attributes.base.put(NpcAttribute.MOVEMENT_SPEED, 0.23);
        attributes.base.put(NpcAttribute.ATTACK_DAMAGE, 3.0);
        attributes.base.put(NpcAttribute.FOLLOW_RANGE, 35.0);
    }

    @Test
    void adoptStripsVanillaMoveAndTargetGoalsAndInstallsOurs() {
        platform.adopt(zombie, null);
        verify(goals).removeAllGoals(zombie, GoalType.MOVE);
        verify(goals).removeAllGoals(zombie, GoalType.TARGET);
        verify(goals).addGoal(eq(zombie), eq(1), any(NpcMeleeGoal.class));
        assertTrue(platform.isAdopted(id));
        assertEquals("zombie", platform.stateOf(id).profileId());
        assertEquals(96 - 35, attributes.modifiers.get(id + ":" + NpcAttribute.FOLLOW_RANGE + ":knk:npc_follow_range"), 1e-9);
        assertFalse(attributes.modifiers.containsKey(id + ":" + NpcAttribute.MOVEMENT_SPEED + ":knk:npc_speed"), "vanilla speed: no delta");
    }

    @Test
    void speedFollowsTheModifierRevisionAndClampsAreReportedOnce() {
        NpcPlatform.Adopted state = platform.adopt(zombie, "zombie");
        pipeline.modifiers().forActor(id).put(StatModifier.multiply("survival:round", NpcStat.MOVEMENT_SPEED, 1.5));
        ResolvedSpeed speed = platform.applySpeed(zombie, state, false);
        assertNotNull(speed);
        assertEquals(0.23 * 1.5, speed.value(), 1e-9);
        assertEquals(0.23 * 1.5 - 0.23, attributes.modifiers.get(id + ":" + NpcAttribute.MOVEMENT_SPEED + ":knk:npc_speed"), 1e-9);
        assertNull(platform.applySpeed(zombie, state, false), "same revision: nothing to do");

        pipeline.modifiers().forActor(id).put(StatModifier.multiply("survival:round", NpcStat.MOVEMENT_SPEED, 10));
        ResolvedSpeed capped = platform.applySpeed(zombie, state, false);
        assertEquals(0.5, capped.value(), 1e-9, "hard cap 0.5");
        assertTrue(capped.wasClamped());
        long clampLogs = logged.stream().filter(m -> m.contains("speed-hard-cap")).count();
        assertEquals(1, clampLogs);
        platform.refresh(zombie);
        assertEquals(1, logged.stream().filter(m -> m.contains("speed-hard-cap")).count(), "reported once per revision");
    }

    @Test
    void meleeRunsOnThePipelineCooldownWithScaledDamage() {
        platform.adopt(zombie, "zombie");
        pipeline.modifiers().forActor(id).putAll(List.of(
            StatModifier.multiply("survival:round", NpcStat.MELEE_DAMAGE, 2.0),
            StatModifier.multiply("survival:round", NpcStat.MELEE_COOLDOWN, 0.1)));
        ResolvedCombat combat = platform.resolveCombat(zombie, TargetTypes.PLAYER, VanillaBases.melee(platform.vanillaMeleeDamage(zombie)));
        assertEquals(6.0, combat.meleeDamageValue(), 1e-9);
        assertEquals(10, combat.meleeCooldownTicks(), "20 × 0.1 = 2 → floored at 10 ticks");
        tick = 100;
        assertTrue(platform.tryMelee(zombie, combat));
        tick = 105;
        assertFalse(platform.tryMelee(zombie, combat));
        tick = 110;
        assertTrue(platform.tryMelee(zombie, combat));
        platform.applyMeleeDamage(zombie, combat);
        assertEquals(3.0, attributes.modifiers.get(id + ":" + NpcAttribute.ATTACK_DAMAGE + ":knk:npc_melee_damage"), 1e-9);
        platform.forget(id);
        assertFalse(platform.isAdopted(id));
        assertEquals(0, pipeline.revision(id));
    }

    @Test
    void vanillaGoalsThatCameBackAreStrippedAgain() {
        platform.adopt(zombie, "zombie");
        when(goals.hasGoal(zombie, NpcMeleeGoal.KEY)).thenReturn(true);
        platform.installGoals(zombie);
        verify(goals, times(1)).removeAllGoals(zombie, GoalType.MOVE);
        when(goals.hasGoal(zombie, VanillaGoal.MELEE_ATTACK)).thenReturn(true);
        platform.installGoals(zombie);
        verify(goals, times(2)).removeAllGoals(zombie, GoalType.MOVE);
        verify(goals, never()).addGoal(eq(zombie), eq(0), any(NpcBowGoal.class));
    }
}
