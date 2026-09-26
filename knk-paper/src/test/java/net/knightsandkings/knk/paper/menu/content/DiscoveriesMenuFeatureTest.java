package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryProgressRow;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySummary;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryTypeCount;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.ports.api.DiscoveriesApi;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Domain discovery DESIGN.md §3.7: the {@code discoveries} root, {@code discoveries.rows} and the seed. */
class DiscoveriesMenuFeatureTest {

    private final UUID uuid = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final DiscoveriesApi api = mock(DiscoveriesApi.class);
    private final UserCache cache = new UserCache(Duration.ofMinutes(5));

    private static final DiscoverySummary SUMMARY = new DiscoverySummary(
            List.of(new DiscoveryTypeCount("Town", 1, 2), new DiscoveryTypeCount("District", 3, 7),
                    new DiscoveryTypeCount("Structure", 0, 0), new DiscoveryTypeCount("GateStructure", 0, 4)),
            new DiscoveryProgressRow(11, "Rivia", "Town", null, true, null, 100, 5, 20), 4, 1200, 10, 300);

    DiscoveriesMenuFeatureTest() {
        when(player.getUniqueId()).thenReturn(uuid);
    }

    private void loggedIn() {
        cache.put(ProfileMenuFeatureTest.user(uuid, 0, 1, null, 0, null, null)); // knk user id 9
    }

    private MenuContentSourceContext context() {
        return new MenuContentSourceContext(player, null, MenuContextParams.EMPTY);
    }

    private static PagedQuery engineQuery(Map<String, String> filters) {
        return new PagedQuery(2, 27, null, null, false, filters);
    }

