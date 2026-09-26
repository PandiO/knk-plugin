package net.knightsandkings.knk.paper.teleport;

import net.knightsandkings.knk.core.teleport.BlockProbe;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportKind;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.core.teleport.TeleportSettings;
import net.knightsandkings.knk.core.teleport.WarmupCancelReason;
import net.knightsandkings.knk.paper.listeners.CombatTagListener;
import net.knightsandkings.knk.paper.listeners.TeleportWarmupListener;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The teleport engine's state machine (docs/specs/teleport/DESIGN.md §3.4, Phase 1): guards, warmup
 * and its cancellation, re-check at commit, cooldown, combat tag, COMMAND cause, bypass authority.
 */
class TeleportServiceTest {

    private long now = 1_000_000;
    private final Map<UUID, Set<String>> granted = new HashMap<>();
    private final World world = world();
    /** Flat world: solid below y 64, air from 64 up. */
    private final BlockProbe flat = new BlockProbe() {
        @Override public boolean isPassable(int x, int y, int z) { return y >= 64; }
        @Override public boolean isSolid(int x, int y, int z) { return y < 64; }
        @Override public boolean isHazard(int x, int y, int z) { return false; }
        @Override public int minY() { return -64; }
        @Override public int maxY() { return 320; }
    };
    private final TeleportService service = new TeleportService(Runnable::run,
        (player, node) -> CompletableFuture.completedFuture(granted.getOrDefault(player.getUniqueId(), Set.of()).contains(node)),
        TeleportSettings.defaults(), () -> now, w -> flat);
    private final TeleportWarmupListener warmupListener = new TeleportWarmupListener(service);

    private final Player alice = player("Alice", new Location(world, 0.5, 64, 0.5));
    private final Player bob = player("Bob", new Location(world, 100.5, 64, 100.5));
    private final Location spawn = new Location(world, 50.5, 64, 50.5);

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

    private void grant(Player player, String... nodes) {
        granted.computeIfAbsent(player.getUniqueId(), id -> new HashSet<>()).addAll(Set.of(nodes));
    }

    private TeleportPlan spawnPlan(Player subject) {
        return new TeleportPlan(subject, () -> spawn, TeleportKind.SPAWN, subject, null, false, "spawn");
    }

