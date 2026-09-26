package net.knightsandkings.knk.paper.teleport;

import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;
import net.knightsandkings.knk.core.domain.teleport.TeleportChargeResult;
import net.knightsandkings.knk.core.domain.teleport.TeleportRefundResult;
import net.knightsandkings.knk.core.ports.api.TeleportDestinationsCommandApi;
import net.knightsandkings.knk.core.teleport.BlockProbe;
import net.knightsandkings.knk.core.teleport.TeleportCharger;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.core.teleport.TeleportRequestBook.Direction;
import net.knightsandkings.knk.core.teleport.TeleportRequestSettings;
import net.knightsandkings.knk.core.teleport.TeleportSettings;
import net.knightsandkings.knk.core.teleport.WarmupCancelReason;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Paid teleports through the engine (docs/specs/teleport/DESIGN.md §3.4 step 3, §3.7.3, Phase 5):
 * the charge happens only after the warmup, a cancelled warmup charges nothing, a refused charge
 * shows the server's reason, and every paid teleport that doesn't arrive is refunded under the same
 * key. Also the /tpa coin fee, paid by the requester.
 */
class TeleportChargeEngineTest {

    /** Records every call; the next charge answer is scripted. */
    private static final class FakeChargeApi implements TeleportDestinationsCommandApi {
        final List<String> warpKeys = new ArrayList<>();
        final List<String> feeCalls = new ArrayList<>();
        final List<String> refundKeys = new ArrayList<>();
        TeleportChargeResult next = TeleportChargeResult.allowed("Gems", 10, 40, false, null);

        @Override
        public CompletableFuture<TeleportChargeResult> chargeWarp(int domainId, int userId, String key,
                                                                 boolean bypassRequirements, boolean bypassCost) {
            warpKeys.add(key);
            return CompletableFuture.completedFuture(next);
        }

        @Override
        public CompletableFuture<TeleportChargeResult> chargeRequestFee(int userId, int amountCoins, String key, Integer otherUserId) {
            feeCalls.add(userId + ":" + amountCoins + ":" + otherUserId + ":" + key);
            return CompletableFuture.completedFuture(next);
        }

        @Override
        public CompletableFuture<TeleportRefundResult> refund(int userId, String key, String reason) {
            refundKeys.add(key);
            return CompletableFuture.completedFuture(new TeleportRefundResult(true, "Gems", 10, 50L, false));
        }
    }

    private long now = 1_000_000;
    private final Map<UUID, Set<String>> granted = new HashMap<>();
    private final World world = world();
    private boolean safe = true;
    private final BlockProbe flat = new BlockProbe() {
        @Override public boolean isPassable(int x, int y, int z) { return y >= 64; }
        @Override public boolean isSolid(int x, int y, int z) { return y < 64; }
        @Override public boolean isHazard(int x, int y, int z) { return !safe; }
        @Override public int minY() { return -64; }
        @Override public int maxY() { return 320; }
    };
    private final TeleportService.PermissionLookup permissions = (player, node) ->
        CompletableFuture.completedFuture(granted.getOrDefault(player.getUniqueId(), Set.of()).contains(node));
    private final TeleportService engine = new TeleportService(Runnable::run, permissions,
        TeleportSettings.defaults(), () -> now, w -> flat);

    private final Player alice = player("Alice", new Location(world, 0.5, 64, 0.5));
    private final Player bob = player("Bob", new Location(world, 100.5, 64, 100.5));
    private final List<Player> everyone = List.of(alice, bob);
    private final Map<UUID, Integer> userIds = Map.of(alice.getUniqueId(), 7, bob.getUniqueId(), 8);

    private final FakeChargeApi api = new FakeChargeApi();
    private final TeleportCharges charges = new TeleportCharges(new TeleportCharger(api, 3, Runnable::run, Runnable::run),
        uuid -> CompletableFuture.completedFuture(userIds.get(uuid)), name -> "world".equals(name) ? world : null, this::byId);
    private final KnkTeleportDestination kardenna = new KnkTeleportDestination(3, "Kardenna", "Town", "world",
        50.5, 64, 50.5, 0f, 0f, 10, null, null, false, true, true, true, null, null);

