package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.ports.enchantment.EnchantmentExecutor;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantmentCombatListenerTest {

    // KNG-11: MONITOR (was LOWEST), so effects only follow hits that every other listener let through.
    @Test
    void eventHandlerIsConfiguredForMonitorPriorityAndIgnoreCancelled() throws Exception {
        Method handlerMethod = EnchantmentCombatListener.class.getDeclaredMethod(
                "onEntityDamage",
                org.bukkit.event.entity.EntityDamageByEntityEvent.class
        );

        EventHandler eventHandler = handlerMethod.getAnnotation(EventHandler.class);

        assertNotNull(eventHandler);
        assertEquals(EventPriority.MONITOR, eventHandler.priority());
        assertTrue(eventHandler.ignoreCancelled());
    }

    @Test
    void constructorStoresDependenciesAndCreativeFlag() throws Exception {
        EnchantmentRepository repository = new EnchantmentRepository() {
            @Override
            public CompletableFuture<Map<String, Integer>> getEnchantments(List<String> loreLines) {
                return CompletableFuture.completedFuture(Map.of());
            }

            @Override
            public CompletableFuture<Boolean> hasAnyEnchantment(List<String> loreLines) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public CompletableFuture<Boolean> hasEnchantment(List<String> loreLines, String enchantmentId) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public CompletableFuture<List<String>> applyEnchantment(List<String> loreLines, String enchantmentId, Integer level) {
                return CompletableFuture.completedFuture(loreLines);
            }

            @Override
            public CompletableFuture<List<String>> removeEnchantment(List<String> loreLines, String enchantmentId) {
                return CompletableFuture.completedFuture(loreLines);
            }
        };

        EnchantmentExecutor executor = new EnchantmentExecutor() {
            @Override
            public CompletableFuture<Void> executeOnMeleeHit(Map<String, Integer> enchantments, UUID attackerId, UUID targetId, double damageDealt) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Boolean> executeOnInteract(Map<String, Integer> enchantments, UUID playerId) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public CompletableFuture<Void> executeOnBowShoot(Map<String, Integer> enchantments, UUID shooterId, UUID projectileId) {
                return CompletableFuture.completedFuture(null);
            }
        };

        EnchantmentCombatListener listener = new EnchantmentCombatListener(repository, executor, true);

        Field repositoryField = EnchantmentCombatListener.class.getDeclaredField("enchantmentRepository");
        Field executorField = EnchantmentCombatListener.class.getDeclaredField("enchantmentExecutor");
        Field creativeField = EnchantmentCombatListener.class.getDeclaredField("disableForCreative");

        repositoryField.setAccessible(true);
        executorField.setAccessible(true);
        creativeField.setAccessible(true);

        assertEquals(repository, repositoryField.get(listener));
        assertEquals(executor, executorField.get(listener));
        assertEquals(true, creativeField.getBoolean(listener));
    }
}
