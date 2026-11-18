package net.knightsandkings.knk.paper.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.ports.enchantment.CooldownManager;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentExecutor;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import net.knightsandkings.knk.paper.commands.support.TestCommandPermissions;
import net.knightsandkings.knk.paper.enchantment.EnchantmentPermissions;

/**
 * PandiO/knk-workspace#193: a right-click ability runs for a player the permission check allows (a rank
 * grant, the group wildcard, op) and not for one it refuses.
 */
class EnchantmentInteractListenerPermissionTest {

    private final EnchantmentRepository repository = mock(EnchantmentRepository.class);
    private final EnchantmentExecutor executor = mock(EnchantmentExecutor.class);
    private final CooldownManager cooldowns = mock(CooldownManager.class);

    EnchantmentInteractListenerPermissionTest() {
        when(repository.getEnchantments(any())).thenReturn(CompletableFuture.completedFuture(Map.of("chaos", 1)));
        when(cooldowns.getRemainingCooldown(any(), anyString())).thenReturn(CompletableFuture.completedFuture(0L));
        when(executor.executeOnInteract(anyMap(), any())).thenReturn(CompletableFuture.completedFuture(true));
    }

    /** The in-house model answers yes for exactly {@code granted} (as a cached rank grant would). */
    private EnchantmentInteractListener listener(Set<String> granted) {
        EnchantmentPermissions enchantmentPermissions = granted == null
            ? EnchantmentPermissions.bukkitOnly()
            : new EnchantmentPermissions(TestCommandPermissions.inHouse(granted::contains));
        return new EnchantmentInteractListener(repository, executor, cooldowns, false, "", enchantmentPermissions);
    }

    private static PlayerInteractEvent rightClick(Player player) {
        ItemMeta meta = mock(ItemMeta.class);
        when(meta.hasLore()).thenReturn(true);
        when(meta.getLore()).thenReturn(List.of("Chaos I"));
        ItemStack item = mock(ItemStack.class);
        Material rod = mock(Material.class); // Material.isAir() needs a server's registries
        when(rod.isAir()).thenReturn(false);
        when(item.getType()).thenReturn(rod);
        when(item.getItemMeta()).thenReturn(meta);

        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getPlayer()).thenReturn(player);
        when(event.getItem()).thenReturn(item);
        return event;
    }

    private static Player player(boolean op) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.isOp()).thenReturn(op);
        when(player.hasPermission(anyString())).thenReturn(op); // the nodes are default: op
        return player;
    }

    @Test
    void aNonOpWithTheRankGrantUsesTheAbility() {
        Player player = player(false);

        listener(Set.of("customenchantments.chaos")).onPlayerInteract(rightClick(player));

        UUID id = player.getUniqueId();
        verify(executor).executeOnInteract(eq(Map.of("chaos", 1)), eq(id));
    }

    @Test
    void aNonOpWithTheGroupNodeUsesTheAbility() {
        Player player = player(false);

        listener(Set.of("customenchantments.use.chaos")).onPlayerInteract(rightClick(player));

        UUID id = player.getUniqueId();
        verify(executor).executeOnInteract(eq(Map.of("chaos", 1)), eq(id));
    }

    @Test
    void aNonOpWithoutAGrantCannot() {
        listener(Set.of("customenchantments.invisibility")).onPlayerInteract(rightClick(player(false)));

        verify(executor, never()).executeOnInteract(anyMap(), any());
    }

    @Test
    void anOpStillCan_alsoWithoutTheApi() {
        Player op = player(true);

        listener(null).onPlayerInteract(rightClick(op));

        UUID id = op.getUniqueId();
        verify(executor).executeOnInteract(eq(Map.of("chaos", 1)), eq(id));
    }

    @Test
    void theNodeNamesMatchTheGrantForm() {
        assertTrue(Set.of("customenchantments.chaos").contains(EnchantmentPermissions.node("chaos")));
        assertFalse(EnchantmentPermissions.USE_ALL.startsWith("customenchantments.command"));
    }
}
