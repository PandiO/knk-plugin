package net.knightsandkings.knk.paper.user;

import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.BalanceAdjustmentResult;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.TitleBracket;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.PermissionGroupsQueryApi;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.paper.commands.support.RankHierarchy;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Content port CP8: the shared staff-edit path used by /knk user, /freeze and the Player manager. */
class UserAdminServiceTest {

    private final UsersDataAccess users = mock(UsersDataAccess.class);
    private final UsersCommandApi api = mock(UsersCommandApi.class);
    private final UsersCommandApi acting = mock(UsersCommandApi.class);
    private final PermissionGroupsQueryApi groupsApi = mock(PermissionGroupsQueryApi.class);
    private final RankHierarchy ranks = mock(RankHierarchy.class);
    private final AdminFreezeManager freeze = new AdminFreezeManager();
    private final UserAdminService service = new UserAdminService(Runnable::run, users, api, groupsApi, ranks, null, freeze);
    private final Player staff = mock(Player.class);
    private final UUID staffUuid = UUID.randomUUID();
    private MockedStatic<Bukkit> bukkit;

    private static UserSummary user(int id, String name, int coins, int xp) {
        return new UserSummary(id, name, UUID.randomUUID(), null, coins, 5, xp, true, false, GatePassThroughMethod.DEFAULT,
                ActiveMode.NONE, null, null, 0, null, null, null, false, null, null);
    }

    private final UserSummary target = user(7, "Steve", 250, 120);

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        when(staff.getName()).thenReturn("Admin");
        when(staff.getUniqueId()).thenReturn(staffUuid);
        UserSummary staffUser = user(42, "Admin", 0, 0);
        when(users.getByUuidAsync(staffUuid)).thenReturn(CompletableFuture.completedFuture(FetchResult.hit(staffUser)));
        when(users.getByUsernameAsync("Admin")).thenReturn(CompletableFuture.completedFuture(FetchResult.hit(staffUser)));
        when(api.withActor(42)).thenReturn(acting);
        when(acting.adjustBalancesById(anyInt(), anyInt(), anyInt(), anyInt(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(new BalanceAdjustmentResult(0, 0, 0, null)));
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    void setIsAComputedDeltaThroughTheActorsApi() {
        assertTrue(service.changeBalance(staff, target, "coins", "set", 1000, "because").join());

        verify(acting).adjustBalancesById(7, 750, 0, 0, "because", true);
        verify(staff).sendMessage("§aIncreased Steve's coins by 750 (now 1000).");
    }

    @Test
    void unchangedValueIsReportedWithoutACall() {
        assertFalse(service.changeBalance(staff, target, "coins", "set", 250, "r").join());

        verify(acting, never()).adjustBalancesById(anyInt(), anyInt(), anyInt(), anyInt(), anyString(), anyBoolean());
        verify(staff).sendMessage("§eSteve's coins is already 250.");
    }

    @Test
    void menuStepsAreSignedDeltas() {
        assertTrue(service.adjustBalance(staff, target, "gems", -10, "Player manager").join());

        verify(acting).adjustBalancesById(7, 0, -10, 0, "Player manager", true);
        verify(staff).sendMessage("§aDecreased Steve's gems by 10 (now -5).");
    }

    @Test
    void setTitleMovesXpToTheBracketMinimum() {
        TitleBracket knight = new TitleBracket(3, "Knight", "Dame", 300, 40, 0, 0, 0);

        assertTrue(service.setTitle(staff, target, knight).join());

        verify(acting).adjustBalancesById(7, 0, 0, 180, "Title set to Knight by Admin", true);
    }

    @Test
    void groupChangesNeedTheActorToOutrankTheTarget() {
        PermissionGroupSummary royal = new PermissionGroupSummary(5, "Royal", 30, true, 1.2);
        when(ranks.actorOutranks(42, 7)).thenReturn(CompletableFuture.completedFuture(false));

        assertFalse(service.changeGroup(staff, target, royal, true, null).join());

        verify(acting, never()).addGroupMembership(anyInt(), anyInt(), any());
        verify(staff).sendMessage("§cYou cannot act on a player of equal or higher rank.");
    }

    @Test
    void groupChangeGoesThroughTheActorsApi() {
        PermissionGroupSummary royal = new PermissionGroupSummary(5, "Royal", 30, true, 1.2);
        when(ranks.actorOutranks(42, 7)).thenReturn(CompletableFuture.completedFuture(true));
        when(acting.removeGroupMembership(7, 5)).thenReturn(CompletableFuture.completedFuture(null));

        assertTrue(service.changeGroup(staff, target, royal, false, null).join());

        verify(staff).sendMessage("§aRemoved Steve's membership in Royal.");
    }

    @Test
    void freezeUsesTheActorAndTheConsoleUsesThePlainApi() {
        when(ranks.actorOutranks(42, 7)).thenReturn(CompletableFuture.completedFuture(true));
        when(acting.freezeById(7, "griefing")).thenReturn(CompletableFuture.completedFuture(null));
        when(api.unfreezeById(7)).thenReturn(CompletableFuture.completedFuture(null));

        assertTrue(service.setFrozen(staff, target, true, "griefing").join());
        verify(staff).sendMessage("§aFroze Steve (offline - takes effect on next join).");

        CommandSender console = mock(CommandSender.class);
        assertTrue(service.setFrozen(console, target, false, null).join());
        verify(console).sendMessage("§aUnfroze Steve.");
    }

    @Test
    void kickAndBanRunPapersCommandsAsTheStaffMember() {
        when(staff.performCommand(anyString())).thenReturn(true);

        assertTrue(service.kick(staff, target));
        assertTrue(service.ban(staff, target));

        verify(staff).performCommand("kick Steve " + UserAdminService.KICK_REASON);
        verify(staff).performCommand("ban Steve " + UserAdminService.BAN_REASON);
    }

    @Test
    void modeNeedsAnOnlineTarget() {
        assertFalse(service.setMode(staff, target, ActiveMode.STAFF).join());
        verify(staff).sendMessage("§cSteve must be online to change their mode.");
    }

    @Test
    void missingPropertyNodeIsReported() {
        when(staff.hasPermission("knk.admin.user.coins")).thenReturn(false);
        assertFalse(service.requireProperty(staff, "coins"));
        verify(staff).sendMessage("§cYou don't have permission to manage this player's coins.");
    }

    @Test
    void actorApiFallsBackToThePlainApi() {
        assertSame(acting, service.actorApi(staff).join());
        assertSame(api, service.actorApi(mock(CommandSender.class)).join());
        when(users.getByUuidAsync(staffUuid)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
        assertSame(api, service.actorApi(staff).join());
    }

    @Test
    void groupByNameReportsAnUnknownGroup() {
        when(groupsApi.list()).thenReturn(CompletableFuture.completedFuture(List.of()));
        assertFalse(service.changeGroupByName(staff, target, "Nope", true, null).join());
        verify(staff).sendMessage(eq("§cNo PermissionGroup named 'Nope'."));
    }
}
