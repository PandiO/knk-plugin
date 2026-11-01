package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportKind;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.core.teleport.WarmupCancelReason;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.commands.support.TargetRankCheck;
import net.knightsandkings.knk.paper.hub.HubCommand;
import net.knightsandkings.knk.paper.hub.HubOutageService;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.knightsandkings.knk.paper.teleport.TeleportNodes;
import net.knightsandkings.knk.paper.teleport.TeleportPlan;
import net.knightsandkings.knk.paper.teleport.TeleportService;
import net.knightsandkings.knk.paper.teleport.VisibleTargetResolver;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import net.knightsandkings.knk.paper.teleport.TeleportExemptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /hub} (KNG-188): any player to the Game Settings hub spawn as a HUB teleport through the engine, the staff
 * form like {@code /spawn <player>}, a plain refusal without a usable hub, and {@code /hub return} unchanged.
 */
class HubTeleportCommandTest {

    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final Set<String> granted = new HashSet<>();
    private final World gameplay = mock(World.class);
    private final World hubWorld = mock(World.class);
    private final Location hubSpawn = new Location(hubWorld, 10.5, 70, -4.5, 180f, 0f);
    private Optional<Location> hub = Optional.of(hubSpawn);
    private Optional<String> why = Optional.empty();
    private final List<Player> settled = new ArrayList<>();
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

    private final TeleportService teleportService = mock(TeleportService.class);
    private final HubTeleportCommand teleport = new HubTeleportCommand(support, rankCheck, targets, teleportService,
        () -> hub, () -> why, vanished::contains);
    private final HubOutageService outage = mock(HubOutageService.class);
    private final HubOutageService.Access access = (player, at) -> Optional.empty();
    private final HubOutageService.Blocker blocker = player -> Optional.empty();
    private final HubCommand command = new HubCommand(outage, access, blocker);

    HubTeleportCommandTest() {
        when(gameplay.getName()).thenReturn("world_KNK-DEV");
        when(hubWorld.getName()).thenReturn("knk_hub");
        when(permissible.hasPermissionAsync(any(), anyString())).thenAnswer(inv ->
            CompletableFuture.completedFuture(granted.contains((String) inv.getArgument(1))));
        when(permissible.checkAsync(any(), anyString())).thenAnswer(inv -> permissible
            .hasPermissionAsync(inv.getArgument(0), inv.getArgument(1))
            .thenApply(net.knightsandkings.knk.core.domain.permissions.PermissionDecision::of));
        when(permissible.hasPermission(any(Player.class), anyString()))
            .thenAnswer(inv -> granted.contains(inv.<String>getArgument(1)));
        // A completed teleport puts the subject in the hub world.
        when(teleportService.start(any())).thenAnswer(inv -> {
            TeleportPlan plan = inv.getArgument(0);
            when(plan.subject().getWorld()).thenReturn(hubWorld);
            return CompletableFuture.completedFuture(TeleportOutcome.teleported());
        });
        teleport.setSettleInWorld(settled::add);
        command.setTeleport(teleport);
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(gameplay);
        return player;
    }

    private void grant(String... nodes) {
        granted.addAll(List.of(nodes));
    }

    private void run(CommandSender sender, String... args) {
        command.onCommand(sender, mock(Command.class), "hub", args);
    }

    private TeleportPlan startedPlan() {
        ArgumentCaptor<TeleportPlan> plan = ArgumentCaptor.forClass(TeleportPlan.class);
        verify(teleportService).start(plan.capture());
        return plan.getValue();
    }

    // ===== /hub =====

    @Test
    void anyPlayerGoesToTheHubSpawnAsAHubTeleport() {
        run(alice);

        TeleportPlan plan = startedPlan();
        assertSame(alice, plan.subject());
        assertSame(alice, plan.actor());
        assertEquals(TeleportKind.HUB, plan.kind());
        assertNull(plan.visited());
        assertNull(plan.charge(), "free without the API's charges");
        assertEquals(hubSpawn, plan.destination().get());
        verify(alice).sendMessage(contains("Teleported to the hub"));
        assertEquals(List.of(alice), settled, "arrived in another world: its game mode");
    }

    @Test
    void theDestinationIsReadAgainWhenTheWarmupEnds() {
        run(alice);
        TeleportPlan plan = startedPlan();

        hub = Optional.empty();

        assertNull(plan.destination().get(), "a hub that stopped taking players fails the teleport");
    }

    @Test
    void withoutAUsableHubNobodyMoves() {
        hub = Optional.empty();
        why = Optional.of("the hub world 'knk_hub' is not loaded");

        run(alice);

        verify(teleportService, never()).start(any());
        verify(alice).sendMessage(contains("The hub isn't available right now."));
        verify(alice, never()).sendMessage(contains("knk_hub"));
    }

