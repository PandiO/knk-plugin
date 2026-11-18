package net.knightsandkings.knk.paper.enchantment.effects.impl;

import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningOverride;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningTable;
import net.knightsandkings.knk.paper.enchantment.GateAreaDamage;
import net.knightsandkings.knk.paper.regions.CombatSafezoneCheck;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PandiO/knk-workspace#223: Chaos and Flash Chaos take their area damage, gate damage and radii from the combat values,
 * per activation, with the old hard-coded numbers as the defaults (40 / 60 within 3 blocks, knockback within 5).
 */
class ChaosAreaDamageTest {
    // Kept in a field: a Location only holds its world weakly (see knk-plugin CLAUDE.md).
    private World world;
    private Player user;
    private final List<double[]> searchedBoxes = new ArrayList<>();
    private final List<Entity> nearby = new ArrayList<>();
    private final List<Double> gateDamage = new ArrayList<>();
    private final GateAreaDamage gates = (source, center, damage) -> gateDamage.add(damage);

    @BeforeEach
    void setUp() {
        world = mock(World.class);
        user = mock(Player.class);
        when(user.getUniqueId()).thenReturn(UUID.randomUUID());
        when(user.getLocation()).thenAnswer(invocation -> new Location(world, 0.5d, 64.0d, 0.5d));
        when(user.getNearbyEntities(anyDouble(), anyDouble(), anyDouble())).thenAnswer(invocation -> {
            searchedBoxes.add(new double[] {invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)});
            return new ArrayList<>(nearby);
        });
    }

    @AfterEach
    void tearDown() {
        EnchantmentTuningTable.getInstance().clearOverrides();
    }

    private LivingEntity entityAt(double distance) {
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getLocation()).thenAnswer(invocation -> new Location(world, 0.5d + distance, 64.0d, 0.5d));
        nearby.add(entity);
        return entity;
    }

    private static void override(String key, Double levelMultiplier, Double damage, Double gate, Double damageRadius,
                                 Double effectRadius) {
        EnchantmentTuningTable.getInstance().replaceOverrides(List.of(new EnchantmentTuningOverride(key, null, null,
                null, null, null, levelMultiplier, null, null, damage, gate, damageRadius, effectRadius)));
    }

    @Test
    void chaosDefaultsAreTheOldHardCodedValues() {
        RecordingChaos chaos = new RecordingChaos();
        LivingEntity close = entityAt(2.0d);
        LivingEntity outside = entityAt(4.0d);

        chaos.blast(user, 1);

        assertEquals(Map.of(close, 40.0d), chaos.damaged);
        assertEquals(List.of(close, outside), chaos.knockedBack);
        assertEquals(5.0d, searchedBoxes.get(0)[0]);
        assertEquals(List.of(40.0d), gateDamage);
    }

    @Test
    void chaosUsesTheAdminsDamageAndRadius_TheIssuesLiveCheck() {
        override("knk:chaos", null, 6d, null, 2d, null);
        RecordingChaos chaos = new RecordingChaos();
        LivingEntity twoBlocks = entityAt(2.0d);
        LivingEntity threeBlocks = entityAt(3.0d);

        chaos.blast(user, 1);

        assertEquals(Map.of(twoBlocks, 6.0d), chaos.damaged);
        assertEquals(List.of(twoBlocks, threeBlocks), chaos.knockedBack);
        assertEquals(List.of(40.0d), gateDamage, "gate damage is its own value");
    }

    @Test
    void chaosDamageGrowsWithTheLevelMultiplier_AndReachFollowsTheLargerRadius() {
        override("chaos", 0.5d, 10d, 100d, 7d, 4d);
        RecordingChaos chaos = new RecordingChaos();
        LivingEntity sixBlocks = entityAt(6.0d);

        chaos.blast(user, 3);

        assertEquals(Map.of(sixBlocks, 20.0d), chaos.damaged);
        assertEquals(7.0d, searchedBoxes.get(0)[0]);
        assertEquals(List.of(200.0d), gateDamage);
    }

    @Test
    void chaosWithZeroDamageOnlyKnocksBack() {
        override("chaos", null, 0d, 0d, null, null);
        RecordingChaos chaos = new RecordingChaos();
        LivingEntity close = entityAt(1.0d);

        chaos.blast(user, 1);

        assertEquals(Map.of(), chaos.damaged);
        assertEquals(List.of(close), chaos.knockedBack);
        assertEquals(List.of(0.0d), gateDamage);
    }

    @Test
    void flashChaosDefaultsAreTheOldHardCodedValues() {
        RecordingFlashChaos flash = new RecordingFlashChaos();
        LivingEntity close = entityAt(3.0d);
        LivingEntity outside = entityAt(4.5d);

        flash.blast(user, 1);

        assertEquals(Map.of(close, 60.0d), flash.damaged);
        assertEquals(List.of(close, outside), flash.knockedBack);
        assertEquals(5.0d, searchedBoxes.get(0)[0]);
        assertEquals(List.of(60.0d), gateDamage);
    }

    @Test
    void flashChaosUsesTheAdminsValues() {
        override("flash_chaos", null, 8d, 25d, 1.5d, 2.5d);
        RecordingFlashChaos flash = new RecordingFlashChaos();
        LivingEntity close = entityAt(1.0d);
        LivingEntity notDamaged = entityAt(2.0d);

        flash.blast(user, 1);

        assertEquals(Map.of(close, 8.0d), flash.damaged);
        assertEquals(List.of(close, notDamaged), flash.knockedBack);
        assertEquals(2.5d, searchedBoxes.get(0)[0]);
        assertEquals(List.of(25.0d), gateDamage);
    }

    /** Chaos without the server: records the damage it would deal and who it knocks back. */
    private final class RecordingChaos extends ChaosEffect {
        final Map<LivingEntity, Double> damaged = new LinkedHashMap<>();
        final List<LivingEntity> knockedBack = new ArrayList<>();

        RecordingChaos() {
            super(mock(Plugin.class), CombatSafezoneCheck.NONE, () -> gates);
        }

        @Override
        protected boolean callDamageEventAndApply(Player attacker, LivingEntity target, double damage) {
            damaged.put(target, damage);
            return true;
        }

        @Override
        protected void applyKnockback(Player attacker, LivingEntity target) {
            knockedBack.add(target);
        }
    }

    /** Flash Chaos without the server: records the damage it would deal and who it knocks back. */
    private final class RecordingFlashChaos extends FlashChaosEffect {
        final Map<LivingEntity, Double> damaged = new LinkedHashMap<>();
        final List<LivingEntity> knockedBack = new ArrayList<>();

        RecordingFlashChaos() {
            super(mock(Plugin.class), CombatSafezoneCheck.NONE, () -> gates);
        }

        @Override
        protected boolean callDamageEventAndApply(Player attacker, LivingEntity target, double damage) {
            damaged.put(target, damage);
            return true;
        }

        @Override
        protected void applyKnockback(Player attacker, LivingEntity target) {
            knockedBack.add(target);
        }
    }
}
