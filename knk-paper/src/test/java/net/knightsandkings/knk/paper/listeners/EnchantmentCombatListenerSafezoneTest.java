package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.ports.enchantment.EnchantmentExecutor;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import net.knightsandkings.knk.paper.regions.CombatSafezoneCheck;
import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KNG-11: a hit on a player in a Town/District safezone runs none of the weapon's attack enchantments
 * (poison, wither, freeze, blindness, confusion, strength all go through this one gate).
 */
class EnchantmentCombatListenerSafezoneTest {

    private static final List<String> LORE = List.of("§7Poison II", "§7Wither I");
    private static final Map<String, Integer> ENCHANTMENTS = Map.of("poison", 2, "wither", 1);

    private final EnchantmentRepository repository = mock(EnchantmentRepository.class);
    private final EnchantmentExecutor executor = mock(EnchantmentExecutor.class);
    private final Player attacker = mock(Player.class);
    private final Player victim = mock(Player.class);
    private final UUID attackerId = UUID.randomUUID();
    private final UUID victimId = UUID.randomUUID();
    private final List<LivingEntity> checkedVictims = new ArrayList<>();

    EnchantmentCombatListenerSafezoneTest() {
        when(attacker.getUniqueId()).thenReturn(attackerId);
        when(victim.getUniqueId()).thenReturn(victimId);
        when(attacker.getGameMode()).thenReturn(GameMode.SURVIVAL);

        ItemStack weapon = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        when(meta.hasLore()).thenReturn(true);
        when(meta.getLore()).thenReturn(LORE);
        when(weapon.getItemMeta()).thenReturn(meta);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getItemInMainHand()).thenReturn(weapon);
        when(attacker.getInventory()).thenReturn(inventory);

        when(repository.getEnchantments(anyList())).thenReturn(CompletableFuture.completedFuture(ENCHANTMENTS));
        when(executor.executeOnMeleeHit(any(), any(), any(), anyDouble())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private void hit(boolean protectedVictim) {
        CombatSafezoneCheck safezones = (a, v) -> {
            assertEquals(attacker, a);
            checkedVictims.add(v);
            return protectedVictim;
        };
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamage()).thenReturn(6.0);

        new EnchantmentCombatListener(repository, executor, true, safezones).onEntityDamage(event);
    }

    @Test
    void aHitOnAPlayerInASafezoneAppliesNoEnchantments() {
        hit(true);

        assertEquals(List.of(victim), checkedVictims);
        verify(executor, never()).executeOnMeleeHit(any(), any(), any(), anyDouble());
    }

    @Test
    void aHitOutsideASafezoneAppliesTheWeaponsEnchantments() {
        hit(false);

        verify(executor).executeOnMeleeHit(eq(ENCHANTMENTS), eq(attackerId), eq(victimId), eq(6.0));
    }

    @Test
    void theOldConstructorHasNoSafezones() {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getEntity()).thenReturn(victim);

        new EnchantmentCombatListener(repository, executor, true).onEntityDamage(event);

        verify(executor).executeOnMeleeHit(any(), any(), any(), anyDouble());
    }
}
