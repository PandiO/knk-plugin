package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningOverride;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentTuningTable;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.ports.enchantment.CooldownManager;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import net.knightsandkings.knk.paper.events.GateDoorDamageEvent;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * GateCleaving: the Cleaving enchantment multiplies melee gate hits and adds a charged right-click strike with
 * a per-level cooldown (siege playtest 2026-10-24).
 */
class GateCleavingTest {

    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    private EnchantmentRepository enchantments;
    private CooldownManager cooldowns;
    private GateDoorHitService hitService;
    private GateCleaving.Effects effects;
    private GateCleaving cleaving;
    private CachedGateDoor gate;
    private Block block;
    private Player player;

    @BeforeEach
    void setUp() {
        enchantments = mock(EnchantmentRepository.class);
        cooldowns = mock(CooldownManager.class);
        hitService = mock(GateDoorHitService.class);
        effects = mock(GateCleaving.Effects.class);
        EnchantmentTuningTable.getInstance().clearOverrides();
        cleaving = new GateCleaving(enchantments, cooldowns, hitService);
        cleaving.setEffects(effects);

        gate = new CachedGateDoor(
            1, 1, "TestGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(100, 64, 100), 5, 5, 3,
            500.0, 500.0, true, false, false, 90, "north"
        );
        block = mock(Block.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(cooldowns.getRemainingCooldown(any(), anyString())).thenReturn(CompletableFuture.completedFuture(0L));
        when(cooldowns.applyCooldown(any(), anyString(), anyLong())).thenReturn(CompletableFuture.completedFuture(null));
        holding(List.of("§7Cleaving II"), Map.of("cleaving", 2));
    }

    private void holding(List<String> lore, Map<String, Integer> enchanted) {
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack weapon = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        when(weapon.getItemMeta()).thenReturn(meta);
        when(meta.hasLore()).thenReturn(lore != null && !lore.isEmpty());
        when(meta.getLore()).thenReturn(lore);
        when(inventory.getItemInMainHand()).thenReturn(weapon);
        when(player.getInventory()).thenReturn(inventory);
        when(enchantments.getEnchantments(any())).thenReturn(CompletableFuture.completedFuture(enchanted));
    }

    private GateDoorDamageEvent counted() {
        GateDoorDamageEvent event = mock(GateDoorDamageEvent.class);
        when(event.isCancelled()).thenReturn(false);
        return event;
    }

    // ===== multipliers and numbers =====

    @Test
    void meleeHitsScaleByAQuarterPerLevel() {
        assertEquals(1.0, cleaving.damageMultiplier(0), 1e-9);
        assertEquals(1.25, cleaving.damageMultiplier(1), 1e-9);
        assertEquals(1.5, cleaving.damageMultiplier(2), 1e-9);
        assertEquals(1.75, cleaving.damageMultiplier(3), 1e-9);
        assertEquals(2.0, cleaving.damageMultiplier(4), 1e-9);
        assertEquals(1.5, cleaving.damageMultiplierOf(player), 1e-9);
    }

    @Test
    void aNetheriteSwordsChargedStrikeTakesATenthToAQuarterOfADefaultGate() {
        // 8 x 1.25 x 5, 8 x 1.5 x 6, 8 x 1.75 x 7, 8 x 2 x 8 of 500 HP
        assertEquals(50.0, cleaving.chargedDamage(8.0, 1), 1e-9);
        assertEquals(72.0, cleaving.chargedDamage(8.0, 2), 1e-9);
        assertEquals(98.0, cleaving.chargedDamage(8.0, 3), 1e-9);
        assertEquals(128.0, cleaving.chargedDamage(8.0, 4), 1e-9);
    }

    @AfterEach
    void clearOverrides() {
        EnchantmentTuningTable.getInstance().clearOverrides();
    }

    @Test
    void theBuiltInCooldownShrinksPerLevel_AndBeyondLevelIvTheStepsGoOn() {
        assertEquals(60_000L, cleaving.chargedCooldownMillis(1));
        assertEquals(50_000L, cleaving.chargedCooldownMillis(2));
        assertEquals(40_000L, cleaving.chargedCooldownMillis(3));
        assertEquals(30_000L, cleaving.chargedCooldownMillis(4));
        assertEquals(0L, cleaving.chargedCooldownMillis(9), "never below 0");
        assertEquals(13.0, cleaving.chargedHitMultiplier(9), 1e-9);
    }

    /** PandiO/knk-workspace#259: the admin's combat values, read per hit (no restart). */
    @Test
    void anAdminsCombatValuesApplyAtTheNextHit() {
        EnchantmentTuningTable.getInstance().replaceOverrides(List.of(new EnchantmentTuningOverride("knk:cleaving",
                null, null, null, 90, null, null, null, null, null, null, null, null, 0.5d, 3.0d, 2.0d, -20)));

        assertEquals(1.5, cleaving.damageMultiplier(1), 1e-9);
        assertEquals(3.0, cleaving.damageMultiplier(4), 1e-9);
        assertEquals(3.0, cleaving.chargedHitMultiplier(1), 1e-9);
        assertEquals(9.0, cleaving.chargedHitMultiplier(4), 1e-9);
        assertEquals(90_000L, cleaving.chargedCooldownMillis(1));
        assertEquals(30_000L, cleaving.chargedCooldownMillis(4));
        assertEquals(8.0 * 1.5 * 3.0, cleaving.chargedDamage(8.0, 1), 1e-9);
    }

    @Test
    void aSwitchedOffCooldownMeansNoStrikeCooldown() {
        EnchantmentTuningTable.getInstance().replaceOverrides(List.of(new EnchantmentTuningOverride("cleaving",
                null, null, false, null, null, null)));

        assertEquals(0L, cleaving.chargedCooldownMillis(1));
        assertEquals(5.0, cleaving.chargedHitMultiplier(1), 1e-9);
    }

    // ===== the level on the weapon =====

    @Test
    void aWeaponWithoutLoreHasNoCleaving() {
        holding(List.of(), Map.of());

        assertEquals(0, cleaving.levelOf(player));
        assertEquals(GateCleaving.Strike.NOT_APPLICABLE, cleaving.tryStrike(player, gate, block, 8.0));
        verifyNoInteractions(hitService);
    }

    @Test
    void aFailingEnchantmentLookupCountsAsNoCleaving() {
        when(enchantments.getEnchantments(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        assertEquals(0, cleaving.levelOf(player));
    }

    // ===== the charged strike =====

    @Test
    void aReadyStrikeHitsTheGateWithTheChargedDamageThenCoolsDownForItsLevel() {
        GateDoorDamageEvent event = counted();
        when(hitService.handleDamage(eq(gate), eq(player), eq(block), eq(GateDoorDamageEvent.Cause.ENCHANTMENT), anyDouble()))
            .thenReturn(event);

        assertEquals(GateCleaving.Strike.STRUCK, cleaving.tryStrike(player, gate, block, 8.0));

        verify(hitService).handleDamage(gate, player, block, GateDoorDamageEvent.Cause.ENCHANTMENT, 72.0);
        verify(cooldowns).applyCooldown(PLAYER_ID, GateCleaving.CLEAVING_ID, 50_000L);
        verify(effects).strike(player, block, 2);
    }

    @Test
    void withoutAMeleeFigureTheFlatDefaultOfTenIsCharged() {
        GateDoorDamageEvent event = counted();
        when(hitService.handleDamage(any(), any(), any(), any(), anyDouble())).thenReturn(event);

        cleaving.tryStrike(player, gate, block, null);

        verify(hitService).handleDamage(gate, player, block, GateDoorDamageEvent.Cause.ENCHANTMENT, 90.0);
    }

    @Test
    void aCoolingStrikeTellsThePlayerAndHitsNothing() {
        when(cooldowns.getRemainingCooldown(PLAYER_ID, GateCleaving.CLEAVING_ID)).thenReturn(CompletableFuture.completedFuture(12_500L));

        assertEquals(GateCleaving.Strike.ON_COOLDOWN, cleaving.tryStrike(player, gate, block, 8.0));

        verify(effects).cooling(player, 12_500L);
        verifyNoInteractions(hitService);
        verify(cooldowns, never()).applyCooldown(any(), anyString(), anyLong());
    }

    @Test
    void aVetoedHitStartsNoCooldownAndShowsNothing() {
        GateDoorDamageEvent vetoed = mock(GateDoorDamageEvent.class);
        when(vetoed.isCancelled()).thenReturn(true);
        when(hitService.handleDamage(any(), any(), any(), any(), anyDouble())).thenReturn(vetoed);

        assertEquals(GateCleaving.Strike.VETOED, cleaving.tryStrike(player, gate, block, 8.0));

        verify(cooldowns, never()).applyCooldown(any(), anyString(), anyLong());
        verify(effects, never()).strike(any(), any(), anyInt());
    }

    @Test
    void aGateThatDoesNotQualifyIsVetoedToo() {
        when(hitService.handleDamage(any(), any(), any(), any(), anyDouble())).thenReturn(null);

        assertEquals(GateCleaving.Strike.VETOED, cleaving.tryStrike(player, gate, block, 8.0));
        verify(cooldowns, never()).applyCooldown(any(), anyString(), anyLong());
    }

    @Test
    void aCreativePlayerCannotCleave() {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);

        assertEquals(GateCleaving.Strike.NOT_APPLICABLE, cleaving.tryStrike(player, gate, block, 8.0));
        verifyNoInteractions(hitService);
    }
}
