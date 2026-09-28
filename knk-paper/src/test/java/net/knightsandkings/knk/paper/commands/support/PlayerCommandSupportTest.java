package net.knightsandkings.knk.paper.commands.support;

import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlayerCommandSupport#whenAnyAllowed}: one allowed node is enough, and an unreachable
 * permission service is reported as such rather than as "no permission" (same rule as whenAllowed).
 */
class PlayerCommandSupportTest {

    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final PlayerCommandSupport support = new PlayerCommandSupport(permissible, Runnable::run, name -> null, List::of);
    private final Player player = mock(Player.class);

    private void decisions(Map<String, PermissionDecision> byNode) {
        when(permissible.checkAsync(any(), anyString())).thenAnswer(inv ->
            CompletableFuture.completedFuture(byNode.getOrDefault((String) inv.getArgument(1), PermissionDecision.DENIED)));
    }

    private boolean ran(List<String> nodes) {
        AtomicBoolean ran = new AtomicBoolean();
        support.whenAnyAllowed(player, nodes, () -> ran.set(true));
        return ran.get();
    }

    @Test
    void anyAllowedNodeRunsEvenIfAnotherCouldntBeChecked() {
        decisions(Map.of("a", PermissionDecision.UNAVAILABLE, "b", PermissionDecision.ALLOWED));
        assertTrue(ran(List.of("a", "b")));
    }

    @Test
    void allDeniedSaysNoPermission() {
        decisions(Map.of());
        assertFalse(ran(List.of("a", "b")));
        verify(player).sendMessage(contains("You don't have permission"));
    }

    @Test
    void anUnreachableServiceIsNotReportedAsNoPermission() {
        decisions(Map.of("a", PermissionDecision.DENIED, "b", PermissionDecision.UNAVAILABLE));
        assertFalse(ran(List.of("a", "b")));
        verify(player).sendMessage(PlayerCommandSupport.UNAVAILABLE_MESSAGE);
        verify(player, never()).sendMessage(contains("You don't have permission"));
    }

    @Test
    void aFailedCheckCountsAsUnavailable() {
        when(permissible.checkAsync(any(), anyString())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
        assertFalse(ran(List.of("a")));
        verify(player).sendMessage(PlayerCommandSupport.UNAVAILABLE_MESSAGE);
    }
}
