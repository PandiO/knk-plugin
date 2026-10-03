package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.statistics.PlayerStatistics;
import net.knightsandkings.knk.core.domain.statistics.TitleChange;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.ports.api.StatisticsApi;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** IMPLEMENTATION_PLAN.md §5.3 / link 5 acceptance criterion 3: the {@code statistics.main} menu. */
class StatisticsMenuFeatureTest {

    private static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");

    private final UUID uuid = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final StatisticsApi api = mock(StatisticsApi.class);
    private final UserCache cache = new UserCache(Duration.ofMinutes(5));
    private final MenuService menuService = mock(MenuService.class);
    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final StatisticsMenuFeature feature = new StatisticsMenuFeature(api, cache, clock);
    private final MenuFeatureRegistries registries = ContentFeatures.all(feature);

    StatisticsMenuFeatureTest() {
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("Alice");
        cache.put(ProfileMenuFeatureTest.user(uuid, 0, 1, null, 0, null, null)); // knk user id 9
    }

    static PlayerStatistics statistics(int userId, String username, String viewer, String period, PlayerStatistics.Metric... metrics) {
        return new PlayerStatistics(userId, username, period, null, null, "Europe/Amsterdam", viewer,
                new PlayerStatistics.Profile("Squire", 1200, 5, 1, T0.minusSeconds(86_400 * 30), 7_500, 600),
                List.of(metrics), null, null);
    }

    static PlayerStatistics.Metric metric(String key, Double value, String unit, PlayerStatistics.ContextValue... contexts) {
        return new PlayerStatistics.Metric(key, key, value, unit, "Sum", List.of(contexts));
    }

    private MenuContentSourceContext rows(MenuContextParams ctx) {
        return new MenuContentSourceContext(player, null, ctx);
    }

    private Page<TitleHistoryRow> history(MenuContextParams ctx) {
        return feature.fetchHistory(rows(ctx), new PagedQuery(1, 27, null, null, false, Map.of())).join();
    }

    private MenuActionContext action() {
        return new MenuActionContext(player, null, Map.of(), menuService, null, null, null, null);
    }

    @Test
    void theSeedsValidateAgainstTheRegisteredFeatures() {
        assertDoesNotThrow(() -> ContentSeedFixture.validate(ContentSeedFixture.assemble(StatisticsMenuFeature.MENU_KEY), registries));
        assertDoesNotThrow(() -> ContentSeedFixture.validate(ContentSeedFixture.assemble("profile.main"), registries));
    }

    @Test
    void ownStatisticsAreReadActingAsTheViewer_AndGroupedLikeTheCatalogue() {
        when(api.getUserStatistics(9, 9, "lifetime", null)).thenReturn(CompletableFuture.completedFuture(statistics(9, "Alice", "self",
                "lifetime",
                metric("active_playtime", 7500d, "Seconds"),
                metric("pvp_kills", 12d, "Count", new PlayerStatistics.ContextValue("open_world", 5), new PlayerStatistics.ContextValue("siege", 7)),
                metric("distance.foot", 1234.9d, "Blocks"),
                metric("xp_gained", 300d, "Count"))));
        when(api.getTitleHistory(9, 9, 1, StatisticsMenuFeature.HISTORY_SIZE)).thenReturn(CompletableFuture.completedFuture(
                new Page<>(List.of(new TitleChange(T0, "Serf", "Squire", "Promotion"), new TitleChange(T0.minusSeconds(60), null, "Serf", "Promotion")),
                        2, 1, 27)));

        List<TitleHistoryRow> rows = history(MenuContextParams.EMPTY).items();
        StatisticsMenuView view = feature.viewFor(player, MenuContextParams.EMPTY);

        assertEquals("&aPromoted to &fSquire", rows.get(0).getName());
        assertEquals(List.of("&7From: &fSerf", "&7On: &f3 Oct 2026"), rows.get(0).getLoreLines());
        assertEquals("&fYour statistics", view.getTitle());
        assertEquals("Alice", view.getTargetName());
        assertEquals(List.of("&7Player kills: &f12 &8(Open world 5, Siege 7)"), view.getCombatLines());
        assertEquals(List.of("&7Distance on foot: &f1,234 blocks"), view.getExplorationLines());
        assertEquals(List.of("&7XP gained: &f300"), view.getProgressionLines());
        assertEquals(List.of(StatisticsMenuView.NOTHING_VISIBLE), view.getMinigamesLines());
        assertTrue(view.getProfileLines().contains("&7Active playtime: &f2h 5m &8| &7AFK: &f10m"));
        assertEquals("&7Title changes, newest first (below)", view.getTitleHistoryLine());
    }

