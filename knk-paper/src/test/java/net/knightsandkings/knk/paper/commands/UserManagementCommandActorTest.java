package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Content port CP7: {@code /knk user} mutations go through the API attributed to the acting player. */
class UserManagementCommandActorTest {

    private final UsersDataAccess users = mock(UsersDataAccess.class);
    private final UsersCommandApi api = mock(UsersCommandApi.class);
    private final UsersCommandApi acting = mock(UsersCommandApi.class);
    private final UserManagementCommand command = new UserManagementCommand(null, users, api, null, null, null);

    @Test
    void playerSenderActsAsTheirOwnUser() {
        UUID uuid = UUID.randomUUID();
        Player staff = mock(Player.class);
        when(staff.getUniqueId()).thenReturn(uuid);
        when(users.getByUuidAsync(uuid)).thenReturn(CompletableFuture.completedFuture(
                FetchResult.hit(new UserSummary(42, "Staff", uuid, 0))));
        when(api.withActor(42)).thenReturn(acting);

        assertSame(acting, command.actorApi(staff).join());
    }

    @Test
    void consoleAndUnresolvableAccountsUseThePlainApi() {
        assertSame(api, command.actorApi(mock(CommandSender.class)).join());

        UUID uuid = UUID.randomUUID();
        Player staff = mock(Player.class);
        when(staff.getUniqueId()).thenReturn(uuid);
        when(users.getByUuidAsync(uuid)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
        assertSame(api, command.actorApi(staff).join());
    }
}
