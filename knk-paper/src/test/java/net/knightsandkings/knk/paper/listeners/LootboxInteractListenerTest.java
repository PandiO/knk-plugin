package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.lootbox.ActiveLootboxCache;
import net.knightsandkings.knk.core.lootbox.ClaimGuard;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxSpawn;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import net.knightsandkings.knk.paper.lootbox.LootboxAnnouncer;
import net.knightsandkings.knk.paper.lootbox.LootboxDelivery;
import net.knightsandkings.knk.paper.lootbox.LootboxPresenter;
import net.knightsandkings.knk.paper.lootbox.LootboxRuntime;
import net.knightsandkings.knk.paper.lootbox.LootboxSettings;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lootboxes Phase 3: the checks before a claim (permission, staff mode, distance, account, free slot, in-flight guard)
 * and what a refusal does.
 */
class LootboxInteractListenerTest {

    private static final UUID TOKEN = UUID.fromString("00000000-0000-0000-0000-00000000b0c5");

    private final LootboxRuntime runtime = mock(LootboxRuntime.class);
    private final ActiveLootboxCache cache = new ActiveLootboxCache();
    private final LootboxesCommandApi api = mock(LootboxesCommandApi.class);
    private final LootboxDelivery delivery = mock(LootboxDelivery.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final Entity hitbox = mock(Entity.class);
    private final UUID playerId = UUID.randomUUID();

    private boolean allowed = true;
    private ActiveMode mode = ActiveMode.NONE;
    private Integer userId = 9;
    private LootboxInteractListener listener;

    private static KnkLootboxSpawn spawn() {
        return new KnkLootboxSpawn(12, TOKEN, 3, "Weapons Lootbox", "Weapons", 5, "Legendary", 5, "Legendary Weapons Lootbox",
                1, "spawn", "world", 10, 64, 10, "Active", Instant.EPOCH, Instant.now().plusSeconds(600));
    }

    @BeforeEach
    void setUp() {
        cache.put(spawn());
        when(runtime.cache()).thenReturn(cache);
        when(runtime.settings()).thenReturn(LootboxSettings.defaults());
        when(runtime.config()).thenReturn(KnkLootboxRuntimeConfig.empty());
        when(runtime.clock()).thenReturn(Clock.systemUTC());
        when(world.getName()).thenReturn("world");
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.firstEmpty()).thenReturn(3);
        standAt(12.0, 64, 10.5);

        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.get(LootboxPresenter.TOKEN_KEY, PersistentDataType.STRING)).thenReturn(TOKEN.toString());
        when(hitbox.getPersistentDataContainer()).thenReturn(pdc);

