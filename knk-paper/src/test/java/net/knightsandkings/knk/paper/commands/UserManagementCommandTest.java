package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.paper.user.UserAdminService;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Content port CP8: /knk user and /freeze only parse arguments - the work is UserAdminService's (shared with the menu). */
class UserManagementCommandTest {

    private final UserAdminService service = mock(UserAdminService.class);
    private final CommandSender sender = mock(CommandSender.class);
    private final UserSummary steve = new UserSummary(7, "Steve", UUID.randomUUID(), 250);

    UserManagementCommandTest() {
        doAnswer(inv -> {
            Consumer<UserSummary> onFound = inv.getArgument(2);
            onFound.accept(steve);
            return null;
        }).when(service).resolveTarget(any(), anyString(), any());
    }

    @Test
    void balanceCommandDelegates() {
        when(service.requireProperty(sender, "coins")).thenReturn(true);

        new UserManagementCommand(service).onCommand(sender, null, "knk", new String[] {"Steve", "coins", "set", "1000", "bonus"});

        verify(service).changeBalance(sender, steve, "coins", "set", 1000, "bonus");
    }

    @Test
    void coinsAndGemsNeedAReason() {
        when(service.requireProperty(eq(sender), anyString())).thenReturn(true);
        UserManagementCommand command = new UserManagementCommand(service);

        command.onCommand(sender, null, "knk", new String[] {"Steve", "coins", "add", "1000"});
        command.onCommand(sender, null, "knk", new String[] {"Steve", "gems", "remove", "5", " "});

        verify(service, never()).changeBalance(any(), any(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(sender).sendMessage("§cGive a reason: /knk user Steve coins add 1000 <reason...>");
    }

    @Test
    void xpKeepsTheOptionalReason() {
        when(service.requireProperty(sender, "xp")).thenReturn(true);

        new UserManagementCommand(service).onCommand(sender, null, "knk", new String[] {"Steve", "xp", "add", "5"});

        verify(service).changeBalance(sender, steve, "xp", "add", 5, null);
    }

    @Test
    void groupAndPermCommandsDelegate() {
        when(service.requireProperty(eq(sender), anyString())).thenReturn(true);
        UserManagementCommand command = new UserManagementCommand(service);

        command.onCommand(sender, null, "knk", new String[] {"Steve", "group", "add", "Royal"});
        command.onCommand(sender, null, "knk", new String[] {"Steve", "perm", "revoke", "knk.x"});

        verify(service).changeGroupByName(sender, steve, "Royal", true, null);
        verify(service).changePermission(sender, steve, "knk.x", false, null);
    }

    @Test
    void missingNodeStopsBeforeTheLookup() {
        when(service.requireProperty(sender, "xp")).thenReturn(false);

        new UserManagementCommand(service).onCommand(sender, null, "knk", new String[] {"Steve", "xp", "add", "5"});

        verify(service, never()).resolveTarget(any(), anyString(), any());
    }

    @Test
    void freezeCommandDelegates() {
        new FreezeCommand(service, true).onCommand(sender, null, "freeze", new String[] {"Steve", "griefing", "again"});
        new FreezeCommand(service, false).onCommand(sender, null, "unfreeze", new String[] {"Steve"});

        verify(service).setFrozen(sender, steve, true, "griefing again");
        verify(service).setFrozen(sender, steve, false, null);
    }
}
