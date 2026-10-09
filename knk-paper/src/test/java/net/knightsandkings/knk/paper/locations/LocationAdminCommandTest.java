package net.knightsandkings.knk.paper.locations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.knightsandkings.knk.core.domain.location.LocationOrphanEntry;
import net.knightsandkings.knk.core.domain.location.LocationOrphanPage;
import net.knightsandkings.knk.core.domain.location.LocationTeleportTarget;
import net.knightsandkings.knk.core.ports.api.LocationRetentionApi;
import net.knightsandkings.knk.core.teleport.TeleportKind;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.paper.teleport.TeleportPlan;
import net.knightsandkings.knk.paper.teleport.TeleportService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** KNG-80: /knk location tp|orphans check their own node, and tp goes through the teleport engine as a staff teleport. */
class LocationAdminCommandTest {

    // Kept in a field: Location holds its world weakly (knk-plugin CLAUDE.md).
    private final World world = mock(World.class);
    private final LocationRetentionApi api = mock(LocationRetentionApi.class);
    private final TeleportService teleportService = mock(TeleportService.class);
    private final Set<String> granted = new HashSet<>();
    private final Set<Player> vanished = new HashSet<>();
    private final List<String> shownHere = new ArrayList<>();
    private final List<String> said = new ArrayList<>();
    private final Player staff = player("Staff");

    private final LocationAdminCommand command = new LocationAdminCommand(
        api,
        (sender, node, onAllowed) -> {
            if (granted.contains(node)) {
                onAllowed.run();
            } else {
                sender.sendMessage("no permission");
            }
        },
        (sender, node) -> granted.contains(node),
        () -> teleportService,
        name -> "world".equals(name) ? world : null,
        vanished::contains,
        Runnable::run,
        player -> shownHere.add(player.getName()));

    LocationAdminCommandTest() {
        when(world.getName()).thenReturn("world");
        when(teleportService.start(any())).thenReturn(CompletableFuture.completedFuture(TeleportOutcome.teleported()));
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        org.mockito.Mockito.doAnswer(inv -> said.add(inv.getArgument(0))).when(player).sendMessage(anyString());
        org.mockito.Mockito.doAnswer(inv -> said.add(PlainTextComponentSerializer.plainText().serialize(inv.getArgument(0))))
            .when(player).sendMessage(any(Component.class));
        return player;
    }

    private TeleportPlan startedPlan() {
        ArgumentCaptor<TeleportPlan> plan = ArgumentCaptor.forClass(TeleportPlan.class);
        verify(teleportService).start(plan.capture());
        return plan.getValue();
    }

    @Test
    void tp_withoutTheNode_neverAsksTheApi() {
        command.execute(staff, new String[] {"tp", "42"});

        verify(api, never()).teleportTarget(42);
        verify(teleportService, never()).start(any());
        assertEquals(List.of("no permission"), said);
    }

    @Test
    void tp_isAStaffTeleportThroughTheEngine_toTheLocation() {
        granted.add(LocationAdminCommand.TELEPORT_NODE);
        when(api.teleportTarget(42)).thenReturn(CompletableFuture.completedFuture(
            Optional.of(new LocationTeleportTarget(42, "Location", "world", 120.5, 64, -33, 90f, 10f))));

        command.execute(staff, new String[] {"tp", "42"});

        TeleportPlan plan = startedPlan();
        assertEquals(TeleportKind.STAFF, plan.kind());
        assertEquals(staff, plan.subject());
        Location destination = plan.destination().get();
        assertEquals(world, destination.getWorld());
        assertEquals(120.5, destination.getX());
        assertEquals(-33, destination.getZ());
        assertEquals(90f, destination.getYaw());
        assertFalse(plan.silent());
        assertTrue(said.get(said.size() - 1).contains("Teleported to Location #42 (120.5, 64.0, -33.0 in world)"), said.toString());
    }

    @Test
    void tp_whileVanished_isSilent() {
        granted.add(LocationAdminCommand.TELEPORT_NODE);
        vanished.add(staff);
        when(api.teleportTarget(7)).thenReturn(CompletableFuture.completedFuture(
            Optional.of(new LocationTeleportTarget(7, null, "world", 0, 70, 0, 0f, 0f))));

        command.execute(staff, new String[] {"tp", "7"});

        assertTrue(startedPlan().silent());
    }

