package net.knightsandkings.knk.paper.minigame;

import net.knightsandkings.knk.core.minigame.MinigameRegistry;
import net.knightsandkings.knk.core.siege.SiegePhase;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportKind;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.core.teleport.TeleportSettings;
import net.knightsandkings.knk.core.util.BlockProbe;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.SiegeParticipation;
import net.knightsandkings.knk.paper.siege.SiegeService;
import net.knightsandkings.knk.paper.siege.SiegeTeleportRestriction;
import net.knightsandkings.knk.paper.teleport.TeleportPlan;
import net.knightsandkings.knk.paper.teleport.TeleportService;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Arena duels Phase 0 (KNG-209): the registry's teleport guard, registered in a real {@link TeleportService} before
 * siege joins the registry, refuses exactly what the siege guard did, and gives no {@code /back} after a match death.
 */
class MinigameTeleportRestrictionTest {

    private long now = 1_000_000;
    private final Map<UUID, Set<String>> granted = new HashMap<>();
    private final Set<UUID> members = new HashSet<>();
    // Location only holds its World through a WeakReference: keep the mock strongly reachable.
    private final World world = world();
    private final BlockProbe flat = new BlockProbe() {
        @Override public boolean isPassable(int x, int y, int z) { return y >= 64; }
        @Override public boolean isSolid(int x, int y, int z) { return y < 64; }
        @Override public boolean isHazard(int x, int y, int z) { return false; }
        @Override public int minY() { return -64; }
        @Override public int maxY() { return 320; }
    };
    private final TeleportService.PermissionLookup permissions = (player, node) ->
        CompletableFuture.completedFuture(granted.getOrDefault(player.getUniqueId(), Set.of()).contains(node));
    private final TeleportService engine = new TeleportService(Runnable::run, permissions,
        TeleportSettings.defaults(), () -> now, w -> flat);
    private final MinigameRegistry minigames = new MinigameRegistry();
    private final MinigameTeleportRestriction restriction = new MinigameTeleportRestriction(minigames);
    private final SiegeService siege = mock(SiegeService.class);

    private final Player alice = player("Alice", new Location(world, 0.5, 64, 0.5));
    private final Player bob = player("Bob", new Location(world, 100.5, 64, 100.5));
    private final Location spawn = new Location(world, 50.5, 64, 50.5);

    MinigameTeleportRestrictionTest() {
        engine.registerRestriction(restriction);
        SiegeLobbyRuntime hub = mock(SiegeLobbyRuntime.class);
        when(hub.phase()).thenReturn(SiegePhase.HUB);
        when(hub.match()).thenReturn(Optional.empty());
        when(siege.activeLobbyOf(any(UUID.class))).thenAnswer(inv ->
            members.contains(inv.<UUID>getArgument(0)) ? Optional.of(hub) : Optional.empty());
        when(siege.lobbyOf(any(UUID.class))).thenAnswer(inv ->
            members.contains(inv.<UUID>getArgument(0)) ? Optional.of(hub) : Optional.empty());
    }

    private World world() {
        World mocked = mock(World.class);
        when(mocked.getName()).thenReturn("world");
        when(mocked.getUID()).thenReturn(UUID.nameUUIDFromBytes("world".getBytes()));
        when(mocked.getChunkAtAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(mock(Chunk.class)));
        return mocked;
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

    private static TeleportOutcome done(CompletableFuture<TeleportOutcome> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new AssertionError("teleport outcome not completed", ex);
        }
    }

    private TeleportOutcome finish(CompletableFuture<TeleportOutcome> result) {
        now += 5_000;
        engine.tick(id -> false);
        return done(result);
    }

    private static void assertSiegeDenial(String message, TeleportOutcome outcome) {
        assertEquals(TeleportOutcome.Status.DENIED, outcome.status());
        assertEquals(TeleportDenial.SIEGE, outcome.code());
        assertEquals(message, outcome.message());
    }

    @Test
    void withNoMinigameRegisteredNothingIsRefused() {
        members.add(alice.getUniqueId());
        assertTrue(restriction.bypassNodes().isEmpty());
        assertTrue(finish(engine.start(TeleportPlan.spawn(alice, spawn, "spawn"))).isTeleported());
        assertFalse(restriction.backDeathExclusion().excludes(alice));
    }

    @Test
    void siegeRegisteredLaterRefusesWhatTheSiegeGuardRefused() {
        minigames.register(new SiegeParticipation(siege));
        members.add(alice.getUniqueId());

        assertSiegeDenial("You can't teleport during a siege. Use /siege leave to leave it.",
            done(engine.start(TeleportPlan.spawn(alice, spawn, "spawn"))));
        assertSiegeDenial("Alice is in a siege match.",
            done(engine.start(new TeleportPlan(bob, alice::getLocation, TeleportKind.REQUEST, bob, alice, false, "Alice"))));
        assertSiegeDenial("Alice is in a siege match.",
            done(engine.start(new TeleportPlan(alice, bob::getLocation, TeleportKind.REQUEST, bob, bob, false, "Bob"))));
        assertSiegeDenial("Alice is in a siege match; use /siege admin kick first.",
            done(engine.start(TeleportPlan.staffToLocation(bob, alice, spawn, "spawn"))));
        assertTrue(done(engine.start(TeleportPlan.staffToPlayer(bob, bob, alice, false))).isTeleported(),
            "staff may watch a member");
        assertTrue(restriction.backDeathExclusion().excludes(alice));
        assertFalse(restriction.backDeathExclusion().excludes(bob));
    }

    @Test
    void theSiegeBypassIsDeclaredAndLetsAMemberMoveThemselves() {
        minigames.register(new SiegeParticipation(siege));
        members.add(alice.getUniqueId());
        assertEquals(Set.of(SiegeTeleportRestriction.BYPASS_NODE), restriction.bypassNodes());

        granted.put(alice.getUniqueId(), Set.of(SiegeTeleportRestriction.BYPASS_NODE));
        assertTrue(finish(engine.start(TeleportPlan.spawn(alice, spawn, "spawn"))).isTeleported());
    }
}
