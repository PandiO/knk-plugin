package net.knightsandkings.knk.paper.menu.content;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.menu.MenuSessionRegistry;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.ports.api.StructureMenusApi;
import net.knightsandkings.knk.core.production.menu.StructureMenu;
import net.knightsandkings.knk.core.production.menu.StructureRow;
import net.knightsandkings.knk.core.production.menu.TransportDryRun;
import net.knightsandkings.knk.core.production.menu.TransportOrderRow;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuService;

/**
 * PandiO/knk-workspace#74 (M1-M5, A24-A27): the seeded production menus validate against this feature; the overview's
 * facets become the API's filters; the detail is shown only when the API sends it; a visitor's orders are a 403 shown as
 * a line; staff actions check the node; the Town filter starts on the player's town.
 */
class StructuresMenuFeatureTest {

    private final UUID uuid = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final StructureMenusApi api = mock(StructureMenusApi.class);
    private final UserCache cache = new UserCache(Duration.ofMinutes(5));
    private final MenuService menuService = mock(MenuService.class);
    private final StructuresMenuFeature.StaffTools tools = mock(StructuresMenuFeature.StaffTools.class);
    private boolean staff;
    private Integer town;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
    private final StructuresMenuFeature feature = new StructuresMenuFeature(api, cache, clock, Runnable::run,
            p -> staff, p -> town, tools, () -> menuService);

    static final StructureRow QUARRY = new StructureRow(5, "ProductionStructure", "Quarry", 3, "Quarry", 2, "Phoenix",
            true, 1, List.of("Cobblestone"), "Working", "");
    static final StructureRow NORTH = new StructureRow(9, "Warehouse", "North", null, "Warehouse", 2, "Phoenix", false,
            null, List.of(), "Working", "");

    StructuresMenuFeatureTest() {
        when(player.getUniqueId()).thenReturn(uuid);
        when(api.search(any(), any())).thenReturn(CompletableFuture.completedFuture(new Page<>(List.of(QUARRY, NORTH), 2, 1, 200)));
    }

    private void loggedIn() {
        cache.put(ProfileMenuFeatureTest.user(uuid, 0, 1, null, 0, null, null)); // knk user id 9
    }

    private MenuContentSourceContext context() {
        return new MenuContentSourceContext(player, null, MenuContextParams.EMPTY);
    }

    private MenuActionContext action(MenuSession session) {
        return new MenuActionContext(player, session, Map.of(), menuService, null, null, null, null);
    }

    @Test
    void theThreeSeededMenusAndTheHub_validateAgainstTheRegisteredFeature() {
        for (String key : StructuresMenuFeature.MENU_KEYS) {
            RuntimeMenu menu = ContentSeedFixture.assemble(key);
            assertDoesNotThrow(() -> ContentSeedFixture.validate(menu, ContentFeatures.all()), key);
        }
        assertDoesNotThrow(() -> ContentSeedFixture.validate(ContentSeedFixture.assemble(HubMenuFeature.HUB_KEY),
                ContentFeatures.all()));
    }

    @Test
    void overview_sendsTheFacetsAsApiFilters_byName_andActsForThePlayer() {
        loggedIn();
        feature.facets(); // reads the facet list (towns and kinds by name)

        Page<StructureOverviewRow> page = feature.fetchOverview(context(),
                new PagedQuery(2, 36, "qua", null, false, Map.of("Town", "Phoenix", "Kind", "Quarry", "Status", "Working"))).join();

        ArgumentCaptor<PagedQuery> sent = ArgumentCaptor.forClass(PagedQuery.class);
        verify(api, times(2)).search(sent.capture(), any());
        PagedQuery query = sent.getAllValues().get(1);
        assertEquals(Map.of("townId", "2", "productionKindId", "3", "status", "Working"), query.filters());
        assertEquals("qua", query.searchTerm());
        assertEquals(2, query.pageNumber());
        verify(api).search(eq(query), eq(9));
        assertEquals(2, page.items().size());
        assertEquals("Quarry", page.items().get(0).getName());
        assertEquals("COBBLESTONE", page.items().get(0).getMaterial());
        assertEquals("CHEST", page.items().get(1).getMaterial());
        StructuresView view = feature.overviewView(player);
        assertEquals("Phoenix", view.getTownValues());
        assertEquals("Quarry", view.getKindValues());
        assertEquals("&7Town: &fPhoenix", view.getTownFilterLine());
        assertEquals("&7Status: &fWorking", view.getStatusFilterLine());
    }

