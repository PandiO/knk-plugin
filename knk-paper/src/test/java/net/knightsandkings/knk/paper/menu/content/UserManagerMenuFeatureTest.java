package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.PermissionGroupsDataAccess;
import net.knightsandkings.knk.core.dataaccess.TitleBracketsDataAccess;
import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.GroupMembershipSummary;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.menu.ConditionOutcome;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.menu.MenuSessionRegistry;
import net.knightsandkings.knk.core.menu.MenuStateView;
import net.knightsandkings.knk.core.menu.MenuVariableProviderRegistry;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.menu.VariableResolver;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.knightsandkings.knk.paper.menu.MenuService;
import net.knightsandkings.knk.paper.user.UserAdminService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Content port CP8: the Player manager's reads, conditions, actions and seeds. */
class UserManagerMenuFeatureTest {

    private final UserAdminService admin = mock(UserAdminService.class);
    private final UsersQueryApi usersQueryApi = mock(UsersQueryApi.class);
    private final UserCache cache = new UserCache(Duration.ofMinutes(5));
    private final List<Player> online = new ArrayList<>();
    private final PermissionGroupsDataAccess groups = new PermissionGroupsDataAccess(() -> CompletableFuture.completedFuture(List.of(
            new PermissionGroupSummary(1, "Default", 0, false, 1.0),
            new PermissionGroupSummary(5, "Royal", 30, true, 1.2))), Duration.ofMinutes(2), Clock.systemUTC());
    private final TitleBracketsDataAccess titles = new TitleBracketsDataAccess(
            () -> CompletableFuture.completedFuture(ProfileMenuFeatureTest.BRACKETS), Duration.ofMinutes(10));
    private final UserManagerMenuFeature feature = new UserManagerMenuFeature(admin, usersQueryApi, cache, titles, groups, () -> online);
    private final MenuFeatureRegistries registries = ContentFeatures.all(feature);
    private final MenuService menuService = mock(MenuService.class);

    private final Player staff = player("Admin");
    private final UserSummary staffUser = user(42, "Admin", staff.getUniqueId());
    private final Player steve = player("Steve");
    private final UserSummary steveUser = user(7, "Steve", steve.getUniqueId());
    private final Player owner = player("Owner");
    private final UserSummary ownerUser = user(1, "Owner", owner.getUniqueId());

