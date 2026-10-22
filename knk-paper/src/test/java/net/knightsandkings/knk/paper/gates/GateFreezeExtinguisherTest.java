package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * GateFreezeExtinguisher: a defender hitting a burning door block with a freeze weapon puts its fire out.
 */
class GateFreezeExtinguisherTest {

    private GateFireSystem fireSystem;
    private EnchantmentRepository enchantments;
    private BiPredicate<Player, CachedGateDoor> defenders;
    private GateFreezeExtinguisher extinguisher;
    private CachedGateDoor gate;
    private Block block;
    private Player player;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        fireSystem = mock(GateFireSystem.class);
        enchantments = mock(EnchantmentRepository.class);
        defenders = mock(BiPredicate.class);
        extinguisher = new GateFreezeExtinguisher(fireSystem, enchantments, defenders);

        gate = new CachedGateDoor(
            1, 1, "TestGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(100, 64, 100), 5, 5, 3,
            500.0, 500.0, true, false, false, 90, "north"
        );
        gate.getBurningBlocks().put(new Vector(100, 64, 100), System.currentTimeMillis() + 5000L);
        block = mock(Block.class);

        player = mock(Player.class);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(defenders.test(player, gate)).thenReturn(true);
        holding(List.of("§7Freeze I"), Map.of("freeze", 1));
        when(fireSystem.extinguishBlock(gate, block)).thenReturn(true);
    }

    private void holding(List<String> lore, Map<String, Integer> enchanted) {
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack weapon = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        when(weapon.getItemMeta()).thenReturn(meta);
        when(meta.hasLore()).thenReturn(lore != null);
        when(meta.getLore()).thenReturn(lore);
        when(inventory.getItemInMainHand()).thenReturn(weapon);
        when(player.getInventory()).thenReturn(inventory);
        when(enchantments.getEnchantments(any())).thenReturn(CompletableFuture.completedFuture(enchanted));
    }

    @Test
    void aDefenderWithAFreezeWeaponPutsTheHitBlocksFireOut() {
        assertTrue(extinguisher.onHit(player, gate, block));

        verify(fireSystem).extinguishBlock(gate, block);
    }

    @Test
    void anAttackerCannotPutOutTheFire() {
        when(defenders.test(player, gate)).thenReturn(false);

        assertFalse(extinguisher.onHit(player, gate, block));

        verifyNoInteractions(fireSystem);
    }

    @Test
    void aWeaponWithoutFreezeDoesNothing() {
        holding(List.of("§7Poison I"), Map.of("poison", 1));

        assertFalse(extinguisher.onHit(player, gate, block));

        verifyNoInteractions(fireSystem);
    }

    @Test
    void aFreezeOfLevelZeroDoesNothing() {
        holding(List.of("§7Freeze"), Map.of("freeze", 0));

        assertFalse(extinguisher.onHit(player, gate, block));
    }

    @Test
    void aWeaponWithoutLoreDoesNothing() {
        holding(null, Map.of());

        assertFalse(extinguisher.onHit(player, gate, block));

        verifyNoInteractions(enchantments);
        verifyNoInteractions(fireSystem);
    }

    @Test
    void anEmptyHandDoesNothing() {
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack air = mock(ItemStack.class);
        when(air.isEmpty()).thenReturn(true);
        when(inventory.getItemInMainHand()).thenReturn(air);
        when(player.getInventory()).thenReturn(inventory);

        assertFalse(extinguisher.onHit(player, gate, block));

        verifyNoInteractions(fireSystem);
    }

    @Test
    void aGateThatIsNotBurningNeedsNoExtinguishing() {
        gate.getBurningBlocks().clear();

        assertFalse(extinguisher.onHit(player, gate, block));

        verifyNoInteractions(fireSystem);
        verifyNoInteractions(defenders);
    }

    @Test
    void onlySurvivalPlayersPutOutFire() {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);

        assertFalse(extinguisher.onHit(player, gate, block));

        verifyNoInteractions(fireSystem);
    }

    @Test
    void aFailingEnchantmentLookupLeavesTheFireBurning() {
        when(enchantments.getEnchantments(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        assertFalse(extinguisher.onHit(player, gate, block));

        verifyNoInteractions(fireSystem);
    }

    @Test
    void whetherTheBlockWasBurningDecidesTheResult() {
        when(fireSystem.extinguishBlock(gate, block)).thenReturn(false); // another block of the gate burns, not this one

        assertFalse(extinguisher.onHit(player, gate, block));
    }

    @Test
    void nullsAreIgnored() {
        assertFalse(extinguisher.onHit(null, gate, block));
        assertFalse(extinguisher.onHit(player, null, block));
        assertFalse(extinguisher.onHit(player, gate, null));
    }
}
