package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.statistics.StatisticVisibility;
import net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilityConflictException;
import net.knightsandkings.knk.core.domain.statistics.StatisticsVisibilitySettings;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.menu.MenuSessionRegistry;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.ports.api.StatisticsApi;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.knightsandkings.knk.paper.menu.MenuService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** IMPLEMENTATION_PLAN.md §5.3 / acceptance criterion 6: the {@code statistics.visibility} menu. */
class StatisticsVisibilityMenuFeatureTest {

    private final UUID uuid = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final StatisticsApi api = mock(StatisticsApi.class);
    private final UserCache cache = new UserCache(Duration.ofMinutes(5));
    private final MenuService menuService = mock(MenuService.class);
    private final StatisticsVisibilityMenuFeature feature =
            new StatisticsVisibilityMenuFeature(api, cache, Runnable::run, Clock.systemUTC());
    private final MenuFeatureRegistries registries = ContentFeatures.all(feature);

    StatisticsVisibilityMenuFeatureTest() {
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.isOnline()).thenReturn(true);
        cache.put(ProfileMenuFeatureTest.user(uuid, 0, 1, null, 0, null, null)); // knk user id 9
    }

    private static StatisticsVisibilitySettings.Setting setting(String key, String group, String label, boolean contextual,
                                                                StatisticVisibility value,
                                                                StatisticsVisibilitySettings.ContextValue... contexts) {
        return new StatisticsVisibilitySettings.Setting(key, group, label, contextual, value, List.of(contexts));
    }

    private static StatisticsVisibilitySettings settings(StatisticVisibility pvp, StatisticVisibility deaths) {
        return new StatisticsVisibilitySettings(9, false, List.of(
                setting("logins", "activity", "Logins", false, StatisticVisibility.NOBODY),
                setting("pvp_kills", "combat", "Player kills", true, pvp,
                        new StatisticsVisibilitySettings.ContextValue("siege", StatisticVisibility.NOBODY, true),
                        new StatisticsVisibilitySettings.ContextValue("open_world", pvp, false)),
                setting("deaths", "combat", "Deaths", false, deaths)));
    }

    private MenuContentSourceContext rowsContext() {
        return new MenuContentSourceContext(player, null, MenuContextParams.EMPTY);
    }

    private MenuActionContext action(MenuSession session, Object row) {
        return new MenuActionContext(player, session, Map.of(), menuService, null, null, null, row);
    }

    private List<StatisticsVisibilityRow> loadRows() {
        Page<StatisticsVisibilityRow> page = feature.fetchRows(rowsContext(), new PagedQuery(1, 27, null, null, false, Map.of())).join();
        return page.items();
    }

    @Test
    void theSeedAndTheProfileValidateAgainstTheRegisteredFeatures() {
        RuntimeMenu menu = ContentSeedFixture.assemble(StatisticsVisibilityMenuFeature.MENU_KEY);
        assertDoesNotThrow(() -> ContentSeedFixture.validate(menu, registries));
        assertDoesNotThrow(() -> ContentSeedFixture.validate(ContentSeedFixture.assemble("profile.main"), registries));
    }

    @Test
    void rowsListTheSelectedGroupWithContextRowsActingAsTheViewer() {
        when(api.getVisibility(9, 9)).thenReturn(CompletableFuture.completedFuture(settings(StatisticVisibility.EVERYONE, StatisticVisibility.NOBODY)));

        assertEquals(List.of("logins"), loadRows().stream().map(StatisticsVisibilityRow::getSettingKey).toList(), "activity first");

        registries.actions().execute(StatisticsVisibilityMenuFeature.SELECT_GROUP_ACTION, action(null, null), Map.of("group", "combat"));
        List<StatisticsVisibilityRow> rows = loadRows();

        assertEquals(4, rows.size());
        assertEquals("&fPlayer kills", rows.get(0).getName());
        assertEquals("Everyone", rows.get(0).getVisibility());
        assertEquals("&fPlayer kills &7— &fSiege", rows.get(1).getName());
        assertEquals("siege", rows.get(1).getContext());
        assertEquals("Nobody", rows.get(1).getVisibility());
        assertTrue(rows.get(1).getLoreLines().contains("&7Set for Siege only"));
        assertTrue(rows.get(2).getLoreLines().contains("&7Same as Player kills (inherited)"));
        assertEquals("deaths", rows.get(3).getSettingKey());
        verify(api, times(1)).getVisibility(9, 9); // the second read reused the fresh settings
        verify(menuService).refreshOpenMenu(player);
    }

    @Test
    void theRootHighlightsTheSelectedGroupAndCountsItsValues() {
        when(api.getVisibility(9, 9)).thenReturn(CompletableFuture.completedFuture(settings(StatisticVisibility.EVERYONE, StatisticVisibility.NOBODY)));
        loadRows();
        registries.actions().execute(StatisticsVisibilityMenuFeature.SELECT_GROUP_ACTION, action(null, null), Map.of("group", "Combat"));

        StatisticsVisibilityView view = feature.viewFor(player);

        assertEquals("HIGHLIGHT", view.getCombatMode());
        assertEquals("NORMAL", view.getActivityMode());
        assertEquals(List.of("&7Showing: &fCombat", "&cNobody &f1 &8| &bFriends &f0 &8| &aEveryone &f1"), view.getGroupSummaryLines());
    }

    @Test
    void aClickCyclesTheSettingWithTheShownValueAsExpected() {
        when(api.getVisibility(9, 9)).thenReturn(CompletableFuture.completedFuture(settings(StatisticVisibility.EVERYONE, StatisticVisibility.NOBODY)));
        registries.actions().execute(StatisticsVisibilityMenuFeature.SELECT_GROUP_ACTION, action(null, null), Map.of("group", "combat"));
        StatisticsVisibilityRow siegeRow = loadRows().get(1);
        when(api.updateVisibility(eq(9), eq(9), anyList()))
                .thenReturn(CompletableFuture.completedFuture(settings(StatisticVisibility.EVERYONE, StatisticVisibility.NOBODY)));

        registries.actions().execute(StatisticsVisibilityMenuFeature.CYCLE_ACTION, action(null, siegeRow), Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StatisticsVisibilitySettings.Change>> changes = ArgumentCaptor.forClass(List.class);
        verify(api).updateVisibility(eq(9), eq(9), changes.capture());
        assertEquals(List.of(new StatisticsVisibilitySettings.Change("pvp_kills", "siege", StatisticVisibility.NOBODY,
                StatisticVisibility.FRIENDS)), changes.getValue());
        verify(player).sendMessage(contains("now visible to Friends"));
    }

    @Test
    void theCycleAlsoWorksFromTheSeedParameters() {
        when(api.updateVisibility(eq(9), eq(9), anyList()))
                .thenReturn(CompletableFuture.completedFuture(settings(StatisticVisibility.NOBODY, StatisticVisibility.NOBODY)));

        registries.actions().execute(StatisticsVisibilityMenuFeature.CYCLE_ACTION, action(null, null),
                Map.of("settingKey", "logins", "context", "", "expected", "Everyone"));

        verify(api).updateVisibility(9, 9, List.of(new StatisticsVisibilitySettings.Change("logins", "",
                StatisticVisibility.EVERYONE, StatisticVisibility.NOBODY)));
    }

    @Test
    void aConflictRefreshesFromTheCurrentSettingsAndTellsThePlayer() {
        when(api.getVisibility(9, 9)).thenReturn(CompletableFuture.completedFuture(settings(StatisticVisibility.NOBODY, StatisticVisibility.NOBODY)));
        registries.actions().execute(StatisticsVisibilityMenuFeature.SELECT_GROUP_ACTION, action(null, null), Map.of("group", "combat"));
        StatisticsVisibilityRow row = loadRows().get(0);
        CompletableFuture<StatisticsVisibilitySettings> conflict = CompletableFuture.failedFuture(new java.util.concurrent.CompletionException(
                new StatisticsVisibilityConflictException("changed", settings(StatisticVisibility.EVERYONE, StatisticVisibility.NOBODY))));
        when(api.updateVisibility(eq(9), eq(9), anyList())).thenReturn(conflict);

        registries.actions().execute(StatisticsVisibilityMenuFeature.CYCLE_ACTION, action(null, row), Map.of());

        verify(player).sendMessage(contains("changed elsewhere"));
        assertEquals("Everyone", loadRows().get(0).getVisibility(), "the menu shows the API's current value");
        verify(api, times(1)).getVisibility(9, 9);
    }

    @Test
    void aGroupActionIsPreviewedConfirmedAndAppliedAtomically() {
        when(api.getVisibility(9, 9)).thenReturn(CompletableFuture.completedFuture(settings(StatisticVisibility.FRIENDS, StatisticVisibility.NOBODY)));
        registries.actions().execute(StatisticsVisibilityMenuFeature.SELECT_GROUP_ACTION, action(null, null), Map.of("group", "combat"));
        loadRows();
        MenuSession session = new MenuSessionRegistry().open(uuid);

        registries.actions().execute(StatisticsVisibilityMenuFeature.GROUP_ACTION, action(session, null), Map.of("value", "Everyone"));

        MenuSession.PendingConfirmation pending = session.getPendingConfirmation().orElseThrow();
        assertEquals(StatisticsVisibilityMenuFeature.APPLY_GROUP_ACTION, pending.actionTypeId());
        assertEquals(Map.of("group", "combat", "value", "Everyone"), pending.actionParams());
        assertTrue(registries.conditions().test(StatisticsVisibilityMenuFeature.PENDING_CONDITION, action(session, null), Map.of()).allowed());
        assertEquals(List.of("&fPlayer kills&7: &bFriends &7→ &aEveryone", "&fDeaths&7: &cNobody &7→ &aEveryone",
                "&8Player kills — Siege stays Nobody"), feature.viewFor(player).getPreviewLines());
        verify(api, never()).updateVisibility(anyInt(), anyInt(), anyList());

        when(api.updateVisibility(eq(9), eq(9), anyList()))
                .thenReturn(CompletableFuture.completedFuture(settings(StatisticVisibility.EVERYONE, StatisticVisibility.EVERYONE)));
        registries.actions().execute("menu.confirm.accept", action(session, null), Map.of());

        verify(api).updateVisibility(9, 9, List.of(
                new StatisticsVisibilitySettings.Change("pvp_kills", "", StatisticVisibility.FRIENDS, StatisticVisibility.EVERYONE),
                new StatisticsVisibilitySettings.Change("deaths", "", StatisticVisibility.NOBODY, StatisticVisibility.EVERYONE)));
        verify(player).sendMessage(contains("Set 2 Combat setting(s) to Everyone."));
        assertFalse(session.getPendingConfirmation().isPresent());
        assertEquals(List.of("&7Nothing to confirm"), feature.viewFor(player).getPreviewLines());
    }

    @Test
    void aGroupActionWithNothingToChangeAsksNothing() {
        when(api.getVisibility(9, 9)).thenReturn(CompletableFuture.completedFuture(settings(StatisticVisibility.NOBODY, StatisticVisibility.NOBODY)));
        loadRows();
        MenuSession session = new MenuSessionRegistry().open(uuid);

        registries.actions().execute(StatisticsVisibilityMenuFeature.GROUP_ACTION, action(session, null), Map.of("value", "Nobody"));

        assertFalse(session.getPendingConfirmation().isPresent());
        verify(player).sendMessage(contains("already visible to Nobody"));
    }

    @Test
    void selectingAnotherGroupDropsAPendingGroupAction() {
        when(api.getVisibility(9, 9)).thenReturn(CompletableFuture.completedFuture(settings(StatisticVisibility.NOBODY, StatisticVisibility.NOBODY)));
        loadRows();
        MenuSession session = new MenuSessionRegistry().open(uuid);
        registries.actions().execute(StatisticsVisibilityMenuFeature.GROUP_ACTION, action(session, null), Map.of("value", "Everyone"));
        assertTrue(session.getPendingConfirmation().isPresent());

        registries.actions().execute(StatisticsVisibilityMenuFeature.SELECT_GROUP_ACTION, action(session, null), Map.of("group", "exploration"));

        assertFalse(session.getPendingConfirmation().isPresent());
    }

    @Test
    void withoutAUserIdTheGridIsEmptyAndNothingIsSent() {
        UUID stranger = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(stranger);

        assertTrue(loadRows().isEmpty());
        registries.actions().execute(StatisticsVisibilityMenuFeature.CYCLE_ACTION, action(null, null),
                Map.of("settingKey", "logins", "context", "", "expected", "Nobody"));

        verify(api, never()).getVisibility(anyInt(), anyInt());
        verify(api, never()).updateVisibility(anyInt(), anyInt(), anyList());
        verify(player).sendMessage(contains("isn't loaded"));
    }

    @Test
    void friendsRowsCarryTheFailClosedNote() {
        StatisticsVisibilitySettings friends = new StatisticsVisibilitySettings(9, false, List.of(
                setting("logins", "activity", "Logins", false, StatisticVisibility.FRIENDS)));
        StatisticsVisibilityRow row = StatisticsMenuRows.first(StatisticsVisibilityMenuFeature.rows(friends, "activity"));

        assertTrue(row.getLoreLines().contains(StatisticsVisibilityRow.FRIENDS_NOTE));
        assertEquals("LIGHT_BLUE_DYE", row.getMaterial());
        assertTrue(row.getLoreLines().contains("&eClick: &7change to &aEveryone"));
    }

    @Test
    void anUnknownGroupIsAProgrammingError() {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                registries.actions().execute(StatisticsVisibilityMenuFeature.SELECT_GROUP_ACTION, action(null, null), Map.of("group", "economy")));
        verify(api, never()).getVisibility(anyInt(), anyInt());
        verify(menuService, never()).refreshOpenMenu(any());
    }

    /** Tiny helper so the friends test reads naturally. */
    private static final class StatisticsMenuRows {
        static StatisticsVisibilityRow first(List<StatisticsVisibilityRow> rows) {
            return rows.get(0);
        }
    }
}
