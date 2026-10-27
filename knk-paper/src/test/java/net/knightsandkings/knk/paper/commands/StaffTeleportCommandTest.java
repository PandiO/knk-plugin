package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.teleport.TeleportKind;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.paper.commands.support.PlayerCommandSupport;
import net.knightsandkings.knk.paper.commands.support.TargetRankCheck;
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
 * /tp, /tphere and /knk tp (docs/specs/teleport/DESIGN.md §3.2, Phase 1): argument forms, which node
 * each checks, the rank check against every other player involved, vanish-aware lookup, silent mode,
 * and that everything goes through the teleport engine.
 */
class StaffTeleportCommandTest {

    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final Set<String> granted = new HashSet<>();
    private final List<String> checkedNodes = new ArrayList<>();
    private final World world = mock(World.class);
    private final Player staff = player("Staff", new Location(world, 10, 64, 10, 45, 10));
    private final Player alice = player("Alice", new Location(world, 0, 64, 0));
    private final Player bob = player("Bob", new Location(world, 100, 64, 100));
    private final Set<Player> vanished = new HashSet<>();
    private final Map<String, Player> online = Map.of("staff", staff, "alice", alice, "bob", bob);
    private final PlayerCommandSupport support = new PlayerCommandSupport(permissible, Runnable::run,
        name -> online.get(name.toLowerCase()), () -> List.of(staff, alice, bob));
    private final VisibleTargetResolver targets = new VisibleTargetResolver(
        name -> online.get(name.toLowerCase()), () -> List.of(staff, alice, bob), vanished::contains);

    /** Staff outranks whoever isn't in {@link #higherRanked}. */
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
    private final StaffTeleportCommand tp = new StaffTeleportCommand(StaffTeleportCommand.Form.TP, support, rankCheck,
        targets, teleportService, vanished::contains, name -> "world".equals(name) ? world : null, () -> List.of("world"));
    private final StaffTeleportCommand tphere = tp.withForm(StaffTeleportCommand.Form.TPHERE);

    StaffTeleportCommandTest() {
        when(world.getName()).thenReturn("world");
        when(permissible.hasPermissionAsync(any(), anyString())).thenAnswer(inv -> {
            String node = inv.getArgument(1);
            checkedNodes.add(node);
            return CompletableFuture.completedFuture(granted.contains(node));
        });
        // PlayerCommandSupport asks checkAsync (allowed / denied / API unreachable); same grants.
        when(permissible.checkAsync(any(), anyString())).thenAnswer(inv -> permissible
            .hasPermissionAsync(inv.getArgument(0), inv.getArgument(1))
            .thenApply(net.knightsandkings.knk.core.domain.permissions.PermissionDecision::of));
        when(teleportService.start(any())).thenReturn(CompletableFuture.completedFuture(TeleportOutcome.teleported()));
    }