    @Test
    void tp_reportsAGuardRefusal() {
        granted.add(LocationAdminCommand.TELEPORT_NODE);
        when(api.teleportTarget(7)).thenReturn(CompletableFuture.completedFuture(
            Optional.of(new LocationTeleportTarget(7, null, "world", 0, 70, 0, 0f, 0f))));
        when(teleportService.start(any())).thenReturn(CompletableFuture.completedFuture(TeleportOutcome.failed("You are in a siege.")));

        command.execute(staff, new String[] {"tp", "7"});

        assertTrue(said.get(said.size() - 1).contains("You are in a siege."), said.toString());
    }

    @Test
    void tp_unknownLocationOrUnloadedWorld_isExplained_andNothingStarts() {
        granted.add(LocationAdminCommand.TELEPORT_NODE);
        when(api.teleportTarget(5)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(api.teleportTarget(6)).thenReturn(CompletableFuture.completedFuture(
            Optional.of(new LocationTeleportTarget(6, null, "siege_arena", 0, 70, 0, 0f, 0f))));

        command.execute(staff, new String[] {"tp", "5"});
        command.execute(staff, new String[] {"tp", "6"});
        command.execute(staff, new String[] {"tp", "abc"});

        verify(teleportService, never()).start(any());
        assertTrue(said.get(0).contains("doesn't exist"), said.toString());
        assertTrue(said.get(1).contains("isn't loaded"), said.toString());
        assertTrue(said.get(2).contains("Usage"), said.toString());
    }

    @Test
    void orphans_listsOpenOrphans_withTeleportLinksOnlyForHoldersOfTheTpNode() {
        granted.add(LocationAdminCommand.ORPHANS_NODE);
        when(api.listOrphans("open", 1, LocationAdminCommand.PAGE_SIZE)).thenReturn(CompletableFuture.completedFuture(
            new LocationOrphanPage(List.of(
                new LocationOrphanEntry(3, 41, "Open", "world", 1, 2, 3, "2026-10-04T04:00:00", null, null),
                new LocationOrphanEntry(4, 42, "Open", "world_nether", 4, 5, 6, "2026-10-04T04:00:00", "mod", "Event marker")),
                2, 1, 8, 2)));

        command.execute(staff, new String[] {"orphans"});

        assertTrue(said.get(0).contains("2 open"), said.toString());
        assertEquals("#41 world 1.0 2.0 3.0 flagged 2026-10-04", said.get(1));
        assertEquals("#42 world_nether 4.0 5.0 6.0 flagged 2026-10-04 (kept before by mod: Event marker)", said.get(2));
        assertTrue(said.get(3).contains("web app"), said.toString());

        said.clear();
        granted.add(LocationAdminCommand.TELEPORT_NODE);
        command.execute(staff, new String[] {"orphans"});
        assertTrue(said.get(1).endsWith("[tp]"), said.toString());
    }

    @Test
    void orphans_withoutTheNode_neverAsksTheApi() {
        command.execute(staff, new String[] {"orphans"});

        verify(api, never()).listOrphans(anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void here_keepsItsOwnNode() {
        command.execute(staff, new String[] {"here"});
        assertTrue(shownHere.isEmpty());

        granted.add(LocationAdminCommand.HERE_NODE);
        command.execute(staff, new String[] {"here"});
        assertEquals(List.of("Staff"), shownHere);
    }

    @Test
    void tabCompletion_andListing_onlyOfferActionsTheSenderHolds() {
        assertFalse(command.visibleTo(staff));
        assertEquals(List.of(), command.complete(staff, new String[] {""}));

        granted.add(LocationAdminCommand.TELEPORT_NODE);
        assertTrue(command.visibleTo(staff));
        assertEquals(List.of("tp"), command.complete(staff, new String[] {""}));

        granted.add(LocationAdminCommand.HERE_NODE);
        granted.add(LocationAdminCommand.ORPHANS_NODE);
        assertEquals(List.of("here", "tp", "orphans"), command.complete(staff, new String[] {""}));
        assertEquals(List.of("orphans"), command.complete(staff, new String[] {"or"}));
    }

    @Test
    void consoleCannotTeleport() {
        granted.add(LocationAdminCommand.TELEPORT_NODE);
        CommandSender console = mock(CommandSender.class);
        List<String> consoleSaw = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> consoleSaw.add(inv.getArgument(0))).when(console).sendMessage(anyString());

        command.execute(console, new String[] {"tp", "1"});

        verify(api, never()).teleportTarget(1);
        assertTrue(consoleSaw.get(0).contains("Only players"), consoleSaw.toString());
    }
}
