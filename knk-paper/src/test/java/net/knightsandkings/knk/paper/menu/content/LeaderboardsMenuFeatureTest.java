package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardBoard;
import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardView;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.ports.api.LeaderboardsApi;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.knightsandkings.knk.paper.menu.MenuService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** IMPLEMENTATION_PLAN.md §5.3 / link 5 acceptance criterion 3: the leaderboard menus. */
class LeaderboardsMenuFeatureTest {

    private static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");

    private final UUID uuid = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final LeaderboardsApi api = mock(LeaderboardsApi.class);
    private final UserCache cache = new UserCache(Duration.ofMinutes(5));
    private final MenuService menuService = mock(MenuService.class);
    private final LeaderboardsMenuFeature feature = new LeaderboardsMenuFeature(api, cache, Clock.fixed(T0, ZoneOffset.UTC));
    private final MenuFeatureRegistries registries = ContentFeatures.all(feature);
    private final MenuContextParams ctx = MenuContextParams.of(Map.of("board", "active_playtime"));

    LeaderboardsMenuFeatureTest() {
        when(player.getUniqueId()).thenReturn(uuid);
        cache.put(ProfileMenuFeatureTest.user(uuid, 0, 1, null, 0, null, null)); // knk user id 9
    }

    private static LeaderboardView view(String period, LeaderboardView.ViewerEntry viewer) {
        return new LeaderboardView("active_playtime", "Active playtime", "Seconds", period, null, T0.minusSeconds(180), 3,
                List.of(new LeaderboardView.Entry(1, 4, "dave", 7200), new LeaderboardView.Entry(1, 9, "Alice", 7200),
                        new LeaderboardView.Entry(3, 2, "bob", 60)),
                viewer);
    }

    private MenuActionContext action() {
        return new MenuActionContext(player, null, Map.of(), menuService, null, null, null, null);
    }

    private List<LeaderboardEntryRow> entries() {
        return feature.fetchEntries(new MenuContentSourceContext(player, null, ctx), new PagedQuery(1, 10, null, null, false, Map.of()))
                .join().items();
    }

    @Test
    void theSeedsValidateAgainstTheRegisteredFeatures() {
        assertDoesNotThrow(() -> ContentSeedFixture.validate(ContentSeedFixture.assemble(LeaderboardsMenuFeature.LIST_MENU_KEY), registries));
        assertDoesNotThrow(() -> ContentSeedFixture.validate(ContentSeedFixture.assemble(LeaderboardsMenuFeature.BOARD_MENU_KEY), registries));
    }

    @Test
    void theBoardListIsLoadedOnce() {
        when(api.listBoards()).thenReturn(CompletableFuture.completedFuture(List.of(
                new LeaderboardBoard("active_playtime", "active_playtime", null, "Active playtime", "Seconds", List.of("weekly"), true),
                new LeaderboardBoard("pvp_kills@siege", "pvp_kills", "siege", "Player kills — Siege", "Count", List.of("weekly"), false))));

        List<LeaderboardBoardRow> rows = feature.fetchBoards(null).join().items();
        feature.fetchBoards(null).join();

        assertEquals(List.of("active_playtime", "pvp_kills@siege"), rows.stream().map(LeaderboardBoardRow::getBoardKey).toList());
        assertEquals("CLOCK", rows.get(0).getMaterial());
        assertEquals("&7Ranks players who show this statistic", rows.get(1).getLoreLines().get(0));
        assertEquals(2, feature.cachedBoards().size());
        verify(api, times(1)).listBoards();
    }

    @Test
    void entriesAreTheTopTenActingAsTheViewer_WithTheViewerHighlighted() {
        when(api.getBoard("active_playtime", "weekly", 10, 9))
                .thenReturn(CompletableFuture.completedFuture(view("weekly", new LeaderboardView.ViewerEntry(1, 7200))));

        List<LeaderboardEntryRow> rows = entries();
        LeaderboardMenuView root = feature.viewFor(player, ctx);

        assertEquals(List.of("&6#1 &fdave", "&6#1 &fAlice &a(you)", "&c#3 &fbob"), rows.stream().map(LeaderboardEntryRow::getName).toList());
        assertEquals(List.of("NORMAL", "HIGHLIGHT", "NORMAL"), rows.stream().map(LeaderboardEntryRow::getDisplayMode).toList());
        assertEquals("2h 0m", rows.get(0).getValue());
        assertEquals((Integer) 4, (Integer) rows.get(0).getUserId());
        assertEquals("This week", root.getPeriodName());
        assertEquals(List.of("&7Your rank: &f#1 &7of &f3", "&7Your value: &f2h 0m", "&8Updated 3 min ago"), root.getViewerLines());
    }

    @Test
    void thePeriodCycles_WeeklyMonthlyLifetime_AndAnUnrankedViewerIsToldWhy() {
        when(api.getBoard(eq("active_playtime"), any(), eq(10), eq(9)))
                .thenAnswer(inv -> CompletableFuture.completedFuture(view(inv.getArgument(1), null)));

        entries();
        entries(); // fresh: reused
        registries.actions().execute(LeaderboardsMenuFeature.PERIOD_ACTION, action(), Map.of());
        assertEquals("monthly", feature.period(uuid));
        assertEquals(List.of("&7Loading..."), feature.viewFor(player, ctx).getViewerLines());
        entries();
        registries.actions().execute(LeaderboardsMenuFeature.PERIOD_ACTION, action(), Map.of());
        assertEquals("lifetime", feature.period(uuid));
        entries();

        assertEquals("&7You're not ranked here (3 ranked)", feature.viewFor(player, ctx).getViewerLines().get(0));
        verify(api, times(1)).getBoard("active_playtime", "weekly", 10, 9);
        verify(api, times(1)).getBoard("active_playtime", "monthly", 10, 9);
        verify(api, times(1)).getBoard("active_playtime", "lifetime", 10, 9);
    }

    @Test
    void aFailedReadSaysSo_AndNoBoardReadsNothing() {
        when(api.getBoard(any(), any(), anyInt(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));

        assertEquals(List.of(), entries());
        assertEquals(List.of("&cCouldn't load this leaderboard - try again later"), feature.viewFor(player, ctx).getViewerLines());
        assertEquals(List.of(), feature.fetchEntries(new MenuContentSourceContext(player, null, MenuContextParams.EMPTY), null).join().items());
        verify(api, times(1)).getBoard(any(), any(), anyInt(), any());
    }
}
