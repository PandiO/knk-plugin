package net.knightsandkings.knk.paper.teleport;

import net.knightsandkings.knk.core.dataaccess.TeleportPolicyDataAccess;
import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;
import net.knightsandkings.knk.core.domain.teleport.KnkTeleportPolicy;
import net.knightsandkings.knk.core.domain.teleport.TeleportPayment;
import net.knightsandkings.knk.core.domain.teleport.TeleportChargeResult;
import net.knightsandkings.knk.core.domain.teleport.TeleportRefundResult;
import net.knightsandkings.knk.core.ports.api.TeleportDestinationsCommandApi;
import net.knightsandkings.knk.core.ports.api.TeleportDestinationsQueryApi;
import net.knightsandkings.knk.core.teleport.BlockProbe;
import net.knightsandkings.knk.core.teleport.TeleportCharger;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportKind;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
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
        /** When set, warp charges stay unanswered until the test completes it. */
        CompletableFuture<TeleportChargeResult> pending;

        @Override
        public CompletableFuture<TeleportChargeResult> chargeWarp(int domainId, int userId, String key,
                                                                 boolean bypassRequirements, boolean bypassCost) {
            warpKeys.add(key);
            return pending != null ? pending : CompletableFuture.completedFuture(next);
        }

        @Override
        public CompletableFuture<TeleportChargeResult> chargeRequestFee(int userId, int amountCoins, String key, Integer otherUserId) {
            feeCalls.add(userId + ":" + amountCoins + ":" + otherUserId + ":" + key);
            return CompletableFuture.completedFuture(next);
        }

        @Override
        public CompletableFuture<TeleportChargeResult> chargeSpawnFee(int userId, String key) {
            feeCalls.add(userId + ":spawn:" + key);
            return CompletableFuture.completedFuture(next);
        }

        @Override
        public CompletableFuture<TeleportChargeResult> chargeBackFee(int userId, int amountCoins, String key, String backKind) {
            feeCalls.add(userId + ":" + amountCoins + ":" + backKind + ":" + key);
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

    // ===== plugin shutdown =====

    @Test
    void aChargeInFlightAtShutdown_IsRefundedUnderItsKey() {
        api.pending = new CompletableFuture<>();
        CompletableFuture<TeleportOutcome> outcome = warp(alice);
        advance(5_000);
        assertEquals(1, api.warpKeys.size(), "the charge is on its way");
        assertEquals(1, engine.openChargeCount());

        engine.abandonOpenCharges("the server shut down").join();

        // Reverses the charge if it went through, voids the key if it's still on its way.
        assertEquals(api.warpKeys, api.refundKeys);
        assertEquals(0, engine.openChargeCount());
        assertFalse(outcome.isDone(), "a disabled plugin gets no main-thread continuation");
    }

    @Test
    void aChargeAnsweredButNotYetTeleportedAtShutdown_IsRefunded() {
        when(world.getChunkAtAsync(any(Location.class))).thenReturn(new CompletableFuture<>());
        warp(alice);
        advance(5_000);
        assertEquals(1, api.warpKeys.size());

        engine.abandonOpenCharges("the server shut down").join();

        assertEquals(api.warpKeys, api.refundKeys);
    }

    @Test
    void aChargeAbandonedBeforeItIsSent_IsNeverSent() {
        CompletableFuture<Integer> idLookup = new CompletableFuture<>();
        TeleportCharges slowIds = new TeleportCharges(new TeleportCharger(api, 3, Runnable::run, Runnable::run),
            uuid -> idLookup, name -> world, this::byId);
        Location location = TeleportCharges.toLocation(kardenna, name -> world);
        engine.start(TeleportPlan.warp(alice, location, "Kardenna", slowIds.warp(alice, kardenna, false, false)));
        advance(5_000);

        engine.abandonOpenCharges("the server shut down").join();
        idLookup.complete(7);

        assertTrue(api.warpKeys.isEmpty(), "no charge after abandon - it couldn't be refunded any more");
        assertTrue(api.refundKeys.isEmpty(), "nothing was sent, nothing to refund");
    }

    @Test
    void aTeleportAlreadyHandedToBukkit_IsNotRefundedOnShutdown() {
        CompletableFuture<Boolean> teleporting = new CompletableFuture<>();
        when(alice.teleportAsync(any(Location.class), any(TeleportCause.class))).thenReturn(teleporting);
        warp(alice);
        advance(5_000);

        engine.abandonOpenCharges("the server shut down").join();

        assertTrue(api.refundKeys.isEmpty(), "the player is on the way - they get what they paid for");
        teleporting.complete(false);
        assertEquals(api.warpKeys, api.refundKeys, "a teleport that then fails is still refunded");
    }

    @Test
    void aFinishedWarpLeavesNoOpenCharge() {
        warp(alice);
        advance(5_000);

        assertEquals(0, engine.openChargeCount());
        engine.abandonOpenCharges("the server shut down").join();
        assertTrue(api.refundKeys.isEmpty());
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

    // ===== KNG-41: permission-group fees and cooldowns =====

    /** The policy every player gets from the fake API; null = the API can't be reached. */
    private KnkTeleportPolicy groupPolicy = KnkTeleportPolicy.DEFAULT;

    private void useGroupSettings() {
        TeleportDestinationsQueryApi query = new TeleportDestinationsQueryApi() {
            @Override
            public CompletableFuture<List<KnkTeleportDestination>> listForUser(int userId) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public CompletableFuture<KnkTeleportPolicy> policyForUser(int userId) {
                return groupPolicy != null ? CompletableFuture.completedFuture(groupPolicy)
                    : CompletableFuture.failedFuture(new RuntimeException("API down"));
            }
        };
        TeleportPolicyDataAccess policies = new TeleportPolicyDataAccess(query,
            uuid -> CompletableFuture.completedFuture(userIds.get(uuid)), Duration.ofSeconds(60),
            Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC));
        charges.setPolicies(policies);
        engine.setCooldownPolicy(new TeleportService.CooldownPolicy() {
            @Override
            public OptionalInt cooldownSeconds(UUID player, TeleportKind kind) {
                return policies.cachedOrDefault(player).of(kind).cooldown();
            }

            @Override
            public void prefetch(UUID player) {
                policies.refresh(player, TeleportPolicyDataAccess.PLAYER_READ_MAX_AGE);
            }
        });
    }

    private static KnkTeleportPolicy.Kind fixed(int coins, int gems, int xp) {
        return new KnkTeleportPolicy.Kind("Fixed", null, coins, gems, xp, null);
    }

    @Test
    void aFreeTpa_AsksTheServerNothing() {
        useGroupSettings();
        TeleportRequestService requests = paidRequests(0);

        requests.send(alice, bob, Direction.TO_TARGET);
        requests.accept(bob, null);
        advance(5_000);

        verify(alice).teleportAsync(any(Location.class), any(TeleportCause.class));
        assertTrue(api.feeCalls.isEmpty());
        verify(alice, never()).sendMessage(contains("It costs you"));
    }

    @Test
    void aGroupPricedTpa_IsChargedEvenWhenTheDefaultIsFree() {
        groupPolicy = new KnkTeleportPolicy(fixed(10, 1, 0), null, null);
        useGroupSettings();
        api.next = TeleportChargeResult.allowed("Coins", List.of(new TeleportPayment("Coins", 10, 990),
            new TeleportPayment("Gems", 1, 49)), 990, false, null);
        TeleportRequestService requests = paidRequests(0);

        requests.send(alice, bob, Direction.TO_TARGET);
        verify(alice).sendMessage(contains("It costs you 10 coins and 1 gem if the teleport happens."));
        requests.accept(bob, null);
        advance(5_000);

        assertEquals(1, api.feeCalls.size());
        assertTrue(api.feeCalls.get(0).startsWith("7:0:8:tpa:"), api.feeCalls.get(0));
        verify(alice).sendMessage(contains("You paid 10 coins and 1 gem; your new balance is 990 coins and 49 gems."));
    }

    @Test
    void aGroupThatCantBePaid_NamesTheGroupPrice() {
        groupPolicy = new KnkTeleportPolicy(fixed(0, 2, 0), null, null);
        useGroupSettings();
        api.next = TeleportChargeResult.refused("InsufficientGems", "You don't have enough gems to send this teleport request!");
        TeleportRequestService requests = paidRequests(250);

        requests.send(alice, bob, Direction.TO_REQUESTER);
        requests.accept(bob, null);
        advance(5_000);

        verify(bob, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
        verify(bob).sendMessage(contains("Alice doesn't have the 2 gems this teleport request costs."));
    }

    @Test
    void aGroupMultiplierOfZero_MakesThePaidDefaultFree() {
        groupPolicy = new KnkTeleportPolicy(new KnkTeleportPolicy.Kind("Multiplier", 0d, null, null, null, null), null, null);
        useGroupSettings();
        TeleportRequestService requests = paidRequests(250);

        requests.send(alice, bob, Direction.TO_TARGET);
        requests.accept(bob, null);
        advance(5_000);

        verify(alice).teleportAsync(any(Location.class), any(TeleportCause.class));
        assertTrue(api.feeCalls.isEmpty());
    }

    @Test
    void withoutAnAnswerAboutGroups_TheDefaultApplies() {
        groupPolicy = null; // the policy can't be loaded
        useGroupSettings();
        api.next = TeleportChargeResult.allowed("Coins", 250, 750, false, null);
        TeleportRequestService requests = paidRequests(250);

        requests.send(alice, bob, Direction.TO_TARGET);
        requests.accept(bob, null);
        advance(5_000);

        assertEquals(1, api.feeCalls.size());
        assertTrue(api.feeCalls.get(0).startsWith("7:250:8:tpa:"), api.feeCalls.get(0));
    }

    @Test
    void aGroupPricedSpawn_IsCharged_AFreeOneAsksNothing() {
        useGroupSettings();
        Location spawn = new Location(world, 0.5, 64, 0.5);
        CompletableFuture<TeleportOutcome> free = engine.start(TeleportPlan.spawn(alice, spawn, "Spawn").withCharge(charges.spawnFee(alice)));
        advance(5_000);
        assertTrue(free.join().isTeleported());
        assertTrue(api.feeCalls.isEmpty(), "free /spawn: no server call");

        groupPolicy = new KnkTeleportPolicy(null, null, fixed(30, 0, 0));
        useGroupSettings();
        api.next = TeleportChargeResult.allowed("Coins", 30, 70, false, null);
        CompletableFuture<TeleportOutcome> paid = engine.start(TeleportPlan.spawn(bob, spawn, "Spawn").withCharge(charges.spawnFee(bob)));
        advance(5_000);

        assertTrue(paid.join().isTeleported());
        assertEquals(1, api.feeCalls.size());
        assertTrue(api.feeCalls.get(0).startsWith("8:spawn:spawn:"), api.feeCalls.get(0));
        verify(bob).sendMessage(contains("You paid 30 coins and your new balance is 70."));
    }

    @Test
    void aGroupCooldownReplacesTheConfiguredOne() {
        groupPolicy = new KnkTeleportPolicy(null, new KnkTeleportPolicy.Kind("None", null, null, null, null, 5), null);
        useGroupSettings();
        Location town = new Location(world, 500.5, 64, 500.5);

        assertTrue(startAndWarm(alice, town).isTeleported());
        advance(6_000);
        // The default (30 s) would still refuse; the group's 5 s are over.
        assertTrue(startAndWarm(alice, town).isTeleported());
        assertEquals(5, engine.cooldownSeconds(alice.getUniqueId(), TeleportKind.WARP));
        assertEquals(TeleportSettings.defaults().cooldownSeconds(), engine.cooldownSeconds(alice.getUniqueId(), TeleportKind.SPAWN));
    }

    @Test
    void withoutAGroupCooldown_TheConfiguredOneApplies() {
        useGroupSettings();
        Location town = new Location(world, 500.5, 64, 500.5);

        assertTrue(startAndWarm(alice, town).isTeleported());
        advance(6_000);
        TeleportOutcome second = engine.start(TeleportPlan.warp(alice, town, "Town", null)).join();

        assertFalse(second.isTeleported());
        assertEquals(TeleportDenial.COOLDOWN, second.code());
    }

    private TeleportOutcome startAndWarm(Player player, Location to) {
        CompletableFuture<TeleportOutcome> outcome = engine.start(TeleportPlan.warp(player, to, "Town", null));
        advance(5_000);
        return outcome.join();
    }

    // ===== KNG-42: the /back fee =====

    private void grant(Player player, String... nodes) {
        granted.computeIfAbsent(player.getUniqueId(), id -> new HashSet<>()).addAll(Set.of(nodes));
    }

    private BackService paidBack(int priceCoins) {
        TeleportSettings d = TeleportSettings.defaults();
        engine.updateSettings(new TeleportSettings(d.warmupSeconds(), d.warmupShortSeconds(), d.cooldownSeconds(),
            d.combatTagSeconds(), d.safeSearchRadius(), d.request(), d.destinationsCacheSeconds(),
            new net.knightsandkings.knk.core.teleport.TeleportBackSettings(true, 300, Map.of(), priceCoins)));
        return new BackService(engine, Runnable::run, permissions, id -> world);
    }

    /** {@code player} warps from where they stand to the town for free, leaving a WARPS place behind. */
    private void warpAwayFree(Player player) {
        Location town = new Location(world, 500.5, 64, 500.5);
        TeleportOutcome outcome = engine.start(TeleportPlan.warp(player, town, "Town", null)).join();
        assertTrue(outcome.isTeleported());
        when(player.getLocation()).thenReturn(town);
    }

    @Test
    void aPaidBackIsChargedAfterTheWarmup_TaggedWithItsKind() {
        BackService back = paidBack(250);
        back.setCharges(charges);
        grant(alice, TeleportNodes.BACK_WARPS, TeleportNodes.BYPASS_WARMUP, TeleportNodes.BYPASS_COOLDOWN);
        warpAwayFree(alice);
        api.next = TeleportChargeResult.allowed("Coins", 250, 750, false, null);
        granted.get(alice.getUniqueId()).remove(TeleportNodes.BYPASS_WARMUP);

        CompletableFuture<BackService.Trip> trip = back.start(alice, back.access(alice).join());
        assertTrue(api.feeCalls.isEmpty(), "nothing charged during the warmup");
        advance(5_000);

        assertTrue(trip.join().isTeleported());
        assertEquals(1, api.feeCalls.size());
        assertTrue(api.feeCalls.get(0).startsWith("7:250:warps:back:"), api.feeCalls.get(0));
        verify(alice).sendMessage(org.mockito.ArgumentMatchers.contains("You paid 250 coins"));
    }

    @Test
    void aPaidBackThatCantBeAffordedIsRefused_AndKeepsThePlace() {
        BackService back = paidBack(250);
        back.setCharges(charges);
        grant(alice, TeleportNodes.BACK_WARPS, TeleportNodes.BYPASS_WARMUP, TeleportNodes.BYPASS_COOLDOWN);
        warpAwayFree(alice);
        api.next = TeleportChargeResult.refused("InsufficientCoins", "Not enough coins.");

        BackService.Trip trip = back.start(alice, back.access(alice).join()).join();

        assertEquals("fee", trip.code());
        assertTrue(trip.outcome().message().contains("250 coins"), trip.outcome().message());
        assertTrue(back.secondsLeft(alice.getUniqueId()) > 0, "a refused /back keeps the place");
    }

    @Test
    void anUnsafePaidBackIsRefunded() {
        BackService back = paidBack(250);
        back.setCharges(charges);
        grant(alice, TeleportNodes.BACK_WARPS, TeleportNodes.BYPASS_WARMUP, TeleportNodes.BYPASS_COOLDOWN);
        warpAwayFree(alice);
        api.next = TeleportChargeResult.allowed("Coins", 250, 750, false, null);
        safe = false;

        BackService.Trip trip = back.start(alice, back.access(alice).join()).join();

        assertEquals(TeleportDenial.UNSAFE, trip.code());
        assertEquals(1, api.refundKeys.size());
    }

    @Test
    void bypassCostMakesBackFree_AndAFreeBackAsksNothing() {
        BackService back = paidBack(250);
        back.setCharges(charges);
        grant(alice, TeleportNodes.BACK_WARPS, TeleportNodes.BYPASS_WARMUP, TeleportNodes.BYPASS_COOLDOWN,
            TeleportNodes.BYPASS_COST);
        warpAwayFree(alice);

        assertTrue(back.start(alice, back.access(alice).join()).join().isTeleported());
        assertTrue(api.feeCalls.isEmpty());

        BackService free = paidBack(0);
        free.setCharges(charges);
        grant(bob, TeleportNodes.BACK_WARPS, TeleportNodes.BYPASS_WARMUP, TeleportNodes.BYPASS_COOLDOWN);
        warpAwayFree(bob);
        assertTrue(free.start(bob, free.access(bob).join()).join().isTeleported());
        assertTrue(api.feeCalls.isEmpty());
    }

    @Test
    void aPaidBackWithoutTheApiIsRefused() {
        BackService back = paidBack(250);
        grant(alice, TeleportNodes.BACK_WARPS, TeleportNodes.BYPASS_WARMUP, TeleportNodes.BYPASS_COOLDOWN);
        warpAwayFree(alice);

        assertEquals(BackService.FEE_UNAVAILABLE, back.start(alice, back.access(alice).join()).join().code());
    }

    @Test
    void staffBackIsNeverCharged() {
        BackService back = paidBack(250);
        back.setCharges(charges);
        grant(alice, TeleportNodes.BYPASS_WARMUP, TeleportNodes.BYPASS_COOLDOWN);
        warpAwayFree(alice);

        assertTrue(back.startFor(bob, alice, false).join().isTeleported());
        assertTrue(api.feeCalls.isEmpty());
    }
}
