package net.knightsandkings.knk.core.dataaccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplateSummary;
import net.knightsandkings.knk.core.ports.api.MenuTemplatesQueryApi;

/** PandiO/knk-workspace#540: a scenario's fake menu template is primed into the live cache, with no API behind it. */
class MenuTemplatesDataAccessPrimeTest {

    private final AtomicInteger apiCalls = new AtomicInteger();

    private final MenuTemplatesQueryApi downApi = new MenuTemplatesQueryApi() {
        @Override
        public CompletableFuture<KnkMenuTemplate> getById(int id) {
            apiCalls.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("API down"));
        }

        @Override
        public CompletableFuture<KnkMenuTemplate> getByKey(String key) {
            apiCalls.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("API down"));
        }

        @Override
        public CompletableFuture<List<KnkMenuTemplateSummary>> listAll() {
            return CompletableFuture.completedFuture(List.of());
        }
    };

    private final MenuTemplatesDataAccess access = new MenuTemplatesDataAccess(Duration.ofMinutes(5), downApi,
        new DataAccessSettings(FetchPolicy.CACHE_FIRST, true, RetryPolicy.noRetry()));

    private static final KnkMenuTemplate TEMPLATE =
        new KnkMenuTemplate(-1, "scenario.menu", "Scenario menu", null, 3, "Static", null, List.of());

    @Test
    void aPrimedTemplateIsReadWithoutTheApi() {
        access.prime(TEMPLATE);

        assertSame(TEMPLATE, access.getByKeyAsync("scenario.menu").join().value().orElseThrow());
        assertEquals(0, apiCalls.get());
    }

    @Test
    void anEvictedTemplateIsAskedForAgain() {
        access.prime(TEMPLATE);

        access.evict("scenario.menu");

        assertFalse(access.getByKeyAsync("scenario.menu").join().isSuccess());
        assertEquals(1, apiCalls.get());
    }

    @Test
    void aTemplateWithoutAKeyIsRefused() {
        assertThrows(NullPointerException.class,
            () -> access.prime(new KnkMenuTemplate(-1, null, "x", null, 3, "Static", null, List.of())));
    }
}