    @Test
    void rowsForwardThePageAndFiltersToTheProgressCall() {
        loggedIn();
        when(api.progress(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(new Page<>(List.of(
                new DiscoveryProgressRow(11, "Rivia", "Town", null, true, null, 100, 5, 20),
                new DiscoveryProgressRow(12, "Market", "District", "Rivia", false, null, 0, 0, 0)), 30, 2, 27)));
        when(api.summary(9)).thenReturn(CompletableFuture.completedFuture(SUMMARY));
        DiscoveriesMenuFeature feature = new DiscoveriesMenuFeature(api, cache, Clock.systemUTC());

        Page<DiscoveryRow> page = feature.fetchRows(context(),
                engineQuery(Map.of("DomainType", "District", "Status", "Undiscovered"))).join();

        ArgumentCaptor<PagedQuery> sent = ArgumentCaptor.forClass(PagedQuery.class);
        verify(api).progress(org.mockito.ArgumentMatchers.eq(9), sent.capture());
        assertEquals(2, sent.getValue().pageNumber());
        assertEquals(27, sent.getValue().pageSize());
        assertEquals(Map.of("domainType", "District", "status", "undiscovered"), sent.getValue().filters());
        assertEquals(30, page.totalCount());
        assertEquals(List.of("HIGHLIGHT", "DISABLED"), page.items().stream().map(DiscoveryRow::getDisplayMode).toList(),
                "the summary's latest discovery is highlighted");
        assertEquals("&8???", page.items().get(1).getName());
    }

    @Test
    void theRootShowsTheSummaryReadWithTheRows() {
        loggedIn();
        when(api.progress(anyInt(), any())).thenReturn(CompletableFuture.completedFuture(new Page<>(List.of(), 0, 1, 27)));
        when(api.summary(9)).thenReturn(CompletableFuture.completedFuture(SUMMARY));
        DiscoveriesMenuFeature feature = new DiscoveriesMenuFeature(api, cache, Clock.systemUTC());

        feature.fetchRows(context(), engineQuery(Map.of())).join();
        DiscoveriesView view = feature.viewFor(player);

        assertTrue(view.isLoaded());
        assertEquals(List.of("&7Towns: &f1&7/&f2", "&7Districts: &f3&7/&f7", "&7Gates: &f0&7/&f4"), view.getSummaryLines(),
                "types without places are left out");
        assertEquals("Rivia", view.getLatestName());
        assertEquals("&7Earned: &6+1200 coins &b+10 gems &d+300 XP", view.getRewardsLine());
        assertEquals("&7Discovered &f4 &7of &f13 &7places", view.getCountLine());
        verify(api, times(1)).summary(9);
    }

    @Test
    void theRootNeverWaitsAndReadsAMissingOrStaleSummaryInTheBackground() {
        loggedIn();
        CompletableFuture<DiscoverySummary> pending = new CompletableFuture<>();
        when(api.summary(9)).thenReturn(pending);
        Instant[] now = {Instant.parse("2026-09-26T12:00:00Z")};
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        DiscoveriesMenuFeature feature = new DiscoveriesMenuFeature(api, cache, clock);

        DiscoveriesView first = feature.viewFor(player);
        assertFalse(first.isLoaded());
        assertNull(first.getCountLine(), "hub line dropped while unknown");
        assertEquals(List.of("&7Loading your discoveries..."), first.getSummaryLines());
        feature.viewFor(player);
        verify(api, times(1)).summary(9); // one read in flight at a time

        pending.complete(SUMMARY);
        assertTrue(feature.viewFor(player).isLoaded());
        verify(api, times(1)).summary(9); // fresh: no new read

        when(api.summary(9)).thenReturn(CompletableFuture.completedFuture(SUMMARY));
        now[0] = now[0].plusMillis(DiscoveriesMenuFeature.SUMMARY_TTL_MILLIS);
        assertTrue(feature.viewFor(player).isLoaded(), "stale summary still shown");
        verify(api, times(2)).summary(9);

        feature.invalidate(uuid);
        assertFalse(feature.viewFor(player).isLoaded(), "dropped; the re-read shows on the next render");
        assertTrue(feature.viewFor(player).isLoaded());
    }

    @Test
    void aFailedReadRendersAnEmptyPage() {
        loggedIn();
        when(api.progress(anyInt(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
        when(api.summary(9)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
        DiscoveriesMenuFeature feature = new DiscoveriesMenuFeature(api, cache, Clock.systemUTC());

        Page<DiscoveryRow> page = feature.fetchRows(context(), engineQuery(Map.of())).join();

        assertTrue(page.items().isEmpty());
        assertEquals(0, page.totalCount());
        assertFalse(feature.viewFor(player).isLoaded());
    }

    @Test
    void aViewerWithoutAnAccountGetsNoRowsAndNoCalls() {
        DiscoveriesMenuFeature feature = new DiscoveriesMenuFeature(api, cache, Clock.systemUTC());

        assertTrue(feature.fetchRows(context(), engineQuery(Map.of())).join().items().isEmpty());
        assertFalse(feature.viewFor(player).isLoaded());
        verify(api, never()).progress(anyInt(), any());
        verify(api, never()).summary(anyInt());
    }

    @Test
    void apiQueryKeepsOtherFiltersAndDropsBlankOnes() {
        PagedQuery query = DiscoveriesMenuFeature.toApiQuery(new PagedQuery(0, 0, "riv", "name", true,
                Map.of("domaintype", "Town", "Status", " ", "Other", "x")));

        assertEquals(Map.of("domainType", "Town", "Other", "x"), query.filters());
        assertEquals(1, query.pageNumber());
        assertEquals(1, query.pageSize());
        assertEquals("riv", query.searchTerm());
        assertNull(query.sortBy(), "default order: Town, District, Structure, then name");
    }

    @Test
    void discoveriesSeedAndTheHubValidateAgainstTheRegisteredFeatures() {
        RuntimeMenu menu = ContentSeedFixture.assemble(DiscoveriesMenuFeature.MENU_KEY);
        assertDoesNotThrow(() -> ContentSeedFixture.validate(menu, ContentFeatures.all()));
        assertDoesNotThrow(() -> ContentSeedFixture.validate(ContentSeedFixture.assemble(HubMenuFeature.HUB_KEY), ContentFeatures.all()));
    }
}