    @Test
    void townFilter_startsOnThePlayersTown_onlyWhenOpenedFromOutside() {
        feature.facets();
        town = 2;

        feature.menuOpened(player, StructuresMenuFeature.OVERVIEW_MENU, "main");
        verify(menuService).filter(player, "Structures", "Town", "Phoenix");

        feature.menuOpened(player, StructuresMenuFeature.OVERVIEW_MENU, "structures.structure"); // back from a structure
        town = null; // the wilderness
        feature.menuOpened(player, StructuresMenuFeature.OVERVIEW_MENU, null);
        feature.menuOpened(player, "main", null);
        verify(menuService, times(1)).filter(any(), any(), any(), any());
    }

    @Test
    void structureMenu_aVisitorSeesOnlyThePublicPart_aMemberTheDetailAndStorages() {
        when(api.get(5, null)).thenReturn(CompletableFuture.completedFuture(Optional.of(new StructureMenu(QUARRY, false, false, null))));

        Page<StructureStorageRow> visitorRows = feature.fetchStorages(context(), Map.of("structureId", "5")).join();
        StructureView visitor = feature.structureView(player, MenuContextParams.of(Map.of("structureId", "5")));

        assertTrue(visitorRows.items().isEmpty());
        assertFalse(visitor.getSeesDetails());
        assertTrue(visitor.getRateLines().isEmpty());
        assertEquals("Quarry", visitor.getName());
        verify(menuService).refreshOpenMenu(player);

        loggedIn();
        Player member = mock(Player.class);
        UUID memberId = UUID.randomUUID();
        when(member.getUniqueId()).thenReturn(memberId);
        cache.put(ProfileMenuFeatureTest.user(memberId, 0, 1, null, 0, null, null));
        StructureMenu detail = new StructureMenu(QUARRY, true, false, new StructureMenu.Detail(2, 2, 0,
                List.of(new StructureMenu.Rate(1, 3, "Cobblestone", 12.5, "Running", List.of())),
                List.of(new StructureMenu.StorageView(8, "Output", "Output", 40, 1000L,
                        List.of(new StructureMenu.Line(3, "Cobblestone", 40, null, null)))),
                List.of(), List.of(), 1));
        when(api.get(eq(5), eq(9))).thenReturn(CompletableFuture.completedFuture(Optional.of(detail)));

        Page<StructureStorageRow> rows = feature.fetchStorages(new MenuContentSourceContext(member, null, MenuContextParams.EMPTY),
                Map.of("structureId", "5")).join();
        StructureView view = feature.structureView(member, MenuContextParams.of(Map.of("structureId", "5")));

        assertEquals(1, rows.items().size());
        assertEquals("&7Filled: &f40 &7of &f1,000 &7units (4%)", rows.items().get(0).getFillLine());
        assertTrue(view.getSeesDetails());
        assertEquals(List.of("&fCobblestone&7: &e12.5 per hour"), view.getRateLines());
        assertEquals("&71 open order", view.getOrdersLine());
    }

    @Test
    void structureMenu_aSecondReadWithinTheTtlUsesTheCachedMenu_andAnUnchangedReadDoesNotRepaint() {
        when(api.get(5, null)).thenReturn(CompletableFuture.completedFuture(Optional.of(new StructureMenu(QUARRY, false, false, null))));

        feature.fetchStorages(context(), Map.of("structureId", "5")).join();
        feature.fetchStorages(context(), Map.of("structureId", "5")).join();

        verify(api, times(1)).get(anyInt(), any());
        verify(menuService, times(1)).refreshOpenMenu(player);
    }

