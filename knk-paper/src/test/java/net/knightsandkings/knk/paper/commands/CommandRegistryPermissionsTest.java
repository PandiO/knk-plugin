package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.paper.commands.support.CommandPermissions;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KNG-24: /knk subcommands are gated on their metadata node through KnkPermissible, so an in-house
 * grant (including a wildcard the API resolves, such as knk.* for an op-less owner) passes, where
 * plain {@code sender.hasPermission} refused it.
 */
class CommandRegistryPermissionsTest {

    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final Player staff = mock(Player.class);
    private final SubcommandExecutor towns = mock(SubcommandExecutor.class);
    private final SubcommandExecutor help = mock(SubcommandExecutor.class);
    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
        registry.setPermissions(CommandPermissions.of(permissible, Runnable::run));
        registry.register(new CommandMetadata("towns", "Towns", "/knk towns", "knk.admin.towns"), towns);
        registry.register(new CommandMetadata("help", "Help", "/knk help", null), help);
    }

    private void live(String node, PermissionDecision decision) {
        when(permissible.checkAsync(any(), eq(node))).thenReturn(CompletableFuture.completedFuture(decision));
    }

    @Test
    void anInHouseGrantRunsTheSubcommand() {
        live("knk.admin.towns", PermissionDecision.ALLOWED);
        registry.execute(staff, registry.get("towns").orElseThrow(), new String[] {"list"});
        verify(towns).execute(eq(staff), any());
    }

    @Test
    void withoutTheNodeTheSubcommandDoesNotRun() {
        live("knk.admin.towns", PermissionDecision.DENIED);
        registry.execute(staff, registry.get("towns").orElseThrow(), new String[0]);
        verify(towns, never()).execute(any(), any());
        verify(staff).sendMessage(CommandPermissions.NO_PERMISSION_MESSAGE);
    }

    @Test
    void anOpenSubcommandNeedsNoCheck() {
        registry.execute(staff, registry.get("help").orElseThrow(), new String[0]);
        verify(help).execute(eq(staff), any());
        verify(permissible, never()).checkAsync(any(), anyString());
    }

    @Test
    void listingShowsCachedInHouseGrants() {
        assertEquals(List.of("help"), names(registry.listAvailable(staff)));
        when(permissible.hasPermission(staff, "knk.admin.towns")).thenReturn(true);
        assertEquals(List.of("towns", "help"), names(registry.listAvailable(staff)));
    }

    @Test
    void permissionNodesListsEveryGatedSubcommand() {
        assertEquals(Set.of("knk.admin.towns"), registry.permissionNodes());
    }

    private static List<String> names(List<CommandRegistry.RegisteredCommand> commands) {
        return commands.stream().map(c -> c.metadata().name()).toList();
    }

    // KNG-80: a subcommand without a top-level node (each action checks its own) is listed only to
    // senders its visibility predicate accepts; running it is unaffected.
    @Test
    void aVisibilityPredicate_hidesANodelessSubcommandFromListing() {
        registry.register(new CommandMetadata("location", "d", "/knk location", null), towns);
        assertTrue(registry.listAvailable(staff).stream().anyMatch(c -> c.metadata().name().equals("location")));

        registry.setVisibility("location", sender -> false);

        assertTrue(registry.listAvailable(staff).stream().noneMatch(c -> c.metadata().name().equals("location")));
        registry.execute(staff, registry.get("location").orElseThrow(), new String[0]);
        verify(towns).execute(staff, new String[0]);
    }

    // ===== KNG-107: nodeless subcommands listed only to holders of one of their action nodes =====

    @Test
    void visibleToAny_listsANodelessSubcommandOnlyToHoldersOfOneOfItsNodes() {
        registry.register(new CommandMetadata("currency", "d", "/knk currency", null), towns);
        registry.setVisibleToAny("currency", List.of("knk.admin.currency.history", "knk.admin.currency.lock"));

        assertTrue(names(registry.listAvailable(staff)).stream().noneMatch("currency"::equals));
        assertTrue(!registry.isListed(staff, registry.get("currency").orElseThrow()));

        when(permissible.hasPermission(staff, "knk.admin.currency.lock")).thenReturn(true);
        assertTrue(registry.isListed(staff, registry.get("currency").orElseThrow()));
    }

    @Test
    void permissionNodes_includesTheVisibilityNodes_soAListingAsksForThemFirst() {
        registry.setVisibleToAny("currency", List.of("knk.admin.currency.history"));
        registry.setVisibility("location", sender -> false, List.of("knk.admin.location"));
        assertEquals(Set.of("knk.admin.towns", "knk.admin.currency.history", "knk.admin.location"), registry.permissionNodes());
    }

    @Test
    void aNodelessSubcommand_refusesASenderHoldingNoneOfItsNodes_insteadOfShowingItsUsage() {
        SubcommandExecutor currency = mock(SubcommandExecutor.class);
        registry.register(new CommandMetadata("currency", "d", "/knk currency", null), currency);
        registry.setVisibleToAny("currency", List.of("knk.admin.currency.history", "knk.admin.currency.lock"));
        live("knk.admin.currency.history", PermissionDecision.DENIED);
        live("knk.admin.currency.lock", PermissionDecision.DENIED);

        registry.execute(staff, registry.get("currency").orElseThrow(), new String[0]);
        verify(currency, never()).execute(any(), any());
        verify(staff).sendMessage(CommandPermissions.NO_PERMISSION_MESSAGE);

        live("knk.admin.currency.lock", PermissionDecision.ALLOWED);
        registry.execute(staff, registry.get("currency").orElseThrow(), new String[0]);
        verify(currency).execute(eq(staff), any());
    }

    @Test
    void help_listsTheOtherCommandsTheSenderMayUse() {
        List<String> sent = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> sent.add(inv.getArgument(0))).when(staff).sendMessage(anyString());
        HelpSubcommand help = new HelpSubcommand(registry);
        help.setOtherCommands(() -> List.of(
                new HelpSubcommand.OtherCommand("msg", "Message a player", "/msg <player> <message>", List.of("m"), List.of()),
                new HelpSubcommand.OtherCommand("inventory", "Open a player's inventory", "/inventory open <player>", List.of(),
                        List.of("knk.inventory.open"))),
                (sender, nodes) -> nodes.stream().anyMatch(node -> registry.isListed(sender,
                        new CommandRegistry.RegisteredCommand(new CommandMetadata("x", "", "", node), towns))));

        help.execute(staff, new String[0]);
        assertTrue(sent.stream().anyMatch(m -> m.contains("/msg") && m.contains("Message a player")), sent::toString);
        assertTrue(sent.stream().noneMatch(m -> m.contains("/inventory") || m.contains("No commands available")), sent::toString);

        sent.clear();
        help.execute(staff, new String[] {"m"});
        assertTrue(sent.stream().anyMatch(m -> m.contains("/msg <player> <message>")), sent::toString);
        sent.clear();
        help.execute(staff, new String[] {"inventory"});
        assertTrue(sent.stream().anyMatch(m -> m.contains("Unknown command: inventory")), sent::toString);

        when(permissible.hasPermission(staff, "knk.inventory.open")).thenReturn(true);
        assertEquals(List.of("help", "msg", "inventory"), help.completionNames(staff));
    }

    @Test
    void helpDetail_treatsASubcommandTheSenderIsNotOfferedAsUnknown() {
        List<String> sent = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> sent.add(inv.getArgument(0))).when(staff).sendMessage(anyString());

        new HelpSubcommand(registry).execute(staff, new String[] {"towns"});

        assertTrue(sent.stream().anyMatch(m -> m.contains("Unknown command: towns")));
        assertTrue(sent.stream().noneMatch(m -> m.contains("knk.admin.towns")));
    }

    @Test
    void theConsoleIsOfferedEverything() {
        org.bukkit.command.CommandSender console = mock(org.bukkit.command.ConsoleCommandSender.class);
        registry.setVisibleToAny("towns", List.of("knk.admin.whatever"));
        assertTrue(registry.isListed(console, registry.get("towns").orElseThrow()));
    }
}
