package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.dataaccess.TeleportDestinationsDataAccess;
import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;
import net.knightsandkings.knk.core.domain.teleport.TeleportChargeResult;
import net.knightsandkings.knk.core.domain.teleport.TeleportRefundResult;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.TeleportDestinationsCommandApi;
import net.knightsandkings.knk.core.teleport.TeleportCharger;
import net.knightsandkings.knk.core.teleport.TeleportKind;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.commands.support.TargetRankCheck;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.knightsandkings.knk.paper.teleport.TeleportCharges;
import net.knightsandkings.knk.paper.teleport.TeleportNodes;
import net.knightsandkings.knk.paper.teleport.TeleportPlan;
import net.knightsandkings.knk.paper.teleport.TeleportService;
import net.knightsandkings.knk.paper.teleport.VisibleTargetResolver;
import net.kyori.adventure.text.Component;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /warp, /warps (docs/specs/teleport/DESIGN.md §3.2/§3.7, Phase 5): name resolution, locks from the
 * server's list with the bypass nodes on top, the player form as a WARP teleport with a charge, the
 * staff form free and instant, and tab completion from the cached list.
 */
class WarpCommandTest {

    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final Set<String> granted = new HashSet<>();
    private final World world = mock(World.class);
    private final Player alice = player("Alice");
    private final Player bob = player("Bob");
    private final Map<String, Player> online = Map.of("alice", alice, "bob", bob);
    private final PlayerCommandSupport support = new PlayerCommandSupport(permissible, Runnable::run,
        name -> online.get(name.toLowerCase()), () -> List.of(alice, bob));
    private final VisibleTargetResolver targets = new VisibleTargetResolver(
        name -> online.get(name.toLowerCase()), () -> List.of(alice, bob), p -> false);
    private final List<String> rankChecked = new ArrayList<>();
    private final TargetRankCheck rankCheck = (sender, targetName, onAllowed) -> {
        rankChecked.add(targetName);
        onAllowed.accept(new UserSummary(8, targetName, UUID.nameUUIDFromBytes(targetName.getBytes()), 0));
    };

    private final KnkTeleportDestination kardenna = destination(3, "Kardenna", "Town", true, null, null);
    private final KnkTeleportDestination keep = destination(4, "Keep", "Structure", false, "TitleTooLow", "Reach title Knight to unlock");
    private final KnkTeleportDestination marketTown = destination(5, "Market", "Town", true, null, null);
    private final KnkTeleportDestination marketDistrict = destination(6, "Market", "District", true, null, null);
    private List<KnkTeleportDestination> list = List.of(kardenna, keep, marketTown, marketDistrict);
    private final List<Integer> listed = new ArrayList<>();
    private final TeleportDestinationsDataAccess destinations = new TeleportDestinationsDataAccess(userId -> {
        listed.add(userId);
        return CompletableFuture.completedFuture(list);
    }, Duration.ofSeconds(60));

    private final TeleportDestinationsCommandApi chargeApi = new TeleportDestinationsCommandApi() {
        @Override public CompletableFuture<TeleportChargeResult> chargeWarp(int d, int u, String k, boolean r, boolean c) {
            return CompletableFuture.completedFuture(TeleportChargeResult.allowed("Gems", 0, 0, false, null));
        }
        @Override public CompletableFuture<TeleportChargeResult> chargeRequestFee(int u, int a, String k, Integer o) {
            return CompletableFuture.completedFuture(TeleportChargeResult.allowed("Coins", 0, 0, false, null));
        }
        @Override public CompletableFuture<TeleportRefundResult> refund(int u, String k, String r) {
            return CompletableFuture.completedFuture(new TeleportRefundResult(false, null, 0, null, false));
        }
    };
    private final Map<UUID, Integer> userIds = Map.of(alice.getUniqueId(), 7, bob.getUniqueId(), 8);
    private final TeleportCharges charges = new TeleportCharges(new TeleportCharger(chargeApi, 1, Runnable::run, Runnable::run),
        uuid -> CompletableFuture.completedFuture(userIds.get(uuid)), name -> world, id -> null);

    private final TeleportService teleportService = mock(TeleportService.class);
    private final WarpCommand command = new WarpCommand(WarpCommand.Form.WARP, new WarpCommand.Deps(support, rankCheck, targets,
        teleportService, destinations, charges, uuid -> CompletableFuture.completedFuture(userIds.get(uuid)),
        (player, node) -> CompletableFuture.completedFuture(granted.contains(node)),
        name -> "world".equals(name) ? world : null, p -> false));

    WarpCommandTest() {
        when(world.getName()).thenReturn("world");
        when(permissible.hasPermissionAsync(any(), anyString())).thenAnswer(inv ->
            CompletableFuture.completedFuture(granted.contains((String) inv.getArgument(1))));
        when(teleportService.start(any())).thenReturn(CompletableFuture.completedFuture(TeleportOutcome.teleported()));
    }

