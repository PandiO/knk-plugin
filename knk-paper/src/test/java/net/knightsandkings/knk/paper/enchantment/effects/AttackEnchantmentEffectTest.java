package net.knightsandkings.knk.paper.enchantment.effects;

import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuning;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackEnchantmentEffectTest {

    @Test
    void probabilityBasedEffectTriggersWhenRollIsBelowChance() {
        AtomicBoolean called = new AtomicBoolean(false);
        AttackEnchantmentEffect effect = new TestAttackEffect("poison", 0.15d, () -> 0.10d, called);

        boolean triggered = effect.tryExecute(null, null, null, 1);

        assertTrue(triggered);
        assertTrue(called.get());
    }

    @Test
    void probabilityBasedEffectDoesNotTriggerWhenRollIsAboveChance() {
        AtomicBoolean called = new AtomicBoolean(false);
        AttackEnchantmentEffect effect = new TestAttackEffect("poison", 0.15d, () -> 0.30d, called);

        boolean triggered = effect.tryExecute(null, null, null, 1);

        assertFalse(triggered);
        assertFalse(called.get());
    }

    @Test
    void alwaysTriggerEffectExecutesWithoutProbability() {
        AtomicBoolean called = new AtomicBoolean(false);
        AttackEnchantmentEffect effect = new TestAttackEffect("blindness", null, () -> 0.99d, called);

        boolean triggered = effect.tryExecute(null, null, null, 3);

        assertTrue(triggered);
        assertTrue(called.get());
    }

    @Test
    void chanceGrowsPerLevelAsTuned() {
        AtomicBoolean called = new AtomicBoolean(false);
        // 15 % at level I, +15 per level: 45 % at level III, so a roll of 0.40 goes off there but not at level II.
        AttackEnchantmentEffect effect = new TestAttackEffect("freeze", 0.15d, () -> 0.40d, called);

        assertFalse(effect.tryExecute(null, null, null, 2));
        assertTrue(effect.tryExecute(null, null, null, 3));
    }

    @Test
    void readsTheTuningAtEveryHit() {
        AtomicBoolean called = new AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<EnchantmentTuning> current =
                new java.util.concurrent.atomic.AtomicReference<>(new EnchantmentTuning(0.0d, 0.0d, false, 0L, 60, 1.0d));
        AttackEnchantmentEffect effect = new AttackEnchantmentEffect("freeze", TestAttackEffect.testPlugin(), () -> 0.5d, current::get) {
            @Override
            protected void applyEffect(ItemStack weapon, Player attacker, LivingEntity target, int level) {
                called.set(true);
            }
        };

        assertFalse(effect.tryExecute(null, null, null, 1));
        current.set(new EnchantmentTuning(0.6d, 0.0d, false, 0L, 60, 1.0d));
        assertTrue(effect.tryExecute(null, null, null, 1));
        assertTrue(called.get());
    }

    private static final class TestAttackEffect extends AttackEnchantmentEffect {
        private final AtomicBoolean called;

        private TestAttackEffect(
                String enchantmentId,
                Double triggerProbability,
                java.util.function.DoubleSupplier rollSupplier,
                AtomicBoolean called
        ) {
            super(enchantmentId, testPlugin(), rollSupplier, () -> triggerProbability == null
                    ? new EnchantmentTuning(1.0d, 0.0d, false, 0L, 60, 1.0d)
                    : new EnchantmentTuning(triggerProbability, triggerProbability, false, 0L, 60, 1.0d));
            this.called = called;
        }

        @Override
        protected void applyEffect(ItemStack weapon, Player attacker, LivingEntity target, int level) {
            called.set(true);
        }

        private static Plugin testPlugin() {
            return (Plugin) Proxy.newProxyInstance(
                    Plugin.class.getClassLoader(),
                    new Class[]{Plugin.class},
                    (proxy, method, args) -> null
            );
        }
    }
}
