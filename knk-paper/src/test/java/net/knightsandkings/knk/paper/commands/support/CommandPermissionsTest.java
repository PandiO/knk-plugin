package net.knightsandkings.knk.paper.commands.support;

import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * KNG-24: commands gated on a node that can be granted in-house - a Bukkit grant or a KnkPermissible
 * grant passes, the console always passes, and a refusal is a clean "no permission" (or "can't be
 * checked" when the API is down) instead of Paper's "Unknown or incomplete command".
 */
class CommandPermissionsTest {

    private static final String NODE = "knk.freeze";

    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final CommandPermissions permissions = CommandPermissions.of(permissible, Runnable::run);
    private final Player staff = mock(Player.class);

    private void inHouse(PermissionDecision decision) {
        when(permissible.checkAsync(any(), anyString())).thenReturn(CompletableFuture.completedFuture(decision));
    }

    private boolean ran(CommandSender sender) {
        AtomicBoolean ran = new AtomicBoolean();
        permissions.whenAllowed(sender, NODE, () -> ran.set(true));
        return ran.get();
    }

    @Test
    void nonOpStaffWithAnInHouseGrantPasses() {
        inHouse(PermissionDecision.ALLOWED);
        assertTrue(ran(staff));
        verify(staff, never()).sendMessage(anyString());
    }

    @Test
    void playerWithoutTheNodeGetsANoPermissionMessage() {
        inHouse(PermissionDecision.DENIED);
        assertFalse(ran(staff));
        verify(staff).sendMessage(CommandPermissions.NO_PERMISSION_MESSAGE);
    }

    @Test
    void anUnreachableServiceIsNotReportedAsNoPermission() {
        inHouse(PermissionDecision.UNAVAILABLE);
        assertFalse(ran(staff));
        verify(staff).sendMessage(contains("can't be checked right now"));
    }

    @Test
    void aFailingCheckCountsAsUnavailable() {
        when(permissible.checkAsync(any(), anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));
        assertFalse(ran(staff));
        verify(staff).sendMessage(contains("can't be checked right now"));
    }

    @Test
    void aBukkitGrantPassesWithoutAskingTheApi() {
        when(staff.hasPermission(NODE)).thenReturn(true);
        assertTrue(ran(staff));
        verifyNoInteractions(permissible);
    }

    @Test
    void theConsoleAlwaysPasses() {
        assertTrue(ran(mock(ConsoleCommandSender.class)));
        verifyNoInteractions(permissible);
    }

    @Test
    void hasUsesTheCachedInHouseAnswer() {
        when(permissible.hasPermission(staff, NODE)).thenReturn(true);
        assertTrue(permissions.has(staff, NODE));
        assertFalse(permissions.has(staff, "knk.unfreeze"));
        assertTrue(permissions.has(staff, null));
    }

    @Test
    void bukkitOnlyRefusesWithoutABukkitGrant() {
        CommandPermissions bukkit = CommandPermissions.bukkitOnly();
        AtomicBoolean ran = new AtomicBoolean();
        bukkit.whenAllowed(staff, NODE, () -> ran.set(true));
        assertFalse(ran.get());
        verify(staff).sendMessage(CommandPermissions.NO_PERMISSION_MESSAGE);
        assertFalse(bukkit.has(staff, NODE));
    }

    @Test
    void warmAsksEveryNodeThenRuns() {
        inHouse(PermissionDecision.DENIED);
        AtomicInteger runs = new AtomicInteger();
        permissions.warm(staff, List.of("a", "b"), runs::incrementAndGet);
        assertEquals(1, runs.get());
        verify(permissible).checkAsync(staff, "a");
        verify(permissible).checkAsync(staff, "b");
    }

    @Test
    void hasAsyncIsTheLiveAnswer() {
        inHouse(PermissionDecision.ALLOWED);
        assertTrue(permissions.hasAsync(staff, NODE).join());
        inHouse(PermissionDecision.UNAVAILABLE);
        assertFalse(permissions.hasAsync(staff, NODE).join());
    }

    // ---- PermissionGatedCommand ----

    @Test
    void gatedCommandRunsTheDelegateOnlyWhenAllowed() {
        CommandExecutor delegate = mock(CommandExecutor.class);
        Command command = mock(Command.class);
        var gated = new PermissionGatedCommand(NODE, delegate, permissions);

        inHouse(PermissionDecision.DENIED);
        assertTrue(gated.onCommand(staff, command, "freeze", new String[] {"Steve", "x"}));
        verify(delegate, never()).onCommand(any(), any(), anyString(), any());

        inHouse(PermissionDecision.ALLOWED);
        gated.onCommand(staff, command, "freeze", new String[] {"Steve", "x"});
        verify(delegate).onCommand(eq(staff), eq(command), eq("freeze"), any());
    }

    @Test
    void gatedCommandCompletesOnlyForHolders() {
        TabExecutor delegate = mock(TabExecutor.class);
        Command command = mock(Command.class);
        when(delegate.onTabComplete(any(), any(), anyString(), any())).thenReturn(List.of("Steve"));
        var gated = new PermissionGatedCommand(NODE, delegate, permissions);

        assertEquals(List.of(), gated.onTabComplete(staff, command, "freeze", new String[] {""}));
        when(permissible.hasPermission(staff, NODE)).thenReturn(true);
        assertEquals(List.of("Steve"), gated.onTabComplete(staff, command, "freeze", new String[] {""}));
    }

    @Test
    void gatedCommandWithoutACompleterKeepsBukkitsDefaultForHolders() {
        when(permissible.hasPermission(staff, NODE)).thenReturn(true);
        var gated = new PermissionGatedCommand(NODE, mock(CommandExecutor.class), permissions);
        assertNull(gated.onTabComplete(staff, mock(Command.class), "freeze", new String[] {""}));
    }
}
