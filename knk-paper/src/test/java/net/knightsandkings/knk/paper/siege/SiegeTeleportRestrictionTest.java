package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.util.BlockProbe;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportKind;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.core.teleport.TeleportSettings;
import net.knightsandkings.knk.paper.listeners.BackDeathListener;
import net.knightsandkings.knk.paper.teleport.BackService;
import net.knightsandkings.knk.paper.teleport.TeleportCheck;
import net.knightsandkings.knk.paper.teleport.TeleportNodes;
import net.knightsandkings.knk.paper.teleport.TeleportPlan;
import net.knightsandkings.knk.paper.teleport.TeleportService;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The siege guards of the teleport engine (docs/specs/teleport/DESIGN.md §3.4, §4 D8/D9, Q5):
 * {@link SiegeTeleportRestriction} on its own and registered in a real {@link TeleportService}, its
 * {@code /back} death exclusion through {@link BackService}, and the siege's own PLUGIN-cause teleports.
 */
class SiegeTeleportRestrictionTest {

    private long now = 1_000_000;
    private final Set<UUID> members = new HashSet<>();
    private final Map<UUID, Set<String>> granted = new HashMap<>();
    private final UUID worldId = UUID.nameUUIDFromBytes("world".getBytes());
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
    private final SiegeTeleportRestriction restriction = new SiegeTeleportRestriction(members::contains);

    private final Player alice = player("Alice", new Location(world, 0.5, 64, 0.5));
    private final Player bob = player("Bob", new Location(world, 100.5, 64, 100.5));
    private final Location spawn = new Location(world, 50.5, 64, 50.5);

    SiegeTeleportRestrictionTest() {
        engine.registerRestriction(restriction);
    }

    private World world() {
        World mocked = mock(World.class);
        when(mocked.getName()).thenReturn("world");
        when(mocked.getUID()).thenReturn(worldId);
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

    private void grant(Player player, String... nodes) {
        granted.computeIfAbsent(player.getUniqueId(), id -> new HashSet<>()).addAll(Set.of(nodes));
    }

    private void joinSiege(Player player) {
        members.add(player.getUniqueId());
    }

    private static TeleportOutcome done(CompletableFuture<TeleportOutcome> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new AssertionError("teleport outcome not completed", ex);
        }
    }

    private void advance(long millis) {
        now += millis;
        engine.tick(id -> false);
    }

    private TeleportOutcome finish(CompletableFuture<TeleportOutcome> result) {
        advance(5_000);
        return done(result);
    }

    private static void assertSiegeDenial(String message, TeleportOutcome outcome) {
        assertEquals(TeleportOutcome.Status.DENIED, outcome.status());
        assertEquals(TeleportDenial.SIEGE, outcome.code());
        assertEquals(message, outcome.message());
    }

    // ===== a member's own teleports =====

    @Test
    void membersOwnSpawnWarpAndBackAreRefused() {
        joinSiege(alice);

        TeleportOutcome spawnOutcome = done(engine.start(TeleportPlan.spawn(alice, spawn, "spawn")));
        // The teleport menu's warp tiles run WarpCommand.warpTo, i.e. this plan - /menu passing the siege
        // command filter doesn't let a warp through.
        TeleportOutcome warpOutcome = done(engine.start(TeleportPlan.warp(alice, spawn, "Kardenna", null)));
        TeleportOutcome backOutcome = done(engine.start(TeleportPlan.back(alice, () -> spawn, "death")));

        String message = "You can't teleport during a siege. Use /siege leave to leave it.";
        assertSiegeDenial(message, spawnOutcome);
        assertSiegeDenial(message, warpOutcome);
        assertSiegeDenial(message, backOutcome);
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
    }

    @Test
    void aMemberHoldingTheSiegeBypassMayTeleportThemselves() {
        joinSiege(alice);
        grant(alice, SiegeTeleportRestriction.BYPASS_NODE);

        assertTrue(finish(engine.start(TeleportPlan.spawn(alice, spawn, "spawn"))).isTeleported());
        verify(alice).teleportAsync(eq(spawn), eq(TeleportCause.COMMAND));
    }

