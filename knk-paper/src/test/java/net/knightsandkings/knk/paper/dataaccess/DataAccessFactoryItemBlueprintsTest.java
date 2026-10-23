package net.knightsandkings.knk.paper.dataaccess;

import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.ports.api.ItemBlueprintsQueryApi;
import net.knightsandkings.knk.paper.config.KnkConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** KNG-262: the item blueprint gateway uses entities.itemBlueprints' TTL, not the global cache TTL. */
class DataAccessFactoryItemBlueprintsTest {

    private static KnkItemBlueprint blueprint(int id) {
        return new KnkItemBlueprint(id, "Sword", null, null, "minecraft:iron_sword", "Sword", null, 1, 64, List.of(), 0,
                null, List.of(), List.of());
    }

    private static KnkConfig.EntityCacheSettings withItemBlueprints(KnkConfig.EntitySettings itemBlueprints) {
        KnkConfig.EntityCacheSettings d = KnkConfig.EntityCacheSettings.defaults();
        return new KnkConfig.EntityCacheSettings(d.users(), d.towns(), d.districts(), d.structures(), d.streets(),
                d.locations(), d.enchantments(), itemBlueprints, d.minecraftMaterials(), d.domains(), d.health(),
                d.menus(), d.permissions(), d.grades(), d.tags(), d.domainCatalog(), d.siege(), d.kits());
    }

    private static KnkConfig.EntitySettings ttlSeconds(int seconds) {
        KnkConfig.EntitySettings d = KnkConfig.EntitySettings.defaults();
        return new KnkConfig.EntitySettings(null, seconds, d.maxTtlMinutes(), d.maxTtlSeconds(), d.defaultPolicy(),
                d.allowStale(), d.retryAttempts(), d.retryBackoffMs());
    }

    @Test
    void aFreshEntryIsServedFromTheCacheWithinTheEntityTtl() {
        ItemBlueprintsQueryApi api = mock(ItemBlueprintsQueryApi.class);
        when(api.getById(7)).thenReturn(CompletableFuture.completedFuture(blueprint(7)));
        ItemBlueprintsDataAccess access = new DataAccessFactory(withItemBlueprints(ttlSeconds(3600)))
                .createItemBlueprintsDataAccess(api);

        FetchResult<KnkItemBlueprint> first = access.getByIdAsync(7).join();
        FetchResult<KnkItemBlueprint> second = access.getByIdAsync(7).join();

        assertTrue(first.isFromApi());
        assertTrue(second.isFromCache());
        verify(api, times(1)).getById(7);
    }

    /** Fails with the old wiring: the global 60 s TTL would still serve the entry. */
    @Test
    void anEntryExpiresAfterTheEntityTtlNotTheGlobalOne() throws InterruptedException {
        ItemBlueprintsQueryApi api = mock(ItemBlueprintsQueryApi.class);
        when(api.getById(7)).thenReturn(CompletableFuture.completedFuture(blueprint(7)));
        ItemBlueprintsDataAccess access = new DataAccessFactory(withItemBlueprints(ttlSeconds(1)))
                .createItemBlueprintsDataAccess(api);

        access.getByIdAsync(7).join();
        Thread.sleep(1_200L);
        FetchResult<KnkItemBlueprint> afterTtl = access.getByIdAsync(7).join();

        assertTrue(afterTtl.isFromApi());
        verify(api, times(2)).getById(7);
    }
}
