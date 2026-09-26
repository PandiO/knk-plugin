package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.teleport.SpawnPointResolver;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportKind;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.core.teleport.WarmupCancelReason;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.commands.support.TargetRankCheck;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.knightsandkings.knk.paper.teleport.SpawnDestinationResolver;
import net.knightsandkings.knk.paper.teleport.TeleportNodes;
import net.knightsandkings.knk.paper.teleport.TeleportPlan;
import net.knightsandkings.knk.paper.teleport.TeleportService;
import net.knightsandkings.knk.paper.teleport.VisibleTargetResolver;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /spawn (docs/specs/teleport/DESIGN.md §3.6, Phase 4): the destination from the Game Settings (or
 * the world spawn), the player form through the engine as a SPAWN teleport, and the staff form
 * ({@code knk.teleport.staff.others}, rank-checked, silent flag).
 */
class SpawnCommandTest {

    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final Set<String> granted = new HashSet<>();
    private final World world = mock(World.class);
    private final Location worldSpawn = new Location(world, 0.5, 64, 0.5);
    private final Player staff = player("Staff");
    private final Player alice = player("Alice");
    private final Player bob = player("Bob");
    private final Set<Player> vanished = new HashSet<>();
    private final Map<String, Player> online = Map.of("staff", staff, "alice", alice, "bob", bob);
    private final PlayerCommandSupport support = new PlayerCommandSupport(permissible, Runnable::run,
        name -> online.get(name.toLowerCase()), () -> List.of(staff, alice, bob));
    private final VisibleTargetResolver targets = new VisibleTargetResolver(
        name -> online.get(name.toLowerCase()), () -> List.of(staff, alice, bob), vanished::contains);

    private final Set<String> higherRanked = new HashSet<>();
    private final List<String> rankChecked = new ArrayList<>();
    private final TargetRankCheck rankCheck = (sender, targetName, onAllowed) -> {
        rankChecked.add(targetName);
        if (higherRanked.contains(targetName)) {
            sender.sendMessage("You cannot act on a player of equal or higher rank.");
            return;
        }
        onAllowed.accept(new UserSummary(1, targetName, UUID.nameUUIDFromBytes(targetName.getBytes()), 0));
    };

    private KnkGameSettings settings = new KnkGameSettings("WorldSpawn", null);
    private final Map<Integer, KnkLocation> towns = new java.util.HashMap<>();
    private final SpawnDestinationResolver spawn = new SpawnDestinationResolver(
        new SpawnPointResolver(() -> CompletableFuture.completedFuture(settings),
            id -> CompletableFuture.completedFuture(Optional.empty()),
            id -> CompletableFuture.completedFuture(Optional.ofNullable(towns.get(id))),
            id -> CompletableFuture.completedFuture(Optional.empty()),
            id -> CompletableFuture.completedFuture(Optional.empty()),
            System::currentTimeMillis, Duration.ofMinutes(5)),
        name -> "world".equals(name) ? world : null,
        () -> world);

    private final TeleportService teleportService = mock(TeleportService.class);
    private final SpawnCommand command = new SpawnCommand(support, rankCheck, targets, teleportService, spawn,
        vanished::contains);

