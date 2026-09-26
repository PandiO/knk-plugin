package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.item.KnkItemCategory;
import net.knightsandkings.knk.core.domain.material.KnkMinecraftMaterialRef;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.ports.api.CategoriesQueryApi;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CP4 + menu follow-up 2026-09-26: catalogue rows (full blueprints), category values, total count, seed. */
class ItemsCatalogMenuFeatureTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-25T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final ItemBlueprintsDataAccess blueprints = mock(ItemBlueprintsDataAccess.class);
    private final MinecraftMaterialRefsDataAccess refs = mock(MinecraftMaterialRefsDataAccess.class);
    private final CategoriesQueryApi categories = () -> CompletableFuture.completedFuture(List.of(
            new KnkItemCategory(2, "Swords", 1), new KnkItemCategory(1, "Weapons", null),
            new KnkItemCategory(3, "armor", null), new KnkItemCategory(4, "Odd, name", null)));

    private static KnkItemBlueprint blueprint(int id, String name, Integer refId, String description) {
        return new KnkItemBlueprint(id, name, null, refId, "minecraft:stick", name, description, 1, 64, List.of(), 0,
                null, List.of(), List.of());
    }

    private static CompletableFuture<Page<KnkItemBlueprint>> total(int count) {
        return CompletableFuture.completedFuture(new Page<>(List.of(), count, 1, 1));
    }

    @Test
    void countIsFetchedInTheBackgroundAndRefreshedWhenStale() {
        CompletableFuture<Page<KnkItemBlueprint>> first = new CompletableFuture<>();
        when(blueprints.searchAsync(any())).thenReturn(first, total(80));
        MutableClock clock = new MutableClock();
        ItemsCatalogMenuFeature feature = new ItemsCatalogMenuFeature(blueprints, refs, categories, clock);

        feature.registerMenuHandlers(ContentFeatures.engineDefaults());
        assertNull(feature.view().getTotalLine(), "unknown until the first answer (line dropped)");
        verify(blueprints, times(1)).searchAsync(any());

        first.complete(new Page<>(List.of(), 77, 1, 1));
        assertEquals("&7Items in the catalogue: &f77", feature.view().getTotalLine());

        clock.now = clock.now.plus(Duration.ofMinutes(2));
        feature.view();
        assertEquals(80, feature.view().getTotalCount());
    }

    @Test
    void categoryValuesAreSortedAndSkipNamesWithCommas() {
        ItemsCatalogMenuFeature feature = new ItemsCatalogMenuFeature(blueprints, refs, categories, Clock.systemUTC());
        when(blueprints.searchAsync(any())).thenReturn(total(0));
        feature.registerMenuHandlers(ContentFeatures.engineDefaults());

        assertEquals("armor,Swords,Weapons", feature.view().getCategoryValues());
    }

    @Test
    void rowsUseTheFullBlueprintAndItsMaterialRefAndKeepThePageShape() {
        KnkItemBlueprint summary = blueprint(7, "Sword", 5, null);
        KnkItemBlueprint full = blueprint(7, "Sword", 5, "Sharp.\nVery sharp.");
        when(blueprints.searchAsync(any())).thenReturn(CompletableFuture.completedFuture(new Page<>(List.of(summary), 41, 2, 36)));
        when(blueprints.getByIdAsync(7)).thenReturn(CompletableFuture.completedFuture(FetchResult.hit(full)));
        when(refs.getByIdAsync(5)).thenReturn(CompletableFuture.completedFuture(FetchResult.hit(
                new KnkMinecraftMaterialRef(5, "minecraft:iron_sword", null, null, null))));
        ItemsCatalogMenuFeature feature = new ItemsCatalogMenuFeature(blueprints, refs, categories, Clock.systemUTC());

        PagedQuery query = new PagedQuery(2, 36, null, null, false, Map.of("Category", "Weapons"));
        Page<CatalogItemRow> page = feature.fetchRows(query).join();

        assertEquals(41, page.totalCount());
        assertEquals(2, page.pageNumber());
        CatalogItemRow row = page.items().get(0);
        assertEquals(7, row.getBlueprintId());
        assertEquals(List.of(full, "minecraft:iron_sword"), row.menuRowKey());
        verify(blueprints).searchAsync(query);
    }

    @Test
    void itemsCatalogSeedValidatesAgainstTheRegisteredFeatures() {
        RuntimeMenu menu = ContentSeedFixture.assemble(ItemsCatalogMenuFeature.MENU_KEY);
        assertDoesNotThrow(() -> ContentSeedFixture.validate(menu, ContentFeatures.all()));
    }
}
