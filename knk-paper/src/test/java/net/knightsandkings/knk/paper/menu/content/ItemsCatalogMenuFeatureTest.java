package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Content port CP4: the {@code itemsCatalog} total-count root and the {@code items.catalog} seed. */
class ItemsCatalogMenuFeatureTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-25T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static CompletableFuture<Page<KnkItemBlueprint>> total(int count) {
        return CompletableFuture.completedFuture(new Page<>(List.of(), count, 1, 1));
    }

    @Test
    void countIsFetchedInTheBackgroundAndRefreshedWhenStale() {
        ItemBlueprintsDataAccess blueprints = mock(ItemBlueprintsDataAccess.class);
        CompletableFuture<Page<KnkItemBlueprint>> first = new CompletableFuture<>();
        when(blueprints.searchAsync(any())).thenReturn(first, total(80));
        MutableClock clock = new MutableClock();
        ItemsCatalogMenuFeature feature = new ItemsCatalogMenuFeature(blueprints, clock);

        feature.registerMenuHandlers(ContentFeatures.engineDefaults());
        assertNull(feature.view().getTotalLine(), "unknown until the first answer (line dropped)");
        verify(blueprints, times(1)).searchAsync(any());

        first.complete(new Page<>(List.of(), 77, 1, 1));
        assertEquals("&7Items in the catalogue: &f77", feature.view().getTotalLine());
        verify(blueprints, times(1)).searchAsync(any());

        clock.now = clock.now.plus(Duration.ofMinutes(2));
        feature.view();
        assertEquals(80, feature.view().getTotalCount());
    }

    @Test
    void itemsCatalogSeedValidatesAgainstTheRegisteredFeatures() {
        RuntimeMenu menu = ContentSeedFixture.assemble(ItemsCatalogMenuFeature.MENU_KEY);
        assertDoesNotThrow(() -> ContentSeedFixture.validate(menu, ContentFeatures.all()));
    }
}
