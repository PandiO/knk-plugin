package net.knightsandkings.knk.paper.teleport;

import net.knightsandkings.knk.core.teleport.BlockProbe;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportRequestBook.Direction;
import net.knightsandkings.knk.core.teleport.TeleportRequestSettings;
import net.knightsandkings.knk.core.teleport.TeleportSettings;
import net.knightsandkings.knk.core.teleport.WarmupCancelReason;
import net.knightsandkings.knk.paper.commands.TeleportRequestCommand;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.listeners.TeleportWarmupListener;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /tpa, /tpahere and their answers (docs/specs/teleport/DESIGN.md §3.5, Phase 3), run against the
 * real teleport engine: warmup on the moving player, guards re-run for both players at accept, live
 * destination, vanish safety, expiry, cooldown, price refusal.
 */
class TeleportRequestServiceTest {

    private long now = 1_000_000;
    private final Map<UUID, Set<String>> granted = new HashMap<>();
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

    private final Player alice = player("Alice", new Location(world, 0.5, 64, 0.5));
    private final Player bob = player("Bob", new Location(world, 100.5, 64, 100.5));
    private final Player carol = player("Carol", new Location(world, -50.5, 64, 20.5));
    private final Player staff = player("Staff", new Location(world, 10.5, 64, 10.5));
    private final List<Player> everyone = List.of(alice, bob, carol, staff);
    private final Set<Player> vanished = new HashSet<>();
    private final VisibleTargetResolver targets = new VisibleTargetResolver(this::byName, () -> everyone, vanished::contains);
    private final TeleportRequestService requests = new TeleportRequestService(engine, Runnable::run, permissions,
        targets, this::byId);

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

    private Player byName(String name) {
        return everyone.stream().filter(p -> p.getName().equalsIgnoreCase(name) && p.isOnline()).findFirst().orElse(null);
    }

    private Player byId(UUID id) {
        return everyone.stream().filter(p -> p.getUniqueId().equals(id) && p.isOnline()).findFirst().orElse(null);
    }

    private void grant(Player player, String... nodes) {
        granted.computeIfAbsent(player.getUniqueId(), id -> new HashSet<>()).addAll(Set.of(nodes));
    }

    private void advance(long millis) {
        now += millis;
        engine.tick(id -> false);
        requests.tick();
    }

    private void withRequestSettings(TeleportRequestSettings request) {
        TeleportSettings d = TeleportSettings.defaults();
        engine.updateSettings(new TeleportSettings(d.warmupSeconds(), d.warmupShortSeconds(), d.cooldownSeconds(),
            d.combatTagSeconds(), d.safeSearchRadius(), request));
    }