    SpawnCommandTest() {
        when(world.getName()).thenReturn("world");
        when(world.getSpawnLocation()).thenReturn(worldSpawn);
        when(permissible.hasPermissionAsync(any(), anyString())).thenAnswer(inv ->
            CompletableFuture.completedFuture(granted.contains((String) inv.getArgument(1))));
        when(teleportService.start(any())).thenReturn(CompletableFuture.completedFuture(TeleportOutcome.teleported()));
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private void grant(String... nodes) {
        granted.addAll(List.of(nodes));
    }

    private void run(CommandSender sender, String... args) {
        command.onCommand(sender, mock(Command.class), "spawn", args);
    }

    private TeleportPlan startedPlan() {
        ArgumentCaptor<TeleportPlan> plan = ArgumentCaptor.forClass(TeleportPlan.class);
        verify(teleportService).start(plan.capture());
        return plan.getValue();
    }

    private void townSpawn(double x, double y, double z, String worldName) {
        towns.put(4, new KnkLocation(12, "Kardenna", x, y, z, 90f, 0f, worldName));
        settings = new KnkGameSettings("CustomReference",
            new KnkSpawnReference(KnkSpawnReference.SourceType.TOWN, 4, "Town: Kardenna", null));
    }

    // ===== /spawn =====

    @Test
    void spawnWithoutCustomSpawnGoesToTheWorldSpawnAsAPlayerTeleport() {
        grant(TeleportNodes.SPAWN);

        run(alice);

        TeleportPlan plan = startedPlan();
        assertSame(alice, plan.subject());
        assertSame(alice, plan.actor());
        assertEquals(TeleportKind.SPAWN, plan.kind());
        assertNull(plan.visited());
        assertEquals(worldSpawn, plan.destination().get());
        verify(alice).sendMessage(contains("Teleported to spawn"));
    }

    @Test
    void spawnGoesToTheTownSetOnTheGameSettingsPage() {
        grant(TeleportNodes.SPAWN);
        townSpawn(100.5, 70, -20.5, "world");

        run(alice);

        Location destination = startedPlan().destination().get();
        assertSame(world, destination.getWorld());
        assertEquals(100.5, destination.getX());
        assertEquals(70, destination.getY());
        assertEquals(-20.5, destination.getZ());
        assertEquals(90f, destination.getYaw());
        assertEquals("Town: Kardenna", startedPlan().destinationLabel());
    }

    @Test
    void spawnInAWorldThatIsNotLoadedFallsBackToTheWorldSpawn() {
        grant(TeleportNodes.SPAWN);
        townSpawn(100, 70, -20, "old_world");

        run(alice);

        assertEquals(worldSpawn, startedPlan().destination().get());
    }

    @Test
    void spawnNeedsItsNode() {
        run(alice);

        verify(teleportService, never()).start(any());
        verify(alice).sendMessage(contains("don't have permission"));
    }

    @Test
    void refusedSpawnShowsTheEnginesReason() {
        grant(TeleportNodes.SPAWN);
        when(teleportService.start(any())).thenReturn(CompletableFuture.completedFuture(
            TeleportOutcome.denied(TeleportDenial.of(TeleportDenial.COMBAT, "You were in combat 2 s ago; wait 8 s."))));

        run(alice);

        verify(alice).sendMessage(contains("You were in combat 2 s ago"));
        verify(alice, never()).sendMessage(contains("Teleported to spawn"));
    }

    @Test
    void cancelledWarmupIsNotReportedTwice() {
        grant(TeleportNodes.SPAWN);
        when(teleportService.start(any())).thenReturn(CompletableFuture.completedFuture(
            TeleportOutcome.cancelled(WarmupCancelReason.MOVED)));

        run(alice);

        verify(alice, never()).sendMessage(anyString());
    }

    @Test
    void consoleNeedsAPlayerName() {
        CommandSender console = mock(CommandSender.class);

        run(console);

        verify(teleportService, never()).start(any());
        verify(console).sendMessage(contains("/spawn <player>"));
    }

    @Test
    void spawnWithYourOwnNameIsThePlayerForm() {
        grant(TeleportNodes.SPAWN);

        run(alice, "Alice");

        assertEquals(TeleportKind.SPAWN, startedPlan().kind());
        assertTrue(rankChecked.isEmpty());
    }

    // ===== /spawn <player> =====

    @Test
    void sendingSomeoneElseNeedsStaffOthers() {
        grant(TeleportNodes.SPAWN);

        run(staff, "Bob");

        verify(teleportService, never()).start(any());
        verify(staff).sendMessage(contains("don't have permission"));
    }

    @Test
    void staffSendsAPlayerToSpawnInstantlyAndRankChecked() {
        grant(TeleportNodes.STAFF_OTHERS);

        run(staff, "Bob");

        TeleportPlan plan = startedPlan();
        assertSame(bob, plan.subject());
        assertSame(staff, plan.actor());
        assertEquals(TeleportKind.STAFF, plan.kind());
        assertFalse(plan.silent());
        assertEquals(worldSpawn, plan.destination().get());
        assertEquals(List.of("Bob"), rankChecked);
        verify(staff).sendMessage(contains("Sent Bob to spawn"));
        verify(bob).sendMessage(contains("Staff sent you to spawn"));
    }

    @Test
    void cannotSendAHigherRankedPlayer() {
        grant(TeleportNodes.STAFF_OTHERS);
        higherRanked.add("Bob");

        run(staff, "Bob");

        verify(teleportService, never()).start(any());
        verify(staff).sendMessage(contains("equal or higher rank"));
    }

    @Test
    void silentFlagNeedsItsNode() {
        grant(TeleportNodes.STAFF_OTHERS);

        run(staff, "Bob", "-s");

        verify(teleportService, never()).start(any());
    }

    @Test
    void silentSendDoesNotTellThePlayer() {
        grant(TeleportNodes.STAFF_OTHERS, TeleportNodes.STAFF_SILENT);

        run(staff, "Bob", "-s");

        assertTrue(startedPlan().silent());
        verify(bob, never()).sendMessage(anyString());
    }

    @Test
    void vanishedStaffAreAlwaysSilent() {
        grant(TeleportNodes.STAFF_OTHERS);
        vanished.add(staff);

        run(staff, "Bob");

        assertTrue(startedPlan().silent());
        verify(bob, never()).sendMessage(anyString());
    }

    @Test
    void consoleCanSendAPlayerToSpawn() {
        CommandSender console = mock(CommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");

        run(console, "Bob");

        TeleportPlan plan = startedPlan();
        assertSame(bob, plan.subject());
        assertEquals(TeleportKind.STAFF, plan.kind());
        verify(bob).sendMessage(contains("CONSOLE sent you to spawn"));
    }

    @Test
    void unknownPlayerIsReported() {
        grant(TeleportNodes.STAFF_OTHERS);

        run(staff, "Nobody");

        verify(teleportService, never()).start(any());
        verify(staff).sendMessage(contains("No online player named 'Nobody'"));
    }

    @Test
    void tabCompletesPlayersThenTheSilentFlag() {
        assertEquals(List.of("Alice"), command.onTabComplete(staff, mock(Command.class), "spawn", new String[]{"al"}));
        assertEquals(List.of("-s"), command.onTabComplete(staff, mock(Command.class), "spawn", new String[]{"Bob", ""}));
    }
}