        listener = new LootboxInteractListener(runtime, new ClaimGuard(), api, delivery, mock(LootboxAnnouncer.class),
                (p, node) -> allowed, p -> mode, p -> userId, Runnable::run);
    }

    private void standAt(double x, double y, double z) {
        when(player.getLocation()).thenReturn(new Location(world, x, y, z));
    }

    @Test
    void aGoodClick_claimsWithTheTokenAndTheIdempotencyKey() {
        when(api.claim(anyInt(), any(), anyInt(), anyString())).thenReturn(new CompletableFuture<>());

        assertEquals(LootboxInteractListener.Attempt.CLAIMING, listener.attemptOpen(player, hitbox));

        verify(api).claim(12, TOKEN, 9, TOKEN + ":9");
    }

    @Test
    void aSecondClickWhileInFlight_isIgnored() {
        when(api.claim(anyInt(), any(), anyInt(), anyString())).thenReturn(new CompletableFuture<>());

        listener.attemptOpen(player, hitbox);
        assertEquals(LootboxInteractListener.Attempt.IN_FLIGHT, listener.attemptOpen(player, hitbox));

        verify(api, times(1)).claim(anyInt(), any(), anyInt(), anyString());
    }

    @Test
    void staffMode_isRefused_unlessConfigured() {
        mode = ActiveMode.STAFF;

        assertEquals(LootboxInteractListener.Attempt.STAFF_MODE, listener.attemptOpen(player, hitbox));
        verify(api, never()).claim(anyInt(), any(), anyInt(), anyString());
    }

    @Test
    void tooFar_isRefused() {
        standAt(20, 64, 10.5);

        assertEquals(LootboxInteractListener.Attempt.TOO_FAR, listener.attemptOpen(player, hitbox));
        verify(player).sendMessage(contains("closer"));
    }

    @Test
    void otherWorld_isTooFar() {
        World nether = mock(World.class);
        when(nether.getName()).thenReturn("world_nether");
        when(player.getLocation()).thenReturn(new Location(nether, 10.5, 64, 10.5));

        assertEquals(LootboxInteractListener.Attempt.TOO_FAR, listener.attemptOpen(player, hitbox));
    }

    @Test
    void fullInventory_isRefusedBeforeAnyApiCall() {
        when(inventory.firstEmpty()).thenReturn(-1);

        assertEquals(LootboxInteractListener.Attempt.INVENTORY_FULL, listener.attemptOpen(player, hitbox));
        verify(player).sendMessage(contains("inventory is full"));
        verify(api, never()).claim(anyInt(), any(), anyInt(), anyString());
    }

    @Test
    void noPermission_orNoAccount_isRefused() {
        allowed = false;
        assertEquals(LootboxInteractListener.Attempt.NO_PERMISSION, listener.attemptOpen(player, hitbox));
        allowed = true;
        userId = null;
        assertEquals(LootboxInteractListener.Attempt.NO_ACCOUNT, listener.attemptOpen(player, hitbox));
        verify(api, never()).claim(anyInt(), any(), anyInt(), anyString());
    }

    @Test
    void aBoxNotInTheCache_isAnOrphanAndRemoved() {
        cache.remove(12);

        assertEquals(LootboxInteractListener.Attempt.ORPHAN, listener.attemptOpen(player, hitbox));
        verify(hitbox).remove();
    }

    @Test
    void someoneElseFirst_takesTheBoxDown_andReleasesTheGuard() {
        when(api.claim(anyInt(), any(), anyInt(), anyString())).thenReturn(CompletableFuture.failedFuture(
                new LootboxRejectedException(409, LootboxRejectedException.ALREADY_CLAIMED, "x", null, null, null)));

        listener.attemptOpen(player, hitbox);

        verify(player).sendMessage(contains("Someone else got there first"));
        verify(runtime).gone(12);
    }

    @Test
    void dailyLimit_saysWhenItResets_andKeepsTheBox() {
        when(api.claim(anyInt(), any(), anyInt(), anyString())).thenReturn(CompletableFuture.failedFuture(
                new LootboxRejectedException(429, "DailyLimit", "x", "Global", 10, Instant.parse("2026-09-27T00:00:00Z"))));

        listener.attemptOpen(player, hitbox);

        verify(player).sendMessage(contains("You've opened 10 lootboxes today — the limit resets at 00:00 UTC."));
        verify(runtime, never()).gone(anyInt());
    }

    @Test
    void apiDown_saysStuck() {
        when(api.claim(anyInt(), any(), anyInt(), anyString())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));

        listener.attemptOpen(player, hitbox);

        verify(player).sendMessage(contains("stuck"));
        verify(runtime, never()).gone(anyInt());
    }

    @Test
    void aClaim_isDelivered() {
        KnkLootboxClaimResult claim = new KnkLootboxClaimResult(41, false, 9, 12, 3, 5, "Legendary Weapons Lootbox", 5L, 77,
                "Steel Sword", 3, 3, 1, false, null, false, null, null);
        when(api.claim(anyInt(), any(), anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(claim));
        when(delivery.deliver(player, claim, false)).thenReturn(new CompletableFuture<>());

        listener.attemptOpen(player, hitbox);

        verify(runtime).gone(12);
        verify(delivery).deliver(eq(player), eq(claim), eq(false));
    }

    @Test
    void withinReach_measuresFromTheBoxCentre() {
        standAt(10.5, 64, 15.5);
        assertTrue(LootboxInteractListener.withinReach(player, spawn(), 5));
        standAt(10.5, 64, 15.6);
        assertFalse(LootboxInteractListener.withinReach(player, spawn(), 5));
    }
}
