package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.KitsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.item.KnkKit;
import net.knightsandkings.knk.core.domain.item.KnkKitAvailability;
import net.knightsandkings.knk.core.domain.item.KnkKitContent;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.menu.MenuSessionRegistry;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.paper.kit.KitGrantFlow;
import net.knightsandkings.knk.paper.menu.MenuActionContext;
import net.knightsandkings.knk.paper.menu.MenuContentSourceContext;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;
import net.knightsandkings.knk.paper.menu.MenuService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Content port CP2: {@code kits.available} rows, {@code kits.claim}/{@code kits.purchase}, the seed. */
class KitsMenuFeatureTest {

    private final KitsDataAccess kits = mock(KitsDataAccess.class);
    private final ItemBlueprintsDataAccess blueprints = mock(ItemBlueprintsDataAccess.class);
    private final MinecraftMaterialRefsDataAccess materials = mock(MinecraftMaterialRefsDataAccess.class);
    private final KitGrantFlow flow = mock(KitGrantFlow.class);
    private final Player player = mock(Player.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC);
    private final KitsMenuFeature feature = new KitsMenuFeature(kits, blueprints, materials, flow, clock);

    KitsMenuFeatureTest() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(flow.resolveUserId(player)).thenReturn(42);
        when(flow.requireUserId(player)).thenReturn(42);
    }

    private static KnkKitAvailability available(int id, String name) {
        return new KnkKitAvailability(id, name, null, true, null, null, false, null, null, false, null);
    }

    private static KnkKit kitWith(int id, int handBlueprint) {
        return new KnkKit(id, "k" + id, null, null, null, null, null, null, handBlueprint,
                List.of(new KnkKitContent(0, 100, 2)), null, null, null, false, 0, null, null, false, null);
    }

    private static KnkItemBlueprint blueprint(int id, String name, String key) {
        return new KnkItemBlueprint(id, name, null, null, key, null, null, null, null, List.of(), 0, null, List.of(), List.of());
    }

    private void stubKits() {
        when(kits.getAvailableForUserAsync(42)).thenReturn(CompletableFuture.completedFuture(
                List.of(available(1, "Archer"), available(2, "Knight"))));
        when(kits.getByIdAsync(1)).thenReturn(CompletableFuture.completedFuture(FetchResult.hit(kitWith(1, 10))));
        when(kits.getByIdAsync(2)).thenReturn(CompletableFuture.completedFuture(FetchResult.hit(kitWith(2, 20))));
        when(blueprints.getByIdAsync(10)).thenReturn(CompletableFuture.completedFuture(
                FetchResult.hit(blueprint(10, "Bow", "minecraft:bow"))));
        when(blueprints.getByIdAsync(20)).thenReturn(CompletableFuture.completedFuture(
                FetchResult.hit(blueprint(20, "Sword", "minecraft:iron_sword"))));
        when(blueprints.getByIdAsync(100)).thenReturn(CompletableFuture.completedFuture(
                FetchResult.hit(blueprint(100, "Arrow", "minecraft:arrow"))));
    }

    private List<KitMenuRow> fetch() {
        Page<KitMenuRow> page = feature.fetchRows(new MenuContentSourceContext(player, null, MenuContextParams.EMPTY)).join();
        return page.items();
    }

    @Test
    void rowsJoinAvailabilityWithContentsAndLookEachBlueprintUpOnce() {
        stubKits();

        List<KitMenuRow> rows = fetch();

        assertEquals(List.of("Archer", "Knight"), rows.stream().map(KitMenuRow::getName).toList());
        assertEquals("minecraft:bow", rows.get(0).getMaterial());
        assertTrue(rows.get(0).getLoreLines().contains("&7Other contents: &fArrow x2"));
        verify(blueprints, times(1)).getByIdAsync(100);
    }

    @Test
    void availabilityIsCachedPerViewerUntilAMutationInvalidatesIt() {
        stubKits();

        fetch();
        fetch();
        verify(kits, times(1)).getAvailableForUserAsync(42);

        feature.invalidate(42);
        fetch();
        verify(kits, times(2)).getAvailableForUserAsync(42);
    }

    @Test
    void noKitsShowsTheDisabledEmptyStateRow() {
        when(kits.getAvailableForUserAsync(42)).thenReturn(CompletableFuture.completedFuture(List.of()));

        List<KitMenuRow> rows = fetch();

        assertEquals(1, rows.size());
        assertEquals("DISABLED", rows.get(0).getDisplayMode());
    }

    @Test
    void claimActionUsesTheSharedFlowThenInvalidatesAndRepaints() {
        stubKits();
        fetch();
        when(flow.claim(player, 42, 2, "Knight")).thenReturn(CompletableFuture.completedFuture(true));
        MenuService menuService = mock(MenuService.class);
        MenuFeatureRegistries registries = ContentFeatures.all(feature);

        registries.actions().execute("kits.claim", context(menuService, null), Map.of("kitId", "2"));

        verify(flow).claim(player, 42, 2, "Knight");
        verify(menuService).refreshOpenMenu(player);
        fetch();
        verify(kits, times(2)).getAvailableForUserAsync(42);
    }

    @Test
    void purchaseActionUsesTheSharedFlow() {
        when(flow.purchase(player, 42, 5, "Kit #5")).thenReturn(CompletableFuture.completedFuture(true));
        MenuFeatureRegistries registries = ContentFeatures.all(feature);

        registries.actions().execute("kits.purchase", context(mock(MenuService.class), null), Map.of("kitId", "5"));

        verify(flow).purchase(player, 42, 5, "Kit #5");
        verify(flow, never()).claim(player, 42, 5, "Kit #5");
    }

    @Test
    void purchasePendingOnlyAllowsAPendingKitPurchase() {
        MenuFeatureRegistries registries = ContentFeatures.all(feature);
        MenuSession session = new MenuSessionRegistry().open(UUID.randomUUID());

        assertFalse(registries.conditions().test("kits.purchase-pending", context(null, session), Map.of()).allowed());
        session.setPendingConfirmation(new MenuSession.PendingConfirmation("users.ban", Map.of(), "?"));
        assertFalse(registries.conditions().test("kits.purchase-pending", context(null, session), Map.of()).allowed());
        session.setPendingConfirmation(new MenuSession.PendingConfirmation("kits.purchase", Map.of("kitId", "1"), "?"));
        assertTrue(registries.conditions().test("kits.purchase-pending", context(null, session), Map.of()).allowed());
    }

    @Test
    void kitsOverviewSeedValidatesAgainstTheRegisteredFeatures() {
        RuntimeMenu menu = ContentSeedFixture.assemble(KitsMenuFeature.MENU_KEY);
        assertDoesNotThrow(() -> ContentSeedFixture.validate(menu, ContentFeatures.all()));
    }

    private MenuActionContext context(MenuService menuService, MenuSession session) {
        return new MenuActionContext(player, session, Map.of(), menuService, null, null, null, null);
    }
}
