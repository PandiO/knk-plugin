package net.knightsandkings.knk.paper.dataaccess;

import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.SiegeDataAccess;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeRuntimeConfig;
import net.knightsandkings.knk.paper.config.KnkConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 4: the factory builds SiegeDataAccess from the entities.siege settings. */
class DataAccessFactorySiegeTest {

    @Test
    void defaultSiegeSettingsUseALongTtlAndCacheFirst() {
        KnkConfig.EntitySettings siege = KnkConfig.EntityCacheSettings.defaults().siege();

        assertEquals(Duration.ofMinutes(30), siege.ttl());
        assertEquals("CACHE_FIRST", siege.policyName());
        assertTrue(siege.isStaleAllowed());
    }

    @Test
    void createsACacheFirstSiegeGateway() {
        AtomicInteger calls = new AtomicInteger();
        DataAccessFactory factory = new DataAccessFactory(KnkConfig.EntityCacheSettings.defaults());

        SiegeDataAccess access = factory.createSiegeDataAccess(
                () -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new KnkSiegeRuntimeConfig(null, KnkSiegeConfiguration.legacyDefaults(), List.of()));
                },
                id -> CompletableFuture.completedFuture(null));

        FetchResult<KnkSiegeRuntimeConfig> first = access.getRuntimeConfigAsync().join();
        FetchResult<KnkSiegeRuntimeConfig> second = access.getRuntimeConfigAsync().join();

        assertTrue(first.isFromApi());
        assertTrue(second.isFromCache());
        assertEquals(1, calls.get());
    }
}