    private void neverTeleported(Player player) {
        verify(player, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
    }

    private static List<String> clickCommands(Component component) {
        List<String> commands = new ArrayList<>();
        if (component.clickEvent() != null) {
            commands.add(component.clickEvent().value());
        }
        for (Component child : component.children()) {
            commands.addAll(clickCommands(child));
        }
        return commands;
    }

    // ===== /tpa =====

    @Test
    void tpaAskTheTargetWithClickableAcceptAndDeny() {
        requests.send(alice, bob, Direction.TO_TARGET);

        ArgumentCaptor<Component> notice = ArgumentCaptor.forClass(Component.class);
        verify(bob).sendMessage(notice.capture());
        assertEquals(List.of("/tpaccept Alice", "/tpdeny Alice"), clickCommands(notice.getValue()));
        verify(alice).sendMessage(contains("Request sent to Bob. It expires in 30 s."));
        neverTeleported(alice);
    }

    @Test
    void acceptedTpaWarmsUpTheRequesterThenTakesThemToTheTarget() {
        Location bobsSpot = bob.getLocation();
        requests.send(alice, bob, Direction.TO_TARGET);

        requests.accept(bob, null);

        assertTrue(engine.isWarmingUp(alice.getUniqueId()), "the warmup is on the requester");
        neverTeleported(alice);
        advance(5_000);
        verify(alice).teleportAsync(eq(bobsSpot), eq(TeleportCause.COMMAND));
        verify(bob, never()).teleportAsync(any(Location.class), any(TeleportCause.class));
        verify(alice).sendMessage(contains("Teleported to Bob."));
        verify(bob).sendMessage(contains("Alice teleported to you."));
    }

    @Test
    void acceptedTpahereWarmsUpTheTargetThenBringsThemToTheRequester() {
        Location alicesSpot = alice.getLocation();
        requests.send(alice, bob, Direction.TO_REQUESTER);

        requests.accept(bob, "Alice");

        assertTrue(engine.isWarmingUp(bob.getUniqueId()), "the warmup is on the target");
        advance(5_000);
        verify(bob).teleportAsync(eq(alicesSpot), eq(TeleportCause.COMMAND));
        neverTeleported(alice);
    }

    @Test
    void destinationIsWhereTheOtherPlayerIsWhenTheWarmupEnds() {
        requests.send(alice, bob, Direction.TO_TARGET);
        requests.accept(bob, null);
        Location bobMovedTo = new Location(world, 300.5, 64, -40.5);
        when(bob.getLocation()).thenReturn(bobMovedTo);

        advance(5_000);

        verify(alice).teleportAsync(eq(bobMovedTo), eq(TeleportCause.COMMAND));
    }

    @Test
    void secondAcceptFindsNothing() {
        requests.send(alice, bob, Direction.TO_TARGET);
        requests.accept(bob, null);

        requests.accept(bob, null);
        advance(5_000);

        verify(alice, times(1)).teleportAsync(any(Location.class), any(TeleportCause.class));
        verify(bob).sendMessage(contains("You have no pending teleport requests."));
    }

    // ===== guards =====

    @Test
    void acceptReRunsTheGuardsForBothPlayers() {
        Set<UUID> inSiege = new HashSet<>();
        engine.registerRestriction(check -> {
            if (inSiege.contains(check.subject().getUniqueId())) {
                return Optional.of(TeleportDenial.of(TeleportDenial.SIEGE, "You're in a siege match."));
            }
            if (check.visited() != null && inSiege.contains(check.visited().getUniqueId())) {
                return Optional.of(TeleportDenial.of(TeleportDenial.SIEGE, check.visited().getName() + " is in a siege match."));
            }
            return Optional.empty();
        });
        requests.send(alice, bob, Direction.TO_TARGET);
        inSiege.add(bob.getUniqueId());

        requests.accept(bob, null);
        advance(5_000);

        neverTeleported(alice);
        verify(alice).sendMessage(contains("Bob is in a siege match."));
        verify(bob).sendMessage(contains("Alice couldn't teleport to you."));
    }

    @Test
    void frozenDuringTheWarmupStopsTheTeleport() {
        Set<UUID> frozen = new HashSet<>();
        engine.registerRestriction(new FreezeTeleportRestriction(frozen::contains));
        requests.send(alice, bob, Direction.TO_TARGET);
        requests.accept(bob, null);
        frozen.add(bob.getUniqueId());

        advance(5_000);

        neverTeleported(alice);
        verify(alice).sendMessage(contains("Bob is frozen."));
    }

    @Test
    void requestIsRefusedUpFrontWhenTheRequesterIsInCombat() {
        engine.combatTags().tag(alice.getUniqueId(), now);

        requests.send(alice, bob, Direction.TO_TARGET);

        verify(alice).sendMessage(contains("You were in combat"));
        verify(bob, never()).sendMessage(any(Component.class));
        requests.accept(bob, null);
        verify(bob).sendMessage(contains("You have no pending teleport requests."));
    }

    @Test
    void movingDuringTheWarmupCancelsItAndTellsTheOtherPlayer() {
        TeleportWarmupListener listener = new TeleportWarmupListener(engine, requests);
        requests.send(alice, bob, Direction.TO_TARGET);
        requests.accept(bob, null);

        listener.onMove(new PlayerMoveEvent(alice, new Location(world, 0.5, 64, 0.5), new Location(world, 1.5, 64, 0.5)));
        advance(5_000);

        neverTeleported(alice);
        verify(alice).sendMessage(contains(WarmupCancelReason.MOVED.message()));
        verify(bob).sendMessage(contains("Alice's teleport to you was cancelled."));
    }

    // ===== vanish / ignore =====

    @Test
    void vanishedRequesterCanNotAskAPlayerWhoCanNotSeeThem() {
        vanished.add(alice);

        requests.send(alice, bob, Direction.TO_TARGET);

        verify(alice).sendMessage(contains("You're vanished"));
        verify(bob, never()).sendMessage(any(Component.class));
    }

    @Test
    void vanishedRequesterCanAskStaffWhoCanSeeThem() {
        vanished.add(alice);
        when(staff.canSee(alice)).thenReturn(true);

        requests.send(alice, staff, Direction.TO_TARGET);

        verify(staff).sendMessage(any(Component.class));
    }

    @Test
    void requesterWhoVanishedSinceLooksLikeNoRequest() {
        requests.send(alice, bob, Direction.TO_TARGET);
        vanished.add(alice);

        requests.accept(bob, "Alice");

        verify(bob).sendMessage(contains("No pending teleport request from Alice."));
        assertFalse(engine.isWarmingUp(alice.getUniqueId()));
    }

    @Test
    void ignoredRequesterIsToldItWasSentButTheTargetNeverSeesIt() {
        requests.setIgnoreCheck((viewer, sender) -> viewer.equals(bob.getUniqueId()) && sender.equals(alice.getUniqueId()));

        requests.send(alice, bob, Direction.TO_TARGET);

        verify(alice).sendMessage(contains("Request sent to Bob."));
        verify(bob, never()).sendMessage(any(Component.class));
        assertTrue(requests.pendingRequesterNames(bob).isEmpty());
    }

    // ===== answer / withdraw / expiry =====

    @Test
    void denyTellsTheRequesterAndNobodyMoves() {
        requests.send(alice, bob, Direction.TO_TARGET);

        requests.deny(bob, null);
        advance(5_000);

        neverTeleported(alice);
        verify(alice).sendMessage(contains("Bob denied your teleport request."));
    }

    @Test
    void tpcancelWithdrawsTheRequest() {
        requests.send(alice, bob, Direction.TO_TARGET);

        requests.cancel(alice);
        requests.accept(bob, null);

        verify(alice).sendMessage(contains("Your teleport request to Bob was withdrawn."));
        verify(bob).sendMessage(contains("You have no pending teleport requests."));
    }

    @Test
    void tpcancelStopsTheRunningWarmupOnceAccepted() {
        requests.send(alice, bob, Direction.TO_TARGET);
        requests.accept(bob, null);

        requests.cancel(alice);
        advance(5_000);

        neverTeleported(alice);
        verify(alice).sendMessage(contains(WarmupCancelReason.BY_PLAYER.message()));
    }

    @Test
    void requestExpiresAfterThirtySeconds() {
        requests.send(alice, bob, Direction.TO_TARGET);

        advance(30_000);
        requests.accept(bob, null);

        verify(alice).sendMessage(contains("Your teleport request to Bob expired."));
        verify(bob).sendMessage(contains("The teleport request from Alice expired."));
        verify(bob).sendMessage(contains("You have no pending teleport requests."));
    }

    @Test
    void sameRequestTwiceIsAlreadyPending() {
        grant(alice, TeleportNodes.BYPASS_COOLDOWN);
        requests.send(alice, bob, Direction.TO_TARGET);

        requests.send(alice, bob, Direction.TO_TARGET);

        verify(alice).sendMessage(contains("is already pending"));
        verify(bob, times(1)).sendMessage(any(Component.class));
    }

    @Test
    void askingSomeoneWhoAlreadyAskedYouPointsToTheirRequest() {
        requests.send(bob, alice, Direction.TO_TARGET);

        requests.send(alice, bob, Direction.TO_TARGET);

        verify(alice).sendMessage(contains("/tpaccept Bob"));
        verify(bob, never()).sendMessage(any(Component.class));
    }

    @Test
    void quittingDropsTheRequestAndTellsTheOtherPlayer() {
        TeleportWarmupListener listener = new TeleportWarmupListener(engine, requests);
        requests.send(alice, bob, Direction.TO_TARGET);

        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(bob);
        listener.onQuit(quit);

        verify(alice).sendMessage(contains("Teleport request to Bob cancelled."));
        assertTrue(requests.pendingRequesterNames(bob).isEmpty());
    }

    // ===== cooldown / price =====

    @Test
    void secondRequestWithinTheSendCooldownIsRefused() {
        requests.send(alice, bob, Direction.TO_TARGET);

        requests.send(alice, carol, Direction.TO_TARGET);

        verify(alice).sendMessage(contains("You can send another teleport request in 10 s."));
        verify(carol, never()).sendMessage(any(Component.class));
        advance(10_000);
        requests.send(alice, carol, Direction.TO_TARGET);
        verify(carol).sendMessage(any(Component.class));
    }

    @Test
    void cooldownBypassSkipsTheSendCooldown() {
        grant(alice, TeleportNodes.BYPASS_COOLDOWN);
        requests.send(alice, bob, Direction.TO_TARGET);

        requests.send(alice, carol, Direction.TO_TARGET);

        verify(carol).sendMessage(any(Component.class));
        verify(alice).sendMessage(contains("Your request to Bob was withdrawn."));
    }

    @Test
    void nonZeroPriceRefusesRequestsInsteadOfCharging() {
        withRequestSettings(new TeleportRequestSettings(30, 10, 5, 10_000));

        requests.send(alice, bob, Direction.TO_TARGET);

        verify(alice).sendMessage(contains(TeleportRequestService.PAID_NOT_AVAILABLE));
        verify(bob, never()).sendMessage(any(Component.class));
    }

    @Test
    void priceSetAfterARequestWasSentRefusesTheAccept() {
        requests.send(alice, bob, Direction.TO_TARGET);
        withRequestSettings(new TeleportRequestSettings(30, 10, 5, 1));

        requests.accept(bob, null);
        advance(5_000);

        neverTeleported(alice);
        verify(bob).sendMessage(contains(TeleportRequestService.PAID_NOT_AVAILABLE));
    }

    @Test
    void reloadedExpiryAppliesToNewRequests() {
        withRequestSettings(new TeleportRequestSettings(10, 0, 5, 0));

        requests.send(alice, bob, Direction.TO_TARGET);
        advance(10_000);

        verify(alice).sendMessage(contains("Your teleport request to Bob expired."));
    }

    // ===== commands =====

    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final PlayerCommandSupport support = new PlayerCommandSupport(permissible, Runnable::run,
        this::byName, () -> everyone);
    private final TeleportRequestCommand tpa = new TeleportRequestCommand(TeleportRequestCommand.Form.TPA, support,
        targets, requests);

    private void permissibleFromGrants() {
        when(permissible.hasPermissionAsync(any(Player.class), anyString())).thenAnswer(inv ->
            permissions.has(inv.getArgument(0), inv.getArgument(1)));
    }

    private static void run(TeleportRequestCommand command, Player sender, String... args) {
        command.onCommand(sender, mock(Command.class), "tpa", args);
    }

    @Test
    void tpaNeedsTheRequestNode() {
        permissibleFromGrants();

        run(tpa, alice, "Bob");
        verify(alice).sendMessage(contains("You don't have permission"));
        verify(bob, never()).sendMessage(any(Component.class));

        grant(alice, TeleportNodes.REQUEST);
        run(tpa, alice, "Bob");
        verify(bob).sendMessage(any(Component.class));
    }

    @Test
    void tpahereNeedsItsOwnNode() {
        permissibleFromGrants();
        grant(alice, TeleportNodes.REQUEST);
        TeleportRequestCommand tpahere = tpa.withForm(TeleportRequestCommand.Form.TPAHERE);

        run(tpahere, alice, "Bob");
        verify(alice).sendMessage(contains("You don't have permission"));

        grant(alice, TeleportNodes.REQUEST_HERE);
        run(tpahere, alice, "Bob");
        requests.accept(bob, null);
        assertTrue(engine.isWarmingUp(bob.getUniqueId()));
    }

    @Test
    void vanishedTargetLooksOfflineToTpa() {
        permissibleFromGrants();
        grant(alice, TeleportNodes.REQUEST);
        vanished.add(bob);

        run(tpa, alice, "Bob");

        verify(alice).sendMessage(contains("No online player named 'Bob'."));
        verify(bob, never()).sendMessage(any(Component.class));
        assertFalse(tpa.onTabComplete(alice, mock(Command.class), "tpa", new String[] {"B"}).contains("Bob"));
    }

    @Test
    void answeringNeedsNoNodeAndTheV1FormWorks() {
        permissibleFromGrants();
        requests.send(alice, bob, Direction.TO_TARGET);

        run(tpa, bob, "accept", "Alice");

        assertTrue(engine.isWarmingUp(alice.getUniqueId()));
    }

    @Test
    void tpdenyByName() {
        requests.send(alice, bob, Direction.TO_TARGET);
        requests.send(carol, bob, Direction.TO_TARGET);

        run(tpa.withForm(TeleportRequestCommand.Form.DENY), bob, "Alice");

        verify(alice).sendMessage(contains("Bob denied your teleport request."));
        assertEquals(List.of("Carol"), requests.pendingRequesterNames(bob));
    }
}