    @Test
    void anotherPlayersStatisticsComeFromCtxTarget_AndAPrivateTitleHistoryIsExplained() {
        MenuContextParams ctx = MenuContextParams.of(Map.of("target", "42", "name", "Bob"));
        when(api.getUserStatistics(42, 9, "lifetime", null)).thenReturn(CompletableFuture.completedFuture(
                statistics(42, "Bob", "signedIn", "lifetime", metric("active_playtime", 60d, "Seconds"))));
        when(api.getTitleHistory(42, 9, 1, StatisticsMenuFeature.HISTORY_SIZE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException(new ApiException("u", 403, "Forbidden", ""))));

        assertTrue(history(ctx).items().isEmpty());
        StatisticsMenuView view = feature.viewFor(player, ctx);

        assertEquals("&fBob&7's statistics", view.getTitle());
        assertEquals("&8Bob's title history isn't visible to you", view.getTitleHistoryLine());
        assertEquals(List.of(StatisticsMenuView.NOTHING_VISIBLE), view.getCombatLines());
    }

    @Test
    void thePeriodCyclesAndEachPeriodIsReadOnce() {
        when(api.getUserStatistics(eq(9), eq(9), any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(
                statistics(9, "Alice", "self", inv.getArgument(2))));
        when(api.getTitleHistory(anyInt(), any(), anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(new Page<>(List.of(), 0, 1, 27)));

        history(MenuContextParams.EMPTY);
        history(MenuContextParams.EMPTY); // fresh for 5 s: no second read
        assertEquals("&7Showing: &fLifetime", feature.viewFor(player, MenuContextParams.EMPTY).getPeriodLine());
        assertEquals("&eClick: &7show today", feature.viewFor(player, MenuContextParams.EMPTY).getNextPeriodLine());

        registries.actions().execute(StatisticsMenuFeature.PERIOD_ACTION, action(), Map.of());
        assertEquals("day", feature.period(uuid));
        assertEquals(List.of("&7Loading..."), feature.viewFor(player, MenuContextParams.EMPTY).getCombatLines());
        history(MenuContextParams.EMPTY);

        verify(api, times(1)).getUserStatistics(9, 9, "lifetime", null);
        verify(api, times(1)).getUserStatistics(9, 9, "day", null);
        verify(menuService).refreshOpenMenu(player);
    }

    @Test
    void aFailedReadSaysSo_AndAnUnloadedViewerReadsNothing() {
        when(api.getUserStatistics(9, 9, "lifetime", null)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
        when(api.getTitleHistory(9, 9, 1, StatisticsMenuFeature.HISTORY_SIZE)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));

        history(MenuContextParams.EMPTY);
        assertEquals(List.of("&cCouldn't load statistics - try again later"), feature.viewFor(player, MenuContextParams.EMPTY).getCombatLines());

        Player stranger = mock(Player.class);
        when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
        assertTrue(feature.fetchHistory(new MenuContentSourceContext(stranger, null, MenuContextParams.EMPTY), null).join().items().isEmpty());
        verify(api, times(1)).getUserStatistics(anyInt(), any(), any(), any());
    }
}
