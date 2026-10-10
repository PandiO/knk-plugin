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
}