    @Test
    void orders_aVisitorsForbiddenReadIsALine_staffSeeTheirButtons() {
        when(api.orders(5, 1, 28, null)).thenReturn(CompletableFuture.failedFuture(
                new CompletionException(new ApiException("u", 403, "Forbidden", ""))));

        Page<TransportOrderMenuRow> none = feature.fetchOrders(context(), Map.of("structureId", "5"),
                new PagedQuery(1, 28, null, null, false, Map.of())).join();

        assertTrue(none.items().isEmpty());
        assertEquals("&cOnly members of the town and staff see the orders.",
                feature.ordersView(player, MenuContextParams.of(Map.of("structureId", "5"))).getCountLine());

        staff = true;
        loggedIn();
        TransportOrderRow order = new TransportOrderRow(31, "Manual", "Planned", null, "Quarry", "Output", "North", 10, 0,
                0, 0, Instant.EPOCH, null, null);
        when(api.orders(5, 1, 28, 9)).thenReturn(CompletableFuture.completedFuture(new Page<>(List.of(order), 1, 1, 28)));

        Page<TransportOrderMenuRow> rows = feature.fetchOrders(context(), Map.of("structureId", "5"),
                new PagedQuery(1, 28, null, null, false, Map.of())).join();

        TransportOrderMenuRow row = rows.items().get(0);
        assertTrue(row.getCanCancel());
        assertTrue(row.getCanRetry());
        assertEquals("&eStaff: click to cancel or run now", row.getStaffHintLine());
        assertEquals("&71 order", feature.ordersView(player, MenuContextParams.of(Map.of("structureId", "5"))).getCountLine());
        TransportOrderMenuRow asMember = new TransportOrderMenuRow(order, false, Instant.EPOCH);
        assertFalse(asMember.getCanCancel());
        assertEquals("", asMember.getStaffHintLine());
    }

    @Test
    void staffActions_checkTheNode_andCallTheirTools() {
        MenuSession session = new MenuSessionRegistry().open(uuid);
        var registries = ContentFeatures.all(feature);

        registries.actions().execute("structures.survey", action(session), Map.of("structureId", "5"));
        verify(tools, never()).survey(any(), anyInt());
        verify(player).sendMessage(org.mockito.ArgumentMatchers.contains("Only staff"));

        staff = true;
        registries.actions().execute("structures.survey", action(session), Map.of("structureId", "5"));
        registries.actions().execute("structures.outline", action(session), Map.of("structureId", "5"));
        verify(tools).survey(player, 5);
        verify(tools).outline(player, 5);
    }

    @Test
    void cancelAndRetry_needAStaffAccount_andRepaint() {
        staff = true;
        loggedIn();
        when(api.cancelOrder(31, 9)).thenReturn(CompletableFuture.completedFuture(null));
        when(api.retryOrder(32, 9)).thenReturn(CompletableFuture.failedFuture(
                new CompletionException(new ApiException("u", 409, "x", "{\"message\":\"The order is on the road\"}"))));
        MenuSession session = new MenuSessionRegistry().open(uuid);
        var registries = ContentFeatures.all(feature);

        registries.actions().execute("structures.order-cancel", action(session), Map.of("orderId", "31"));
        registries.actions().execute("structures.order-retry", action(session), Map.of("orderId", "32"));

        verify(player).sendMessage(org.mockito.ArgumentMatchers.contains("Transport #31 cancelled."));
        verify(player).sendMessage(org.mockito.ArgumentMatchers.contains("409: The order is on the road"));
        verify(menuService, times(2)).refreshOpenMenu(player);
    }

    @Test
    void pending_onlyForAStructuresConfirmation() {
        MenuSession session = new MenuSessionRegistry().open(uuid);
        var registries = ContentFeatures.all(feature);

        assertFalse(registries.conditions().test("structures.pending", action(session), Map.of()).allowed());
        session.setPendingConfirmation(new MenuSession.PendingConfirmation("users.ban", Map.of(), "?"));
        assertFalse(registries.conditions().test("structures.pending", action(session), Map.of()).allowed());
        session.setPendingConfirmation(new MenuSession.PendingConfirmation("structures.order-cancel", Map.of("orderId", "31"), "?"));
        assertTrue(registries.conditions().test("structures.pending", action(session), Map.of()).allowed());
    }

    @Test
    void dryRunLines_sayWhatWouldHappen() {
        List<String> lines = StructuresMenuFeature.dryRunLines(new TransportDryRun(5, true, true, "Connected", 900, 1000L,
                0.8, 128, 2, 2, List.of(), List.of("Output 90% full"), List.of("Held: no warehouse room"), "128 Cobblestone", 1));

        assertTrue(lines.stream().anyMatch(l -> l.contains("Output 90% full")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Held: no warehouse room")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("128 Cobblestone")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("80%")));
    }

    @Test
    void ids() {
        assertEquals(5, StructuresMenuFeature.parseId(" 5 "));
        assertNull(StructuresMenuFeature.parseId("x"));
        assertNull(StructuresMenuFeature.parseId("0"));
        assertNull(StructuresMenuFeature.parseId(null));
    }
}