    private static World world() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getChunkAtAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(mock(Chunk.class)));
        return world;
    }

    private static Player player(String name, Location location) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(location);
        when(player.teleportAsync(any(Location.class), any(TeleportCause.class))).thenReturn(CompletableFuture.completedFuture(true));
        return player;
    }

    private Player byId(UUID id) {
        return everyone.stream().filter(p -> p.getUniqueId().equals(id)).findFirst().orElse(null);
    }

    private CompletableFuture<TeleportOutcome> warp(Player player) {
        Location location = TeleportCharges.toLocation(kardenna, name -> world);
        return engine.start(TeleportPlan.warp(player, location, kardenna.name(), charges.warp(player, kardenna, false, false)));
    }

    private void advance(long millis) {
        now += millis;
        engine.tick(id -> false);
    }

    @Test
    void theChargeHappensAfterTheWarmup_AndThePlayerIsToldWhatItCost() {
        CompletableFuture<TeleportOutcome> outcome = warp(alice);

        assertTrue(api.warpKeys.isEmpty(), "nothing is charged before the warmup ends");
        advance(5_000);

        assertEquals(1, api.warpKeys.size());
        assertTrue(api.warpKeys.get(0).startsWith("warp:"));
        assertTrue(outcome.join().isTeleported());
        ArgumentCaptor<Location> to = ArgumentCaptor.forClass(Location.class);
        verify(alice).teleportAsync(to.capture(), any(TeleportCause.class));
        assertEquals(50.5, to.getValue().getX());
        verify(alice).sendMessage(contains("You paid 10 gems and your new balance is 40."));
        assertTrue(api.refundKeys.isEmpty());
    }

    @Test
    void aCancelledWarmupChargesNothing() {
        CompletableFuture<TeleportOutcome> outcome = warp(alice);

        engine.cancelWarmup(alice.getUniqueId(), WarmupCancelReason.MOVED);
        advance(10_000);

        assertEquals(TeleportOutcome.Status.CANCELLED, outcome.join().status());
        assertTrue(api.warpKeys.isEmpty());
        assertTrue(api.refundKeys.isEmpty());
    }

    @Test
    void aRefusedChargeShowsTheServersReason_AndNothingMoves() {
        api.next = TeleportChargeResult.refused("TitleTooLow", "Reach title Knight to unlock");

        CompletableFuture<TeleportOutcome> outcome = warp(alice);
        advance(5_000);

        assertEquals(TeleportOutcome.Status.DENIED, outcome.join().status());
        assertEquals("Reach title Knight to unlock", outcome.join().message());
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
        assertTrue(api.refundKeys.isEmpty(), "nothing was charged, nothing to refund");
    }

    @Test
    void aBlockedTeleportIsRefundedUnderTheChargesKey() {
        when(alice.teleportAsync(any(Location.class), any(TeleportCause.class))).thenReturn(CompletableFuture.completedFuture(false));

        CompletableFuture<TeleportOutcome> outcome = warp(alice);
        advance(5_000);

        assertEquals(TeleportOutcome.Status.FAILED, outcome.join().status());
        assertEquals(api.warpKeys, api.refundKeys);
        verify(alice, never()).sendMessage(contains("You paid"));
    }

    @Test
    void anUnsafeDestinationAfterTheChargeIsRefunded() {
        safe = false;

        CompletableFuture<TeleportOutcome> outcome = warp(alice);
        advance(5_000);

        assertEquals(TeleportOutcome.Status.DENIED, outcome.join().status());
        assertEquals(1, api.refundKeys.size());
    }

    @Test
    void aFreeWarpIsAuthorizedButNeverRefunded() {
        api.next = TeleportChargeResult.allowed("Gems", 0, 3, false, null);
        when(alice.teleportAsync(any(Location.class), any(TeleportCause.class))).thenReturn(CompletableFuture.completedFuture(false));

        warp(alice);
        advance(5_000);

        assertEquals(1, api.warpKeys.size());
        assertTrue(api.refundKeys.isEmpty());
    }

    @Test
    void aPlayerWithoutAnAccountCantWarp() {
        Player carol = player("Carol", new Location(world, 1.5, 64, 1.5));
        Location location = TeleportCharges.toLocation(kardenna, name -> world);

        CompletableFuture<TeleportOutcome> outcome = engine.start(
            TeleportPlan.warp(carol, location, "Kardenna", charges.warp(carol, kardenna, false, false)));
        advance(5_000);

        assertEquals(TeleportOutcome.Status.DENIED, outcome.join().status());
        assertTrue(api.warpKeys.isEmpty());
    }

    // ===== paid /tpa, /tpahere =====

    private TeleportRequestService paidRequests(int coins) {
        TeleportSettings d = TeleportSettings.defaults();
        engine.updateSettings(new TeleportSettings(d.warmupSeconds(), d.warmupShortSeconds(), d.cooldownSeconds(),
            d.combatTagSeconds(), d.safeSearchRadius(), new TeleportRequestSettings(30, 0, 5, coins)));
        VisibleTargetResolver targets = new VisibleTargetResolver(
            name -> everyone.stream().filter(p -> p.getName().equalsIgnoreCase(name)).findFirst().orElse(null),
            () -> everyone, p -> false);
        TeleportRequestService requests = new TeleportRequestService(engine, Runnable::run, permissions, targets, this::byId);
        requests.setCharges(charges);
        return requests;
    }

    @Test
    void aPaidTpahereChargesTheRequester_EvenThoughTheTargetMoves() {
        api.next = TeleportChargeResult.allowed("Coins", 250, 750, false, null);
        TeleportRequestService requests = paidRequests(250);

        requests.send(alice, bob, Direction.TO_REQUESTER);
        verify(alice).sendMessage(contains("It costs you 250 coins"));
        requests.accept(bob, null);
        assertTrue(api.feeCalls.isEmpty(), "charged at commit, not at accept");
        advance(5_000);

        assertEquals(1, api.feeCalls.size());
        assertTrue(api.feeCalls.get(0).startsWith("7:250:8:tpa:"), api.feeCalls.get(0));
        verify(bob).teleportAsync(any(Location.class), any(TeleportCause.class));
        verify(alice).sendMessage(contains("You paid 250 coins and your new balance is 750."));
    }

    @Test
    void aRequesterWhoCantPayStopsTheTeleport() {
        api.next = TeleportChargeResult.refused("InsufficientCoins", "You don't have enough coins to send this teleport request!");
        TeleportRequestService requests = paidRequests(250);

        requests.send(alice, bob, Direction.TO_REQUESTER);
        requests.accept(bob, null);
        advance(5_000);

        verify(bob, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
        verify(bob).sendMessage(contains("Alice doesn't have the 250 coins"));
        assertFalse(api.feeCalls.isEmpty());
    }
}