    /** The outcome, failing (not hanging) when the engine never completes it. */
    private static TeleportOutcome done(CompletableFuture<TeleportOutcome> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new AssertionError("teleport outcome not completed", ex);
        }
    }

    private void advance(long millis) {
        now += millis;
        service.tick(id -> false);
    }

    // ===== staff teleports =====

    @Test
    void staffTeleportIsInstantAndUsesTheCommandCause() {
        Location bobsSpot = bob.getLocation();
        CompletableFuture<TeleportOutcome> result = service.start(TeleportPlan.staffToPlayer(alice, alice, bob, false));

        assertTrue(done(result).isTeleported());
        verify(alice).teleportAsync(eq(bobsSpot), eq(TeleportCause.COMMAND));
    }

    @Test
    void staffTeleportIgnoresCombatTagAndCooldown() {
        service.combatTags().tag(alice.getUniqueId(), now);

        assertTrue(done(service.start(TeleportPlan.staffToPlayer(alice, alice, bob, false))).isTeleported());
        assertTrue(done(service.start(TeleportPlan.staffToPlayer(alice, alice, bob, false))).isTeleported());
    }

    @Test
    void staffTeleportToAPlayerWhoWentOfflineFails() {
        when(bob.isOnline()).thenReturn(false);

        TeleportOutcome outcome = done(service.start(TeleportPlan.staffToPlayer(alice, alice, bob, false)));

        assertEquals(TeleportOutcome.Status.FAILED, outcome.status());
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
    }

    @Test
    void teleportBlockedByAnotherListenerIsReportedAsFailed() {
        when(alice.teleportAsync(any(Location.class), any(TeleportCause.class))).thenReturn(CompletableFuture.completedFuture(false));

        TeleportOutcome outcome = done(service.start(TeleportPlan.staffToPlayer(alice, alice, bob, false)));

        assertEquals(TeleportOutcome.Status.FAILED, outcome.status());
    }

    // ===== restrictions =====

    @Test
    void restrictionRefusesBeforeAnythingHappens() {
        service.registerRestriction(check -> Optional.of(TeleportDenial.of(TeleportDenial.SIEGE, "Alice is in a siege match.")));

        TeleportOutcome outcome = done(service.start(TeleportPlan.staffToPlayer(bob, alice, bob, false)));

        assertEquals(TeleportOutcome.Status.DENIED, outcome.status());
        assertEquals("Alice is in a siege match.", outcome.message());
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
    }

    @Test
    void brokenRestrictionFailsClosed() {
        service.registerRestriction(check -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(TeleportOutcome.Status.DENIED, done(service.start(spawnPlan(alice))).status());
    }

    @Test
    void staffTeleportBypassesAreTheActorsNotTheSubjects() {
        CommandSender staff = bob;
        grant(bob, TeleportNodes.REGION_BYPASS);
        service.registerRestriction(new TeleportRestriction() {
            @Override
            public Optional<TeleportDenial> deny(TeleportCheck check) {
                return check.hasBypass(TeleportNodes.REGION_BYPASS) ? Optional.empty()
                    : Optional.of(TeleportDenial.of(TeleportDenial.REGION, "You are not allowed to enter Jail."));
            }

            @Override
            public Set<String> bypassNodes() {
                return Set.of(TeleportNodes.REGION_BYPASS);
            }
        });

        // Bob (holds the bypass) sends Alice (doesn't) - allowed.
        assertTrue(done(service.start(TeleportPlan.staffToLocation(staff, alice, spawn, "jail"))).isTeleported());
        // Alice's own player teleport - refused, her permissions count.
        assertEquals(TeleportOutcome.Status.DENIED, done(service.start(spawnPlan(alice))).status());
    }

    @Test
    void inFlightBypassIsVisibleOnlyWhileTheTeleportIsHappening() {
        grant(bob, TeleportNodes.REGION_BYPASS);
        service.registerRestriction(new TeleportRestriction() {
            @Override
            public Optional<TeleportDenial> deny(TeleportCheck check) {
                return Optional.empty();
            }

            @Override
            public Set<String> bypassNodes() {
                return Set.of(TeleportNodes.REGION_BYPASS);
            }
        });
        CompletableFuture<Boolean> pending = new CompletableFuture<>();
        when(alice.teleportAsync(any(Location.class), any(TeleportCause.class))).thenReturn(pending);

        CompletableFuture<TeleportOutcome> result = service.start(TeleportPlan.staffToPlayer(bob, alice, bob, false));

        assertTrue(service.hasInFlightBypass(alice.getUniqueId(), TeleportNodes.REGION_BYPASS));
        pending.complete(true);
        assertTrue(done(result).isTeleported());
        assertFalse(service.hasInFlightBypass(alice.getUniqueId(), TeleportNodes.REGION_BYPASS));
    }

    // ===== warmup =====

    @Test
    void playerTeleportWaitsForTheWarmup() {
        CompletableFuture<TeleportOutcome> result = service.start(spawnPlan(alice));

        assertTrue(service.isWarmingUp(alice.getUniqueId()));
        advance(4_900);
        assertFalse(result.isDone());
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));

        advance(100);
        assertTrue(done(result).isTeleported());
        verify(alice).teleportAsync(eq(spawn), eq(TeleportCause.COMMAND));
    }

    @Test
    void shortWarmupNodeWaitsThreeSeconds() {
        grant(alice, TeleportNodes.WARMUP_SHORT);
        CompletableFuture<TeleportOutcome> result = service.start(spawnPlan(alice));

        advance(3_000);

        assertTrue(done(result).isTeleported());
    }

    @Test
    void warmupBypassTeleportsImmediately() {
        grant(alice, TeleportNodes.BYPASS_WARMUP);

        assertTrue(done(service.start(spawnPlan(alice))).isTeleported());
    }

    @Test
    void movingToAnotherBlockCancelsTheWarmup() {
        CompletableFuture<TeleportOutcome> result = service.start(spawnPlan(alice));

        warmupListener.onMove(new PlayerMoveEvent(alice, new Location(world, 0.5, 64, 0.5), new Location(world, 1.5, 64, 0.5)));

        assertEquals(TeleportOutcome.Status.CANCELLED, done(result).status());
        assertEquals(WarmupCancelReason.MOVED.name(), done(result).code());
        advance(10_000);
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
    }

    @Test
    void lookingAroundDoesNotCancelTheWarmup() {
        CompletableFuture<TeleportOutcome> result = service.start(spawnPlan(alice));

        warmupListener.onMove(new PlayerMoveEvent(alice,
            new Location(world, 0.2, 64, 0.2, 0, 0), new Location(world, 0.8, 64, 0.7, 170, 30)));

        assertTrue(service.isWarmingUp(alice.getUniqueId()));
        advance(5_000);
        assertTrue(done(result).isTeleported());
    }

    @Test
    void takingDamageCancelsTheWarmup() {
        CompletableFuture<TeleportOutcome> result = service.start(spawnPlan(alice));
        EntityDamageEvent damage = mock(EntityDamageEvent.class);
        when(damage.getEntity()).thenReturn(alice);

        warmupListener.onDamage(damage);

        assertEquals(WarmupCancelReason.DAMAGED.name(), done(result).code());
    }

    @Test
    void aNewTeleportReplacesTheRunningWarmup() {
        CompletableFuture<TeleportOutcome> first = service.start(spawnPlan(alice));
        CompletableFuture<TeleportOutcome> second = service.start(spawnPlan(alice));

        assertEquals(WarmupCancelReason.REPLACED.name(), done(first).code());
        advance(5_000);
        assertTrue(done(second).isTeleported());
    }

    @Test
    void guardsAreCheckedAgainAtCommit() {
        AtomicBoolean joinedSiege = new AtomicBoolean(false);
        service.registerRestriction(check -> joinedSiege.get()
            ? Optional.of(TeleportDenial.of(TeleportDenial.SIEGE, "You can't teleport during a siege match."))
            : Optional.empty());
        CompletableFuture<TeleportOutcome> result = service.start(spawnPlan(alice));

        joinedSiege.set(true);
        advance(5_000);

        assertEquals(TeleportOutcome.Status.DENIED, done(result).status());
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
    }

    @Test
    void beingFrozenMidWarmupCancelsIt() {
        service.registerRestriction(new FreezeTeleportRestriction(id -> false));
        CompletableFuture<TeleportOutcome> result = service.start(spawnPlan(alice));

        now += 1_000;
        service.tick(id -> id.equals(alice.getUniqueId()));

        assertEquals(WarmupCancelReason.FROZEN.name(), done(result).code());
    }

    @Test
    void frozenPlayerCanNotStartAPlayerTeleportButStaffCanMoveThem() {
        service.registerRestriction(new FreezeTeleportRestriction(id -> id.equals(alice.getUniqueId())));

        assertEquals(TeleportDenial.FROZEN, done(service.start(spawnPlan(alice))).code());
        assertTrue(done(service.start(TeleportPlan.staffToLocation(bob, alice, spawn, "jail"))).isTeleported());
    }

    // ===== cooldown, combat tag, safety =====

    @Test
    void cooldownStartsAfterAPlayerTeleport() {
        grant(alice, TeleportNodes.BYPASS_WARMUP);
        assertTrue(done(service.start(spawnPlan(alice))).isTeleported());

        TeleportOutcome again = done(service.start(spawnPlan(alice)));

        assertEquals(TeleportDenial.COOLDOWN, again.code());
        assertEquals("You can teleport again in 30 s.", again.message());
        now += 30_000;
        assertTrue(done(service.start(spawnPlan(alice))).isTeleported());
    }

    @Test
    void combatTaggedPlayerCanNotTeleport() {
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(bob);
        EntityDamageByEntityEvent hit = mock(EntityDamageByEntityEvent.class);
        when(hit.getEntity()).thenReturn(alice);
        when(hit.getDamager()).thenReturn(arrow);
        new CombatTagListener(service).onDamage(hit);
        now += 3_000;

        TeleportOutcome outcome = done(service.start(spawnPlan(alice)));

        assertEquals(TeleportDenial.COMBAT, outcome.code());
        assertEquals("You were in combat 3 s ago; wait 7 s.", outcome.message());
        assertEquals(TeleportDenial.COMBAT, done(service.start(spawnPlan(bob))).code(), "the shooter is tagged too");
        grant(alice, TeleportNodes.BYPASS_COMBAT);
        service.start(spawnPlan(alice));
        assertTrue(service.isWarmingUp(alice.getUniqueId()), "the combat bypass lets the warmup start");
    }

    @Test
    void unsafeDestinationIsRefusedForPlayerTeleports() {
        TeleportService lava = new TeleportService(Runnable::run, (player, node) -> CompletableFuture.completedFuture(false),
            TeleportSettings.defaults(), () -> now, w -> new BlockProbe() {
                @Override public boolean isPassable(int x, int y, int z) { return true; }
                @Override public boolean isSolid(int x, int y, int z) { return false; }
                @Override public boolean isHazard(int x, int y, int z) { return true; }
                @Override public int minY() { return -64; }
                @Override public int maxY() { return 320; }
            });
        CompletableFuture<TeleportOutcome> result = lava.start(spawnPlan(alice));

        now += 5_000;
        lava.tick(id -> false);

        assertEquals(TeleportDenial.UNSAFE, done(result).code());
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
    }

    @Test
    void unsafeDestinationNearbyIsMovedToASafeSpot() {
        Location inTheAir = new Location(world, 50.5, 66, 50.5, 90, 0);
        CompletableFuture<TeleportOutcome> result = service.start(
            new TeleportPlan(alice, () -> inTheAir, TeleportKind.WARP, alice, null, false, "tower"));

        advance(5_000);

        assertTrue(done(result).isTeleported());
        verify(alice).teleportAsync(eq(new Location(world, 50.5, 64, 50.5, 90, 0)), eq(TeleportCause.COMMAND));
    }
}