    @Test
    void aHubSpawnInAnUnloadedWorldCountsAsNoHub() {
        World unloaded = null;
        hub = Optional.of(new Location(unloaded, 1, 2, 3));

        run(alice);

        verify(teleportService, never()).start(any());
        verify(alice).sendMessage(contains("The hub isn't available right now."));
    }

    @Test
    void aRefusedTeleportShowsTheEnginesReason() {
        doReturn(CompletableFuture.completedFuture(
            TeleportOutcome.denied(TeleportDenial.of("SIEGE", "You can't teleport during a siege match."))))
            .when(teleportService).start(any());

        run(alice);

        verify(alice).sendMessage(contains("You can't teleport during a siege match."));
        verify(alice, never()).sendMessage(contains("Teleported to the hub"));
        assertTrue(settled.isEmpty());
    }

    @Test
    void aCancelledWarmupIsNotReportedTwice() {
        doReturn(CompletableFuture.completedFuture(TeleportOutcome.cancelled(WarmupCancelReason.MOVED)))
            .when(teleportService).start(any());

        run(alice);

        verify(alice, never()).sendMessage(anyString());
    }

    @Test
    void aTeleportWithinTheHubWorldKeepsTheGameMode() {
        when(alice.getWorld()).thenReturn(hubWorld);

        run(alice);

        assertTrue(settled.isEmpty());
    }

    @Test
    void consoleNeedsAPlayerName() {
        CommandSender console = mock(CommandSender.class);

        run(console);

        verify(teleportService, never()).start(any());
        verify(console).sendMessage(contains("/hub <player>"));
    }

    @Test
    void withoutTheTeleportEngineTheHubIsUnavailable() {
        command.setTeleport(null);

        run(alice);

        verify(teleportService, never()).start(any());
        verify(alice).sendMessage(contains("The hub isn't available right now."));
    }

    // ===== /hub return =====

    @Test
    void theReturnButtonStillWorks() {
        run(alice, "return", "42");

        verify(outage).returnTo(alice, 42L, access, blocker);
        verify(teleportService, never()).start(any());
    }

    @Test
    void returnWithoutATokenShowsTheOfferAgain() {
        run(alice, "return");

        verify(outage).offerReturn(alice);
        verify(outage, never()).returnTo(any(), anyLong(), any(), any());
    }

    // ===== /hub <player> =====

    @Test
    void sendingSomeoneElseNeedsStaffOthers() {
        run(staff, "Bob");

        verify(teleportService, never()).start(any());
        verify(staff).sendMessage(contains("don't have permission"));
    }

    @Test
    void staffSendsAPlayerToTheHubInstantlyAndRankChecked() {
        grant(TeleportNodes.STAFF_OTHERS);

        run(staff, "Bob");

        TeleportPlan plan = startedPlan();
        assertSame(bob, plan.subject());
        assertSame(staff, plan.actor());
        assertEquals(TeleportKind.STAFF, plan.kind());
        assertEquals(hubSpawn, plan.destination().get());
        assertEquals(List.of("Bob"), rankChecked);
        verify(staff).sendMessage(contains("Sent Bob to the hub"));
        verify(bob).sendMessage(contains("Staff sent you to the hub"));
        assertEquals(List.of(bob), settled);
    }