    @Test
    void joiningASiegeMidWarmupIsRefusedAtCommit() {
        CompletableFuture<TeleportOutcome> result = engine.start(TeleportPlan.spawn(alice, spawn, "spawn"));

        joinSiege(alice); // the lobby reached HUB during the warmup
        TeleportOutcome outcome = finish(result);

        assertEquals(TeleportOutcome.Status.DENIED, outcome.status());
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
    }

    @Test
    void leavingTheSiegeLiftsTheGuard() {
        joinSiege(alice);
        assertEquals(TeleportOutcome.Status.DENIED, done(engine.start(TeleportPlan.spawn(alice, spawn, "spawn"))).status());

        members.remove(alice.getUniqueId());

        assertTrue(finish(engine.start(TeleportPlan.spawn(alice, spawn, "spawn"))).isTeleported());
    }

    // ===== requests =====

    @Test
    void aRequestToAMemberIsRefused() {
        joinSiege(alice);
        // /tpa Alice: Bob would move to Alice.
        TeleportPlan tpa = new TeleportPlan(bob, alice::getLocation, TeleportKind.REQUEST, bob, alice, false, "Alice");

        assertSiegeDenial("Alice is in a siege match.", done(engine.start(tpa)));
        verify(bob, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
    }

    @Test
    void aTpahereThatWouldPullAMemberOutIsRefused() {
        joinSiege(alice);
        // /tpahere Alice by Bob: Alice would move to Bob.
        TeleportPlan tpahere = new TeleportPlan(alice, bob::getLocation, TeleportKind.REQUEST, bob, bob, false, "Bob");

        assertSiegeDenial("Alice is in a siege match.", done(engine.start(tpahere)));
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
    }

    // ===== staff =====

    @Test
    void staffCantMoveAMember_EvenWithTheSiegeBypass() {
        joinSiege(alice);
        grant(bob, SiegeTeleportRestriction.BYPASS_NODE, TeleportNodes.REGION_BYPASS);

        TeleportOutcome tphere = done(engine.start(TeleportPlan.staffToPlayer(bob, alice, bob, false)));
        TeleportOutcome sendToSpawn = done(engine.start(TeleportPlan.staffToLocation(bob, alice, spawn, "spawn")));

        String message = "Alice is in a siege match; use /siege admin kick first.";
        assertSiegeDenial(message, tphere);
        assertSiegeDenial(message, sendToSpawn);
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
    }

    @Test
    void theConsoleCantMoveAMemberEither() {
        joinSiege(alice);
        CommandSender console = mock(CommandSender.class);

        TeleportOutcome outcome = done(engine.start(TeleportPlan.staffToLocation(console, alice, spawn, "spawn")));

        assertSiegeDenial("Alice is in a siege match; use /siege admin kick first.", outcome);
    }

    @Test
    void staffMayTeleportToAMemberToWatch() {
        joinSiege(alice);

        assertTrue(done(engine.start(TeleportPlan.staffToPlayer(bob, bob, alice, false))).isTeleported());
    }

    @Test
    void aStaffMemberInASiegeNeedsTheBypassToTeleportThemselves() {
        joinSiege(bob);
        TeleportOutcome without = done(engine.start(TeleportPlan.staffToPlayer(bob, bob, alice, false)));
        grant(bob, SiegeTeleportRestriction.BYPASS_NODE);
        TeleportOutcome with = done(engine.start(TeleportPlan.staffToPlayer(bob, bob, alice, false)));

        assertSiegeDenial("You can't teleport during a siege. Use /siege leave to leave it.", without);
        assertTrue(with.isTeleported());
    }

    // ===== the restriction on its own =====

    @Test
    void nonMembersAreNotRestricted_AndTheBypassNodeIsDeclared() {
        TeleportCheck check = new TeleportCheck(alice, alice.getLocation(), spawn, TeleportKind.WARP, alice, null, node -> false);

        assertEquals(Optional.empty(), restriction.deny(check));
        assertEquals(Set.of("knk.siege.bypass.commands"), restriction.bypassNodes());
    }

    // ===== /back =====

    @Test
    void aSiegeDeathGivesNoBack_ButALaterOrdinaryDeathDoes() {
        BackService back = new BackService(engine, Runnable::run, permissions, id -> id.equals(worldId) ? world : null);
        back.registerDeathExclusion(restriction.backDeathExclusion());
        BackDeathListener deaths = new BackDeathListener(back);
        grant(alice, TeleportNodes.BACK);
        Location matchDeath = new Location(world, 10.5, 64, 10.5);
        Location cave = new Location(world, 30.5, 64, 30.5);

        joinSiege(alice);
        die(deaths, alice, matchDeath);
        assertTrue(restriction.backDeathExclusion().excludes(alice));
        members.remove(alice.getUniqueId());
        assertEquals(BackService.NOWHERE, done(back.start(alice, back.access(alice).join()).thenApply(BackService.Trip::outcome)).code());

        assertFalse(restriction.backDeathExclusion().excludes(alice));
        die(deaths, alice, cave);
        assertTrue(finish(back.start(alice, back.access(alice).join()).thenApply(BackService.Trip::outcome)).isTeleported());
        verify(alice).teleportAsync(eq(cave), eq(TeleportCause.COMMAND));
    }

    @Test
    void aPlaceFromBeforeTheSiegeIsUsableAfterIt_ButNotDuringIt() {
        BackService back = new BackService(engine, Runnable::run, permissions, id -> id.equals(worldId) ? world : null);
        back.registerDeathExclusion(restriction.backDeathExclusion());
        BackDeathListener deaths = new BackDeathListener(back);
        grant(alice, TeleportNodes.BACK_ALL, TeleportNodes.BYPASS_COOLDOWN);
        Location field = alice.getLocation();
        Location town = new Location(world, 700.5, 64, 700.5);
        Location hub = new Location(world, 500.5, 64, 500.5);
        when(alice.teleport(any(Location.class), any(TeleportCause.class))).thenReturn(true);

        assertTrue(finish(engine.start(TeleportPlan.warp(alice, town, "Town", null))).isTeleported());
        when(alice.getLocation()).thenReturn(town);
        joinSiege(alice);
        SiegeBukkit.teleport(alice, hub);           // the siege's own teleport: never recorded
        when(alice.getLocation()).thenReturn(hub);
        die(deaths, alice, new Location(world, 10.5, 64, 10.5)); // a match death: never recorded

        TeleportOutcome during = done(back.start(alice, back.access(alice).join()).thenApply(BackService.Trip::outcome));
        assertEquals(TeleportDenial.SIEGE, during.code());

        members.remove(alice.getUniqueId());
        SiegeBukkit.teleport(alice, town);          // returned after the match
        when(alice.getLocation()).thenReturn(town);
        TeleportOutcome after = finish(back.start(alice, back.access(alice).join()).thenApply(BackService.Trip::outcome));
        assertTrue(after.isTeleported());
        verify(alice).teleportAsync(eq(field), eq(TeleportCause.COMMAND));
    }

    @Test
    void staffBackOfAMemberWithNothingRecordedSaysTheyAreInASiege() {
        BackService back = new BackService(engine, Runnable::run, permissions, id -> id.equals(worldId) ? world : null);
        joinSiege(alice);

        TeleportOutcome outcome = done(back.startFor(bob, alice, false).thenApply(BackService.Trip::outcome));
        assertSiegeDenial("Alice is in a siege match; use /siege admin kick first.", outcome);

        members.remove(alice.getUniqueId());
        assertEquals(BackService.NOWHERE, done(back.startFor(bob, alice, false).thenApply(BackService.Trip::outcome)).code());
    }

    private void die(BackDeathListener deaths, Player player, Location where) {
        when(player.getLocation()).thenReturn(where);
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(player);
        deaths.onDeathEarly(event);
        deaths.onDeath(event);
        when(player.getLocation()).thenReturn(new Location(world, 0.5, 64, 300.5));
    }

    // ===== the siege's own teleports =====

    @Test
    void siegeTeleportsUseThePluginCause_OutsideTheEngine() {
        joinSiege(alice);
        Location hub = new Location(world, 500.5, 64, 500.5);
        when(alice.teleport(any(Location.class), any(TeleportCause.class))).thenReturn(true);

        assertTrue(SiegeBukkit.teleport(alice, hub));

        verify(alice).teleport(hub, TeleportCause.PLUGIN);
        verify(alice, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
    }
}