    private static KnkTeleportDestination destination(int id, String name, String type, boolean met, String code, String reason) {
        return new KnkTeleportDestination(id, name, type, "world", 10.5, 64, -3.5, 90f, 0f, 10, met ? null : "Knight", null,
            false, met, met, true, code, reason);
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private void run(CommandSender sender, String... args) {
        command.onCommand(sender, mock(Command.class), "warp", args);
    }

    private TeleportPlan startedPlan() {
        ArgumentCaptor<TeleportPlan> plan = ArgumentCaptor.forClass(TeleportPlan.class);
        verify(teleportService).start(plan.capture());
        return plan.getValue();
    }

    @Test
    void warpStartsAChargedWarpTeleportToTheDestinationsLocation() {
        granted.add(TeleportNodes.WARP);

        run(alice, "kardenna");

        TeleportPlan plan = startedPlan();
        assertEquals(TeleportKind.WARP, plan.kind());
        assertEquals(alice, plan.subject());
        assertNotNull(plan.charge(), "the server authorizes and charges it after the warmup");
        Location to = plan.destination().get();
        assertEquals(10.5, to.getX());
        assertEquals(-3.5, to.getZ());
        assertEquals(90f, to.getYaw());
        assertEquals(List.of(7), listed);
        verify(alice).sendMessage(contains("You teleported to Kardenna."));
    }

    @Test
    void warpNeedsTheNode() {
        run(alice, "Kardenna");

        verify(alice).sendMessage(contains("You don't have permission"));
        verify(teleportService, never()).start(any());
    }

    @Test
    void aLockedDestinationShowsTheServersReasonWithoutStarting() {
        granted.add(TeleportNodes.WARP);

        run(alice, "Keep");

        verify(alice).sendMessage(contains("Reach title Knight to unlock"));
        verify(teleportService, never()).start(any());
    }

    @Test
    void theRequirementsBypassLetsALockedDestinationThrough() {
        granted.add(TeleportNodes.WARP);
        granted.add(TeleportNodes.BYPASS_REQUIREMENTS);

        run(alice, "Keep");

        assertEquals(TeleportKind.WARP, startedPlan().kind());
    }

    @Test
    void sharedNamesAskForTheTypeForm() {
        granted.add(TeleportNodes.WARP);

        run(alice, "Market");
        verify(alice).sendMessage(contains("town:Market, district:Market"));
        verify(teleportService, never()).start(any());

        run(alice, "district:market");
        assertEquals("Market", startedPlan().destinationLabel());
    }

    @Test
    void unknownNamesPointToTheList() {
        granted.add(TeleportNodes.WARP);

        run(alice, "Atlantis");

        verify(alice).sendMessage(contains("No teleport destination named 'Atlantis'"));
    }

    @Test
    void staffSendAPlayerFreeAndInstant() {
        granted.add(TeleportNodes.STAFF_OTHERS);

        run(alice, "Kardenna", "Bob");

        TeleportPlan plan = startedPlan();
        assertEquals(TeleportKind.STAFF, plan.kind());
        assertEquals(bob, plan.subject());
        assertNull(plan.charge(), "staff sends are free");
        assertEquals(List.of("Bob"), rankChecked);
        assertEquals(List.of(8), listed, "the moved player's list");
        verify(alice).sendMessage(contains("Sent Bob to Kardenna."));
        verify(bob).sendMessage(contains("Alice sent you to Kardenna."));
    }

    @Test
    void theStaffFormNeedsStaffOthers() {
        granted.add(TeleportNodes.WARP);

        run(alice, "Kardenna", "Bob");

        verify(alice).sendMessage(contains("You don't have permission"));
        verify(teleportService, never()).start(any());
    }

    @Test
    void theConsoleCanSendPlayers() {
        CommandSender console = mock(CommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");

        command.onCommand(console, mock(Command.class), "warp", new String[] {"Kardenna", "Bob", "-s"});

        TeleportPlan plan = startedPlan();
        assertTrue(plan.silent());
        verify(bob, never()).sendMessage(contains("sent you"));
    }

    @Test
    void warpsListsEveryDestinationWithItsLock() {
        granted.add(TeleportNodes.WARP);

        command.withForm(WarpCommand.Form.LIST).onCommand(alice, mock(Command.class), "warps", new String[0]);

        verify(alice, atLeastOnce()).sendMessage(any(Component.class));
        verify(teleportService, never()).start(any());
    }

    @Test
    void listLinesShowPriceAndLock() {
        String open = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(WarpCommand.listLine(kardenna, false, false));
        String locked = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(WarpCommand.listLine(keep, false, false));

        assertTrue(open.contains("Kardenna (Town, 10 gems) Available!"), open);
        assertTrue(locked.contains("Locked! Reach title Knight to unlock"), locked);
    }

    @Test
    void tabCompletionUsesTheCachedListOnceLoaded() {
        granted.add(TeleportNodes.WARP);
        List<String> first = command.onTabComplete(alice, mock(Command.class), "warp", new String[] {""});
        assertEquals(List.of("list"), first, "no list yet - it is fetched for the next press");

        List<String> second = command.onTabComplete(alice, mock(Command.class), "warp", new String[] {"k"});
        assertEquals(List.of("Kardenna", "Keep"), second);
        assertTrue(command.onTabComplete(alice, mock(Command.class), "warp", new String[] {"m"})
            .containsAll(List.of("town:Market", "district:Market")));
    }
}