    @Test
    void staffAreToldWhyThereIsNoHub() {
        grant(TeleportNodes.STAFF_OTHERS);
        hub = Optional.empty();
        why = Optional.of("the hub world 'knk_hub' is not loaded");

        run(staff, "Bob");

        verify(teleportService, never()).start(any());
        verify(staff).sendMessage(contains("the hub world 'knk_hub' is not loaded"));
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
    void silentSendNeedsItsNodeAndDoesNotTellThePlayer() {
        grant(TeleportNodes.STAFF_OTHERS);
        run(staff, "Bob", "-s");
        verify(teleportService, never()).start(any());

        grant(TeleportNodes.STAFF_SILENT);
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
    void hubWithYourOwnNameIsThePlayerForm() {
        run(alice, "Alice");

        assertEquals(TeleportKind.HUB, startedPlan().kind());
        assertTrue(rankChecked.isEmpty());
    }

    // ===== tab completion =====

    @Test
    void playersAreOfferedReturn_StaffAlsoPlayersAndTheSilentFlag() {
        Command cmd = mock(Command.class);
        assertEquals(List.of("return"), command.onTabComplete(alice, cmd, "hub", new String[]{""}));
        assertEquals(List.of(), command.onTabComplete(alice, cmd, "hub", new String[]{"al"}));

        grant(TeleportNodes.STAFF_OTHERS, TeleportNodes.STAFF_SILENT);
        assertEquals(List.of("Alice"), command.onTabComplete(staff, cmd, "hub", new String[]{"al"}));
        assertEquals(List.of("-s"), command.onTabComplete(staff, cmd, "hub", new String[]{"Bob", ""}));
        assertEquals(List.of(), command.onTabComplete(staff, cmd, "hub", new String[]{"return", ""}));
    }

    @Test
    void aStaffSendFromTheHubWorldKeepsTheGameMode() {
        grant(TeleportNodes.STAFF_OTHERS);
        when(bob.getWorld()).thenReturn(hubWorld);

        run(staff, "Bob");

        verify(staff).sendMessage(eq(org.bukkit.ChatColor.GREEN + "Sent Bob to the hub."));
        assertTrue(settled.isEmpty());
    }

    // ===== PandiO/knk-workspace#171: exemptions and the all form =====

    /** Exempt nodes per player name (the exemptions' own permission view; the command's grants stay sender-wide). */
    private final Map<String, Set<String>> exemptGrants = new java.util.HashMap<>();
    private final Set<Player> inMode = new HashSet<>();
    private final Set<Player> higherForAll = new HashSet<>();
    private boolean overridesExemption;

    private net.knightsandkings.knk.paper.teleport.TeleportExemptions exemptions(List<Player> onlinePlayers) {
        KnkPermissible perPlayer = mock(KnkPermissible.class);
        when(perPlayer.checkAsync(any(), anyString())).thenAnswer(inv -> {
            Player player = inv.getArgument(0);
            String node = inv.getArgument(1);
            boolean held = exemptGrants.getOrDefault(player.getName(), Set.of()).contains(node) || granted.contains(node) && player.getName().equals("Staff");
            return CompletableFuture.completedFuture(net.knightsandkings.knk.core.domain.permissions.PermissionDecision.of(held));
        });
        when(perPlayer.hasPermission(any(Player.class), anyString())).thenAnswer(inv -> granted.contains(inv.<String>getArgument(1)));
        PlayerCommandSupport exemptSupport = new PlayerCommandSupport(perPlayer, Runnable::run, name -> null, () -> onlinePlayers);
        return new net.knightsandkings.knk.paper.teleport.TeleportExemptions(exemptSupport, inMode::contains,
            (sender, target) -> CompletableFuture.completedFuture(!higherForAll.contains(target)),
            sender -> CompletableFuture.completedFuture(overridesExemption));
    }

    private void exempt(Player player, String node) {
        exemptGrants.computeIfAbsent(player.getName(), k -> new HashSet<>()).add(node);
    }

    private List<TeleportPlan> startedPlans() {
        ArgumentCaptor<TeleportPlan> plan = ArgumentCaptor.forClass(TeleportPlan.class);
        verify(teleportService, org.mockito.Mockito.atLeast(0)).start(plan.capture());
        return plan.getAllValues();
    }

    @Test
    void hubAllSendsEveryEligiblePlayerToTheHub() {
        grant(TeleportExemptions.Type.HUB.allNode());
        Player carol = player("Carol");
        exempt(bob, "knk.teleport.exempt.hub");
        inMode.add(carol);
        teleport.setExemptions(exemptions(List.of(staff, alice, bob, carol)));

        run(staff, "all");

        List<TeleportPlan> plans = startedPlans();
        assertEquals(1, plans.size());
        assertSame(alice, plans.get(0).subject());
        assertEquals(hubSpawn, plans.get(0).destination().get());
        assertEquals(TeleportKind.STAFF, plans.get(0).kind());
        assertEquals(List.of(alice), settled);
        verify(staff).sendMessage(contains("Teleported 1 of 1 player to the hub."));
        verify(alice).sendMessage(contains("Staff sent you to the hub"));
    }

    @Test
    void hubAllWithoutAHubMovesNobody() {
        grant(TeleportExemptions.Type.HUB.allNode());
        hub = Optional.empty();
        teleport.setExemptions(exemptions(List.of(staff, alice, bob)));

        run(staff, "all");

        verify(teleportService, never()).start(any());
        verify(staff).sendMessage(contains(HubTeleportCommand.UNAVAILABLE));
    }

    @Test
    void hubAllNeedsItsNode() {
        grant(TeleportNodes.STAFF_OTHERS);
        teleport.setExemptions(exemptions(List.of(staff, alice, bob)));

        run(staff, "all");

        verify(teleportService, never()).start(any());
        verify(staff).sendMessage(contains("don't have permission"));
    }

    @Test
    void hubOfAnExemptPlayerIsRefused() {
        grant(TeleportNodes.STAFF_OTHERS);
        exempt(bob, "knk.teleport.exempt.hub");
        teleport.setExemptions(exemptions(List.of(staff, alice, bob)));

        run(staff, "Bob");

        verify(teleportService, never()).start(any());
        verify(staff).sendMessage(contains("Bob is exempt"));
    }
}