    private static Player player(String name, Location location) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(location);
        return player;
    }

    private void grant(String... nodes) {
        granted.addAll(List.of(nodes));
    }

    private static void run(StaffTeleportCommand command, CommandSender sender, String... args) {
        command.onCommand(sender, mock(Command.class), "tp", args);
    }

    private TeleportPlan startedPlan() {
        ArgumentCaptor<TeleportPlan> plan = ArgumentCaptor.forClass(TeleportPlan.class);
        verify(teleportService).start(plan.capture());
        return plan.getValue();
    }

    // ===== /tp <player> =====

    @Test
    void tpToAPlayerMovesYouThereAndTellsThem() {
        grant(TeleportNodes.STAFF);

        run(tp, staff, "Alice");

        TeleportPlan plan = startedPlan();
        assertSame(staff, plan.subject());
        assertSame(alice, plan.visited());
        assertEquals(TeleportKind.STAFF, plan.kind());
        assertFalse(plan.silent());
        assertEquals(List.of("Alice"), rankChecked);
        verify(staff).sendMessage(contains("Teleported to Alice"));
        verify(alice).sendMessage(contains("Staff teleported to you"));
    }

    @Test
    void legacyAdminTpNodeStillWorksForTpToAPlayer() {
        grant(TeleportNodes.LEGACY_ADMIN_TP);

        run(tp, staff, "Alice");

        startedPlan();
        assertTrue(checkedNodes.containsAll(List.of(TeleportNodes.STAFF, TeleportNodes.LEGACY_ADMIN_TP)));
    }

    @Test
    void tpWithoutTheNodeIsRefused() {
        run(tp, staff, "Alice");

        verify(teleportService, never()).start(any());
        verify(staff).sendMessage(contains("permission"));
    }

    @Test
    void tpToAHigherRankedPlayerIsRefused() {
        grant(TeleportNodes.STAFF);
        higherRanked.add("Alice");

        run(tp, staff, "Alice");

        verify(teleportService, never()).start(any());
    }

    @Test
    void vanishedTargetLooksOfflineToAViewerWhoCantSeeThem() {
        grant(TeleportNodes.STAFF);
        vanished.add(bob);
        when(alice.canSee(bob)).thenReturn(false);

        run(tp, alice, "Bob");

        verify(alice).sendMessage("§cNo online player named 'Bob'.");
        verify(teleportService, never()).start(any());
        assertFalse(targets.complete(alice, "").contains("Bob"));
    }

    @Test
    void vanishedTargetIsFoundByStaffWhoCanSeeThem() {
        grant(TeleportNodes.STAFF);
        vanished.add(bob);
        when(staff.canSee(bob)).thenReturn(true);

        run(tp, staff, "Bob");

        assertSame(bob, startedPlan().visited());
        assertTrue(targets.complete(staff, "B").contains("Bob"));
    }

    // ===== /tp <a> <b> =====

    @Test
    void tpPlayerToPlayerNeedsTheOthersNodeAndOutranksBoth() {
        grant(TeleportNodes.STAFF_OTHERS);

        run(tp, staff, "Alice", "Bob");

        TeleportPlan plan = startedPlan();
        assertSame(alice, plan.subject());
        assertSame(bob, plan.visited());
        assertEquals(List.of(TeleportNodes.STAFF_OTHERS), checkedNodes);
        assertEquals(List.of("Alice", "Bob"), rankChecked);
        verify(alice).sendMessage(contains("Staff teleported you to Bob"));
        verify(bob).sendMessage(contains("Alice was teleported to you"));
    }

    @Test
    void tpPlayerToPlayerIsRefusedWhenYouDontOutrankEither() {
        grant(TeleportNodes.STAFF_OTHERS);
        higherRanked.add("Bob");

        run(tp, staff, "Alice", "Bob");

        verify(teleportService, never()).start(any());
    }

    @Test
    void consoleMayMovePlayersAround() {
        CommandSender console = mock(CommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");

        run(tp, console, "Alice", "Bob");

        assertSame(alice, startedPlan().subject());
        assertTrue(checkedNodes.isEmpty(), "the console isn't permission-checked");
    }

    @Test
    void consoleCanNotTeleportItself() {
        CommandSender console = mock(CommandSender.class);

        run(tp, console, "Alice");

        verify(teleportService, never()).start(any());
    }

    // ===== silent =====

    @Test
    void silentFlagNeedsItsNodeAndKeepsTheTargetUninformed() {
        grant(TeleportNodes.STAFF, TeleportNodes.STAFF_SILENT);

        run(tp, staff, "Alice", "-s");

        assertTrue(startedPlan().silent());
        verify(alice, never()).sendMessage(anyString());
    }

    @Test
    void silentFlagWithoutItsNodeIsRefused() {
        grant(TeleportNodes.STAFF);

        run(tp, staff, "Alice", "silent");

        verify(teleportService, never()).start(any());
    }

    @Test
    void vanishedStaffAreAlwaysSilent() {
        grant(TeleportNodes.STAFF);
        vanished.add(staff);

        run(tp, staff, "Alice");

        assertTrue(startedPlan().silent());
        assertFalse(checkedNodes.contains(TeleportNodes.STAFF_SILENT));
    }

    // ===== /tphere, coordinates, /knk tp =====

    @Test
    void tphereBringsThePlayerToYou() {
        grant(TeleportNodes.STAFF_OTHERS);

        run(tphere, staff, "Alice");

        TeleportPlan plan = startedPlan();
        assertSame(alice, plan.subject());
        assertSame(staff, plan.visited());
        assertEquals(List.of("Alice"), rankChecked);
    }

    @Test
    void tpToCoordinatesSupportsRelativeValuesAndYawPitch() {
        grant(TeleportNodes.STAFF);

        run(tp, staff, "~", "70", "~-5", "90", "~");

        TeleportPlan plan = startedPlan();
        Location destination = plan.destination().get();
        assertEquals(10, destination.getX());
        assertEquals(70, destination.getY());
        assertEquals(5, destination.getZ());
        assertEquals(90f, destination.getYaw());
        assertEquals(10f, destination.getPitch());
        assertSame(world, destination.getWorld());
        assertTrue(rankChecked.isEmpty());
    }

    @Test
    void tpToCoordinatesInAnUnknownWorldIsRefused() {
        grant(TeleportNodes.STAFF);

        run(tp, staff, "1", "2", "3", "nether_nope");

        verify(teleportService, never()).start(any());
        verify(staff).sendMessage(contains("Unknown world"));
    }

    @Test
    void coordinateParsing() {
        assertEquals(5.0, StaffTeleportCommand.coordinate("~5", 0));
        assertEquals(-2.5, StaffTeleportCommand.coordinate("-2.5", 99));
        assertEquals(7.0, StaffTeleportCommand.coordinate("~", 7));
        assertNull(StaffTeleportCommand.coordinate("Alice", 0));
        assertNull(StaffTeleportCommand.coordinate("NaN", 0));
        assertNull(StaffTeleportCommand.coordinate("1d", 0));
    }

    @Test
    void knkTpDelegatesWithoutCheckingTheStaffNodeAgain() {
        // /knk already checked knk.admin.tp through its own registry.
        TeleportToPlayerCommand knkTp = new TeleportToPlayerCommand(tp);

        knkTp.onCommand(staff, new String[] {"Alice"});

        TeleportPlan plan = startedPlan();
        assertSame(staff, plan.subject());
        assertSame(alice, plan.visited());
        assertTrue(checkedNodes.isEmpty());
        assertEquals(List.of("Alice"), rankChecked);
    }

    @Test
    void refusalFromTheEngineIsReportedWithTheSubjectsName() {
        grant(TeleportNodes.STAFF_OTHERS);
        when(teleportService.start(any())).thenReturn(CompletableFuture.completedFuture(
            TeleportOutcome.denied(net.knightsandkings.knk.core.teleport.TeleportDenial.of("siege", "Alice is in a siege match."))));

        run(tp, staff, "Alice", "Bob");

        verify(staff).sendMessage("§cCan't teleport Alice: Alice is in a siege match.");
        verify(alice, never()).sendMessage(anyString());
    }

    // ===== KNG-107: tab completion offers only what the sender can run =====

    private List<String> complete(StaffTeleportCommand command, String... args) {
        when(permissible.hasPermission(any(Player.class), anyString()))
            .thenAnswer(inv -> granted.contains(inv.<String>getArgument(1)));
        return command.onTabComplete(staff, mock(Command.class), "tp", args);
    }

    @Test
    void completion_withoutNodes_suggestsNothing() {
        assertEquals(List.of(), complete(tp, "a"));
        assertEquals(List.of(), complete(tp, "Alice", ""));
        assertEquals(List.of(), complete(tp, "10", ""));
        assertEquals(List.of(), complete(tphere, "a"));
    }

    @Test
    void completion_withTheStaffNodeOnly_namesButNoSecondPlayerOrSilentFlag() {
        grant(TeleportNodes.STAFF);
        assertEquals(List.of("Alice"), complete(tp, "a"));
        assertEquals(List.of(), complete(tp, "Alice", ""));
        assertEquals(List.of("~"), complete(tp, "10", ""));
        assertEquals(List.of(), complete(tphere, "a"));
    }

    @Test
    void completion_withOthersAndSilent_everything() {
        grant(TeleportNodes.STAFF_OTHERS, TeleportNodes.STAFF_SILENT);
        assertEquals(List.of("Bob"), complete(tp, "Alice", "b"));
        assertEquals(List.of("-s"), complete(tp, "Alice", "-"));
        assertEquals(List.of("Alice"), complete(tphere, "a"));
        assertEquals(List.of("-s"), complete(tphere, "Alice", ""));
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
    void tpAllSendsEveryEligiblePlayerToTheTargetAndReportsTheCount() {
        grant(TeleportExemptions.Type.TP.allNode());
        Player carol = player("Carol", new Location(world, 5, 64, 5));
        Player dave = player("Dave", new Location(world, 6, 64, 6));
        Player erin = player("Erin", new Location(world, 7, 64, 7));
        exempt(carol, "knk.teleport.exempt.tp");
        inMode.add(dave);
        higherForAll.add(erin);
        tp.setExemptions(exemptions(List.of(staff, alice, bob, carol, dave, erin)));

        run(tp, staff, "all", "Bob");

        List<TeleportPlan> plans = startedPlans();
        assertEquals(1, plans.size());
        assertSame(alice, plans.get(0).subject());
        assertSame(bob, plans.get(0).visited());
        assertEquals(List.of("Bob"), rankChecked);
        verify(staff).sendMessage(contains("Teleported 1 of 1 player to Bob."));
        verify(alice).sendMessage(contains("Staff teleported you to Bob"));
    }

    @Test
    void tpAllNeedsItsOwnNode() {
        grant(TeleportNodes.STAFF_OTHERS);
        tp.setExemptions(exemptions(List.of(staff, alice, bob)));

        run(tp, staff, "all", "Bob");

        verify(teleportService, never()).start(any());
        verify(staff).sendMessage(contains("don't have permission"));
    }

    @Test
    void tphereAllBringsEveryEligiblePlayerSilently() {
        grant(TeleportExemptions.Type.TPHERE.allNode(), TeleportNodes.STAFF_SILENT);
        exempt(bob, "knk.teleport.exempt.tphere");
        tp.setExemptions(exemptions(List.of(staff, alice, bob)));
        StaffTeleportCommand here = tp.withForm(StaffTeleportCommand.Form.TPHERE);

        run(here, staff, "all", "-s");

        List<TeleportPlan> plans = startedPlans();
        assertEquals(1, plans.size());
        assertSame(alice, plans.get(0).subject());
        assertSame(staff, plans.get(0).visited());
        assertTrue(plans.get(0).silent());
        verify(alice, never()).sendMessage(anyString());
    }

    @Test
    void tphereRefusesAnExemptPlayerUnlessOverridden() {
        grant(TeleportNodes.STAFF_OTHERS);
        exempt(alice, "knk.teleport.exempt.tphere");
        tp.setExemptions(exemptions(List.of(staff, alice, bob)));
        StaffTeleportCommand here = tp.withForm(StaffTeleportCommand.Form.TPHERE);

        run(here, staff, "Alice");
        verify(teleportService, never()).start(any());
        verify(staff).sendMessage(contains("Alice is exempt"));

        overridesExemption = true;
        run(here, staff, "Alice");
        assertSame(alice, startedPlan().subject());
    }

    @Test
    void tpOfAPlayerInStaffModeIsRefused() {
        grant(TeleportNodes.STAFF_OTHERS);
        inMode.add(alice);
        tp.setExemptions(exemptions(List.of(staff, alice, bob)));

        run(tp, staff, "Alice", "Bob");

        verify(teleportService, never()).start(any());
        verify(staff).sendMessage(contains("staff or owner mode"));
    }

    @Test
    void withoutExemptionsAllIsAPlayerName() {
        grant(TeleportNodes.STAFF);

        run(tp, staff, "all");

        verify(staff).sendMessage(contains("No online player named 'all'"));
    }

    @Test
    void completionOffersAllToHoldersOfItsNode() {
        tp.setExemptions(exemptions(List.of(staff, alice, bob)));
        grant(TeleportExemptions.Type.TP.allNode());
        assertEquals(List.of("all"), complete(tp, "a"));
        assertEquals(List.of("Bob"), complete(tp, "all", "b"));
        assertEquals(List.of(), complete(tp.withForm(StaffTeleportCommand.Form.TPHERE), "a"));
    }
}