    UserManagerMenuFeatureTest() {
        when(staff.hasPermission(UserManagerMenuFeature.MANAGE_NODE)).thenReturn(true);
        cache.put(staffUser);
        cache.put(steveUser);
        cache.put(ownerUser);
        online.addAll(List.of(staff, owner, steve));
        when(admin.outranks(42, 7)).thenReturn(CompletableFuture.completedFuture(true));
        when(admin.outranks(42, 1)).thenReturn(CompletableFuture.completedFuture(false));
        when(usersQueryApi.getByUsername("Steve")).thenReturn(CompletableFuture.completedFuture(steveUser));
        when(admin.requireProperty(any(), anyString())).thenReturn(true);
        when(admin.adjustBalance(any(), any(), anyString(), anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(true));
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        return player;
    }

    private static UserSummary user(int id, String name, UUID uuid) {
        return new UserSummary(id, name, uuid, null, 100, 5, 150, true, false, GatePassThroughMethod.DEFAULT,
                ActiveMode.NONE, 2, "Squire", 0, null, null, null, false, null, "Male");
    }

    private MenuContentSourceContext sourceContext(Player viewer, MenuContextParams ctx) {
        return new MenuContentSourceContext(viewer, null, ctx);
    }

    private MenuActionContext actionContext(MenuSession session) {
        return new MenuActionContext(staff, session, Map.of(), menuService, null, null, null, null);
    }

    private void loadSteve() {
        feature.fetchTarget(sourceContext(staff, MenuContextParams.EMPTY), Map.of("userId", "7", "name", "Steve")).join();
    }

    @Test
    void onlineListShowsOnlyPlayersTheViewerOutranks() {
        List<OnlinePlayerRow> rows = feature.fetchOnline(sourceContext(staff, MenuContextParams.EMPTY)).join().items();

        assertEquals(List.of("Steve"), rows.stream().map(OnlinePlayerRow::getName).toList());
        assertEquals(7, rows.get(0).getUserId());
        assertTrue(rows.get(0).getLoreLines().contains("&eClick to edit"));
    }

    @Test
    void onlineListIsEmptyWithoutTheManageNode() {
        when(staff.hasPermission(UserManagerMenuFeature.MANAGE_NODE)).thenReturn(false);

        List<OnlinePlayerRow> rows = feature.fetchOnline(sourceContext(staff, MenuContextParams.EMPTY)).join().items();

        assertEquals(1, rows.size());
        assertEquals("DISABLED", rows.get(0).getDisplayMode());
    }

    @Test
    void targetFetchFeedsTheRootAndTheOutranksCondition() {
        MenuContextParams ctx = MenuContextParams.of(Map.of("userId", "7", "name", "Steve"));
        assertFalse(feature.targetView(staff, ctx).isLoaded());
        ConditionOutcome before = registries.conditions().test("users.outranks-target", actionContext(null), Map.of("userId", "7"));
        assertFalse(before.allowed(), "unknown until a fetch ran");

        loadSteve();

        assertEquals(100, feature.targetView(staff, ctx).getCoins());
        assertEquals("STAFF", feature.targetView(staff, ctx).getNextMode());
        assertTrue(registries.conditions().test("users.outranks-target", actionContext(null), Map.of("userId", "7")).allowed());
    }

    @Test
    void outranksConditionDeniesAHigherRankedTarget() {
        when(usersQueryApi.getByUsername("Owner")).thenReturn(CompletableFuture.completedFuture(ownerUser));
        feature.fetchTarget(sourceContext(staff, MenuContextParams.EMPTY), Map.of("userId", "1", "name", "Owner")).join();

        ConditionOutcome outcome = registries.conditions().test("users.outranks-target", actionContext(null), Map.of("userId", "1"));

        assertFalse(outcome.allowed());
        assertEquals("You can only manage players ranked below you.", outcome.denialMessage());
    }

    @Test
    void steppersInterpolateTheSessionStepIncludingNegation() {
        MenuSession session = new MenuSessionRegistry().open(UUID.randomUUID());
        session.setState("pm.coinStep", "100");
        Map<String, Object> scope = Map.of(MenuVariableProviderRegistry.ROOT_STATE, MenuStateView.of(session),
                MenuVariableProviderRegistry.ROOT_CTX, MenuContextParams.of(Map.of("userId", "7")));
        loadSteve();

        String minus = VariableResolver.interpolate("-$state.pm.coinStep$", scope);
        registries.actions().execute("users.adjust", actionContext(session),
                Map.of("userId", VariableResolver.interpolate("$ctx.userId$", scope), "field", "coins", "delta", minus));

        verify(admin).adjustBalance(staff, steveUser, "coins", -100, "Player manager (Admin)");
        verify(menuService).refreshOpenMenu(staff);
    }

    @Test
    void eachActionChecksItsPropertyNode() {
        loadSteve();
        when(admin.requireProperty(staff, "gems")).thenReturn(false);

        registries.actions().execute("users.adjust", actionContext(null), Map.of("userId", "7", "field", "gems", "delta", "10"));

        verify(admin, never()).adjustBalance(any(), any(), anyString(), anyInt(), anyString());
    }

    @Test
    void unsetStepAsksForOneInsteadOfFailing() {
        loadSteve();

        registries.actions().execute("users.adjust", actionContext(null), Map.of("userId", "7", "field", "xp", "delta", "-"));

        verify(staff).sendMessage("§cPick a step first - click the value item.");
        verify(admin, never()).adjustBalance(any(), any(), anyString(), anyInt(), anyString());
    }

    @Test
    void kickAndBanDispatchAsTheViewer() {
        loadSteve();
        when(admin.kick(staff, steveUser)).thenReturn(true);
        when(admin.ban(staff, steveUser)).thenReturn(true);

        registries.actions().execute("users.kick", actionContext(null), Map.of("userId", "7"));
        registries.actions().execute("users.ban", actionContext(null), Map.of("userId", "7"));

        verify(admin).kick(staff, steveUser);
        verify(admin).ban(staff, steveUser);
    }

    @Test
    void setTitleGroupModeSalaryAndFreezeUseTheService() {
        loadSteve();
        titles.listAsync().join();
        groups.listAsync().join();
        when(staff.hasPermission("knk.freeze")).thenReturn(true);
        when(admin.setTitle(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(true));
        when(admin.changeGroup(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(admin.setMode(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(true));
        when(admin.payOutSalary(any(), any())).thenReturn(CompletableFuture.completedFuture(true));
        when(admin.setFrozen(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        registries.actions().execute("users.set-title", actionContext(null), Map.of("userId", "7", "bracketId", "3"));
        registries.actions().execute("users.group", actionContext(null), Map.of("userId", "7", "groupId", "5", "op", "remove"));
        registries.actions().execute("users.mode", actionContext(null), Map.of("userId", "7", "mode", "staff"));
        registries.actions().execute("users.salary-payout", actionContext(null), Map.of("userId", "7"));
        registries.actions().execute("users.freeze", actionContext(null), Map.of("userId", "7", "op", "freeze"));

        verify(admin).setTitle(staff, steveUser, ProfileMenuFeatureTest.BRACKETS.get(2));
        verify(admin).changeGroup(staff, steveUser, groups.cachedOrEmpty().get(1), false, null);
        verify(admin).setMode(staff, steveUser, ActiveMode.STAFF);
        verify(admin).payOutSalary(staff, steveUser);
        verify(admin).setFrozen(staff, steveUser, true, UserManagerMenuFeature.FREEZE_REASON);
    }

    @Test
    void groupsRowsHighlightMemberships() {
        when(usersQueryApi.getGroupMemberships(7)).thenReturn(CompletableFuture.completedFuture(List.of(
                new GroupMembershipSummary(5, "Royal", 30, true, null, true))));

        List<GroupRow> rows = feature.fetchGroups(sourceContext(staff, MenuContextParams.EMPTY),
                Map.of("userId", "7", "name", "Steve")).join().items();

        assertEquals(List.of("NORMAL", "HIGHLIGHT"), rows.stream().map(GroupRow::getDisplayMode).toList());
        assertTrue(rows.get(1).getIsMember());
    }

    @Test
    void titlesRowsAreSeenAsTheTargetAndStayClickable() {
        List<TitleRow> rows = feature.fetchTitles(sourceContext(staff, MenuContextParams.EMPTY),
                Map.of("userId", "7", "name", "Steve")).join().items();

        assertEquals("HIGHLIGHT", rows.get(1).getDisplayMode());
        assertEquals(List.of("NORMAL", "HIGHLIGHT", "NORMAL"), rows.stream().map(TitleRow::getPickerDisplayMode).toList());
    }

    @Test
    void pendingOnlyAllowsAUsersConfirmation() {
        MenuSession session = new MenuSessionRegistry().open(UUID.randomUUID());
        session.setPendingConfirmation(new MenuSession.PendingConfirmation("kits.purchase", Map.of(), "?"));
        assertFalse(registries.conditions().test("users.pending", actionContext(session), Map.of()).allowed());
        session.setPendingConfirmation(new MenuSession.PendingConfirmation("users.ban", Map.of("userId", "7"), "?"));
        assertTrue(registries.conditions().test("users.pending", actionContext(session), Map.of()).allowed());
    }

    @Test
    void userManagerSeedsValidateAgainstTheRegisteredFeatures() {
        for (String key : List.of("users.manager", "users.manager.edit", "users.manager.titles", "users.manager.groups")) {
            RuntimeMenu menu = ContentSeedFixture.assemble(key);
            assertDoesNotThrow(() -> ContentSeedFixture.validate(menu, ContentFeatures.all()), key);
        }
    }
}
