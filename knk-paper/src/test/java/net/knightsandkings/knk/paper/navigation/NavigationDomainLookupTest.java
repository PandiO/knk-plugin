package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.RegionSnapshot;

/**
 * KNG-123: a domain lookup on the main thread (a {@code /navigate} policy, the route re-check) never waits for the
 * API - with the API unreachable it froze the server for up to 3 s per region missing from the cache.
 */
class NavigationDomainLookupTest {

    private static final DomainSnapshot TOWN = new DomainSnapshot(4, "Town", null, "town_4", false, true, "Town",
        Set.of(), Set.of(), Set.of(), Set.of());

    private final RegionDomainResolver resolver = mock(RegionDomainResolver.class);
    private final AtomicBoolean mainThread = new AtomicBoolean(true);
    private NavigationDomainLookup access;

    @BeforeEach
    void setUp() {
        access = new NavigationDomainLookup(resolver, mainThread::get);
    }

    @Test
    void mainThread_cachedDomainIsAnswered() {
        when(resolver.getDomainByRegionIdNoRefresh("world", "town_4")).thenReturn(Optional.of(TOWN));
        when(resolver.refreshIfStale(eq("world"), any())).thenReturn(CompletableFuture.completedFuture(null));

        assertEquals(Optional.of(TOWN), access.domainByRegionId("world", "town_4"));
        verify(resolver, never()).resolveRegionsFromApi(any(), any());
    }

    @Test
    void mainThread_missingDomainIsUnknownAndAskedForInTheBackground() {
        when(resolver.getDomainByRegionIdNoRefresh("world", "town_4")).thenReturn(Optional.empty());
        CompletableFuture<RegionSnapshot> neverAnswers = new CompletableFuture<>();
        when(resolver.resolveRegionsFromApi("world", Set.of("town_4"))).thenReturn(neverAnswers);

        long start = System.nanoTime();
        Optional<DomainSnapshot> answer = access.domainByRegionId("world", "town_4");

        assertTrue(answer.isEmpty(), "unknown: open, as for the trail");
        assertTrue(System.nanoTime() - start < 1_000_000_000L, "did not wait for the API");
        verify(resolver, times(1)).resolveRegionsFromApi("world", Set.of("town_4"));
    }

    @Test
    void mainThread_oneBackgroundLookupPerRegionAtATime() {
        when(resolver.getDomainByRegionIdNoRefresh("world", "town_4")).thenReturn(Optional.empty());
        CompletableFuture<RegionSnapshot> pending = new CompletableFuture<>();
        when(resolver.resolveRegionsFromApi("world", Set.of("town_4"))).thenReturn(pending);

        access.domainByRegionId("world", "town_4");
        access.domainByRegionId("world", "town_4");
        verify(resolver, times(1)).resolveRegionsFromApi("world", Set.of("town_4"));

        pending.completeExceptionally(new RuntimeException("connection refused"));
        access.domainByRegionId("world", "town_4");
        verify(resolver, times(2)).resolveRegionsFromApi("world", Set.of("town_4"));
    }

    @Test
    void mainThread_theBackgroundAnswerIsUsedNextTime() {
        when(resolver.getDomainByRegionIdNoRefresh("world", "town_4")).thenReturn(Optional.empty(), Optional.of(TOWN));
        when(resolver.resolveRegionsFromApi("world", Set.of("town_4")))
            .thenReturn(CompletableFuture.completedFuture(new RegionSnapshot(Set.of(TOWN))));
        when(resolver.refreshIfStale(eq("world"), any())).thenReturn(CompletableFuture.completedFuture(null));

        assertTrue(access.domainByRegionId("world", "town_4").isEmpty());
        assertEquals(Optional.of(TOWN), access.domainByRegionId("world", "town_4"));
    }

    @Test
    void offTheMainThread_stillAsksTheApiAndWaits() {
        mainThread.set(false);
        when(resolver.getDomainByRegionIdNoRefresh("world", "town_4")).thenReturn(Optional.empty(), Optional.of(TOWN));
        when(resolver.resolveRegionsFromApi("world", Set.of("town_4")))
            .thenReturn(CompletableFuture.completedFuture(new RegionSnapshot(Set.of(TOWN))));

        assertEquals(Optional.of(TOWN), access.domainByRegionId("world", "town_4"));
    }

    @Test
    void offTheMainThread_apiDownIsUnknown() {
        mainThread.set(false);
        when(resolver.getDomainByRegionIdNoRefresh("world", "town_4")).thenReturn(Optional.empty());
        when(resolver.resolveRegionsFromApi("world", Set.of("town_4")))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("connection refused")));

        assertTrue(access.domainByRegionId("world", "town_4").isEmpty());
    }
}
