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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private final List<Object[]> redrawn = new java.util.ArrayList<>();
    private final UserAdminService service = new UserAdminService(Runnable::run, users, api, groupsApi, ranks, null, freeze,
            (player, summary) -> redrawn.add(new Object[] { player, summary }));
    private final Player staff = mock(Player.class);
    private final UUID staffUuid = UUID.randomUUID();
    private MockedStatic<Bukkit> bukkit;

    private static UserSummary user(int id, String name, int coins, int xp) {
        return new UserSummary(id, name, UUID.randomUUID(), null, coins, 5, xp, true, false, GatePassThroughMethod.DEFAULT,
                ActiveMode.NONE, null, null, 0, null, null, null, false, null, null);
    }

    private final UserSummary target = user(7, "Steve", 250, 120);

    /** Steve as the API returns him after a change, holding {@code tierName} (id {@code tierId}). */
    private UserSummary steveWithTier(Integer tierId, String tierName) {
        return new UserSummary(7, "Steve", target.uuid(), null, 250, 5, 120, true, false, GatePassThroughMethod.DEFAULT,
                ActiveMode.NONE, null, null, 0, tierId, tierName, null, false, null, null, "&e", "&6", "&e");
    }

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
        when(users.refreshAsync(target.uuid()))
                .thenReturn(CompletableFuture.completedFuture(FetchResult.missFetched(steveWithTier(null, null))));
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

    // ===== re-sync after a change (premium tier / title shown in chat and the tab list) =====

    @Test
    void groupChangeRefreshesTheTargetAndRedrawsTheirTabList() {
        PermissionGroupSummary noble = new PermissionGroupSummary(4, "Noble", 10, true, 1.1);
        UserSummary fresh = steveWithTier(4, "Noble");
        Player online = mock(Player.class);
        bukkit.when(() -> Bukkit.getPlayer(target.uuid())).thenReturn(online);
        when(ranks.actorOutranks(42, 7)).thenReturn(CompletableFuture.completedFuture(true));
        when(acting.addGroupMembership(7, 4, null)).thenReturn(CompletableFuture.completedFuture(null));
        when(users.refreshAsync(target.uuid())).thenReturn(CompletableFuture.completedFuture(FetchResult.missFetched(fresh)));

        assertTrue(service.changeGroup(staff, target, noble, true, null).join());

        verify(users).refreshAsync(target.uuid());
        assertEquals(1, redrawn.size());
        assertSame(online, redrawn.get(0)[0]);
        assertSame(fresh, redrawn.get(0)[1]);
        verify(staff).sendMessage("§7Steve's displayed premium tier is now Noble.");
    }

    @Test
    void addingALowerTierNextToAHigherOneSaysTheHigherOneStillShows() {
        PermissionGroupSummary noble = new PermissionGroupSummary(4, "Noble", 10, true, 1.1);
        when(ranks.actorOutranks(42, 7)).thenReturn(CompletableFuture.completedFuture(true));
        when(acting.addGroupMembership(7, 4, null)).thenReturn(CompletableFuture.completedFuture(null));
        when(users.refreshAsync(target.uuid()))
                .thenReturn(CompletableFuture.completedFuture(FetchResult.missFetched(steveWithTier(5, "Royal"))));

        assertTrue(service.changeGroup(staff, target, noble, true, null).join());

        verify(staff).sendMessage("§eSteve's displayed premium tier is still Royal (the highest-weight tier wins)"
                + " - remove Royal to show Noble.");
    }

    @Test
    void offlineTargetIsStillRefreshedButNothingIsRedrawn() {
        PermissionGroupSummary royal = new PermissionGroupSummary(5, "Royal", 30, true, 1.2);
        when(ranks.actorOutranks(42, 7)).thenReturn(CompletableFuture.completedFuture(true));
        when(acting.removeGroupMembership(7, 5)).thenReturn(CompletableFuture.completedFuture(null));

        assertTrue(service.changeGroup(staff, target, royal, false, null).join());

        verify(users).refreshAsync(target.uuid());
        assertTrue(redrawn.isEmpty());
        verify(staff).sendMessage("§7Steve's displayed premium tier is now none.");
    }

    @Test
    void aFailedRefreshDoesNotFailTheChange() {
        PermissionGroupSummary staffGroup = new PermissionGroupSummary(9, "Staff", 100, false, 1.0);
        when(ranks.actorOutranks(42, 7)).thenReturn(CompletableFuture.completedFuture(true));
        when(acting.addGroupMembership(7, 9, null)).thenReturn(CompletableFuture.completedFuture(null));
        when(users.refreshAsync(target.uuid())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));

        assertTrue(service.changeGroup(staff, target, staffGroup, true, null).join());

        verify(staff).sendMessage("§aAdded Steve's membership in Staff (permanent).");
        assertTrue(redrawn.isEmpty());
    }

    @Test
    void titleChangeRefreshesTheTargetAndRedrawsTheirTabList() {
        TitleBracket knight = new TitleBracket(3, "Knight", "Dame", 300, 40, 0, 0, 0);
        Player online = mock(Player.class);
        bukkit.when(() -> Bukkit.getPlayer(target.uuid())).thenReturn(online);

        assertTrue(service.setTitle(staff, target, knight).join());

        verify(users).refreshAsync(target.uuid());
        assertEquals(1, redrawn.size());
        assertSame(online, redrawn.get(0)[0]);
    }

    // ===== rank switch (Player manager) =====

    @Test
    void setRankOnlyAddsTheNewRank_theApiReplacesTheOldOne() {
        PermissionGroupSummary royal = new PermissionGroupSummary(5, "Royal", 30, true, 1.2);
        PermissionGroupSummary noble = new PermissionGroupSummary(4, "Noble", 10, true);
        when(ranks.actorOutranks(42, 7)).thenReturn(CompletableFuture.completedFuture(true));
        when(acting.addGroupMembership(7, 5, null)).thenReturn(CompletableFuture.completedFuture(null));

        assertTrue(service.setRank(staff, target, royal, List.of(noble)).join());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(acting, users);
        order.verify(acting).addGroupMembership(7, 5, null);
        order.verify(users).refreshAsync(target.uuid());
        verify(acting, never()).removeGroupMembership(anyInt(), anyInt());
        verify(staff).sendMessage("§aSet Steve's rank to Royal (was Noble).");
    }

    @Test
    void setRankWithNothingReplacedSaysSo() {
        PermissionGroupSummary royal = new PermissionGroupSummary(5, "Royal", 30, true, 1.2);
        when(ranks.actorOutranks(42, 7)).thenReturn(CompletableFuture.completedFuture(true));
        when(acting.addGroupMembership(7, 5, null)).thenReturn(CompletableFuture.completedFuture(null));

        assertTrue(service.setRank(staff, target, royal, List.of(royal)).join());

        verify(staff).sendMessage("§aSet Steve's rank to Royal.");
    }

    @Test
    void setRankReportsAFailureAndStillRefreshes() {
        PermissionGroupSummary royal = new PermissionGroupSummary(5, "Royal", 30, true, 1.2);
        when(ranks.actorOutranks(42, 7)).thenReturn(CompletableFuture.completedFuture(true));
        when(acting.addGroupMembership(7, 5, null)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));

        assertFalse(service.setRank(staff, target, royal, List.of()).join());

        verify(users).refreshAsync(target.uuid());
        verify(staff).sendMessage(org.mockito.ArgumentMatchers.startsWith("§cFailed: "));
    }

    @Test
    void resyncDisplayReReadsTheOnlinePlayerAndRedrawsThem() {
        Player online = mock(Player.class);
        when(online.getUniqueId()).thenReturn(target.uuid());
        bukkit.when(() -> Bukkit.getPlayer(target.uuid())).thenReturn(online);

        service.resyncDisplay(online);

        verify(users).refreshAsync(target.uuid());
        assertEquals(1, redrawn.size());
        assertSame(online, redrawn.get(0)[0]);
    }
}
