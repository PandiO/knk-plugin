package net.knightsandkings.knk.core.regions;

import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.RegionSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import net.knightsandkings.knk.core.domain.domains.DomainRegionQuery;
import net.knightsandkings.knk.core.domain.domains.DomainRegionSummary;
import net.knightsandkings.knk.core.ports.api.DomainsQueryApi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for RegionDomainResolver, focused on the resolveRegions/getDomainByRegionId
 * staleness bug: WorldGuardRegionTracker.checkCacheStatus decides whether to proceed
 * synchronously with a transition using getDomainByRegionIdNoRefresh (age-tolerant), but
 * resolveRegions previously used the TTL-strict getDomainByRegionId, which silently discarded
 * (returned empty for) any entry older than cacheTtl. Once a region had been cached once, the
 * tracker never re-triggered a refresh (its own check doesn't look at age), so
 * enteredDomains/leftDomains came back permanently empty for that region after the first minute -
 * exactly the reported symptom of district gate loading never firing on entry. resolveRegions
 * must return a domain that's still present but stale, not silently drop it.
 */
class RegionDomainResolverTest {

    private static DomainSnapshot districtSnapshot(int id, String regionId) {
        return new DomainSnapshot(
            id, "TestDistrict", "A test district", regionId,
            true, true, "District",
            Set.of(), Set.of(), Set.of(), Set.of()
        );
    }

    @Test
    void resolveRegionsReturnsAFreshlyRegisteredDomain() {
        RegionDomainResolver resolver = new RegionDomainResolver();
        DomainSnapshot district = districtSnapshot(1000006, "district_1000006");
        resolver.registerDomain(district);

        RegionSnapshot snapshot = resolver.resolveRegions(Set.of("district_1000006"));

        assertEquals(1, snapshot.domains().size());
        assertTrue(snapshot.domains().contains(district));
    }

    @Test
    void resolveRegionsStillReturnsADomainAfterItsCacheEntryHasExpired() {
        // A negative TTL guarantees cachedAt.plus(ttl) is strictly before cachedAt itself, so the
        // entry reads as expired regardless of clock resolution - no sleep, no flakiness (a
        // zero-duration TTL isn't reliable here: on a coarse-resolution clock, Instant.now() at
        // check time can compare equal to cachedAt, which isBefore() treats as "not expired").
        RegionDomainResolver resolver = new RegionDomainResolver(
            null, null, null, null, null, null, null, Duration.ofSeconds(-1));
        DomainSnapshot district = districtSnapshot(1000006, "district_1000006");
        resolver.registerDomain(district);

        RegionSnapshot snapshot = resolver.resolveRegions(Set.of("district_1000006"));

        assertEquals(1, snapshot.domains().size());
        assertTrue(snapshot.domains().contains(district));
    }

    @Test
    void getDomainByRegionIdStillDiscardsAnExpiredEntry() {
        // Documents the intentionally different (TTL-strict) behavior of the other accessor,
        // used by isCached()/resolveRegionsFromApi() to decide whether an API refetch is due.
        RegionDomainResolver resolver = new RegionDomainResolver(
            null, null, null, null, null, null, null, Duration.ofSeconds(-1));
        resolver.registerDomain(districtSnapshot(1000006, "district_1000006"));

        assertFalse(resolver.getDomainByRegionId("district_1000006").isPresent());
    }

    @Test
    void getDomainByRegionIdNoRefreshReturnsAnExpiredEntryRegardless() {
        RegionDomainResolver resolver = new RegionDomainResolver(
            null, null, null, null, null, null, null, Duration.ofSeconds(-1));
        DomainSnapshot district = districtSnapshot(1000006, "district_1000006");
        resolver.registerDomain(district);

        assertEquals(district, resolver.getDomainByRegionIdNoRefresh("district_1000006").orElse(null));
    }

    // ---- KNG-104: refresh and eviction --------------------------------------------------------

    private static DomainRegionSummary summary(int id, String regionId, boolean allowEntry) {
        return new DomainRegionSummary(id, "TestDistrict", "", regionId, allowEntry, true, "District", List.of());
    }

    /** A domains API whose region-decision search answers {@code answer} and records every query. */
    private static final class FakeDomainsApi implements DomainsQueryApi {
        final List<Set<String>> queries = new ArrayList<>();
        Function<Set<String>, CompletableFuture<HashMap<Integer, DomainRegionSummary>>> answer =
            q -> CompletableFuture.completedFuture(new HashMap<>());

        @Override
        public CompletableFuture<DomainRegionSummary> getByWorldGuardRegionId(String wgRegionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<HashMap<Integer, DomainRegionSummary>> searchDomainRegionDecisions(DomainRegionQuery query) {
            queries.add(Set.copyOf(query.wgRegionIds()));
            return answer.apply(query.wgRegionIds());
        }

        static CompletableFuture<HashMap<Integer, DomainRegionSummary>> of(DomainRegionSummary... summaries) {
            HashMap<Integer, DomainRegionSummary> map = new HashMap<>();
            for (int i = 0; i < summaries.length; i++) {
                map.put(i, summaries[i]);
            }
            return CompletableFuture.completedFuture(map);
        }
    }

    private static RegionDomainResolver resolver(FakeDomainsApi api, Duration ttl) {
        return new RegionDomainResolver(null, null, null, api, null, null, null, ttl);
    }

    @Test
    void anExpiredEntryIsReAskedAndAChangedAllowEntryArrives() {
        FakeDomainsApi api = new FakeDomainsApi();
        RegionDomainResolver resolver = resolver(api, Duration.ofSeconds(-1));
        resolver.registerDomain(districtSnapshot(17, "domain_17")); // allowEntry true
        api.answer = q -> FakeDomainsApi.of(summary(17, "domain_17", false));

        resolver.refreshIfStale(Set.of("domain_17")).join();

        assertEquals(List.of(Set.of("domain_17")), api.queries);
        assertFalse(resolver.getDomainByRegionIdNoRefresh("domain_17").orElseThrow().allowEntry());
    }

    @Test
    void aRegionTheApiNoLongerKnowsIsForgotten() {
        // live test 2026-10-09 (KNG-92 P1): the district moved to another region; the old one kept "entry denied"
        FakeDomainsApi api = new FakeDomainsApi();
        RegionDomainResolver resolver = resolver(api, Duration.ofSeconds(-1));
        resolver.registerDomain(districtSnapshot(17, "domain_17"));

        resolver.refreshIfStale(Set.of("domain_17")).join();

        assertTrue(resolver.getDomainByRegionIdNoRefresh("domain_17").isEmpty());
    }

    @Test
    void aFreshEntryOrAnUncachedRegionIsNotAsked() {
        FakeDomainsApi api = new FakeDomainsApi();
        RegionDomainResolver resolver = resolver(api, Duration.ofMinutes(1));
        resolver.registerDomain(districtSnapshot(16, "domain_16"));

        resolver.refreshIfStale(Set.of("domain_16", "never_seen")).join();

        assertTrue(api.queries.isEmpty());
        assertTrue(resolver.getDomainByRegionIdNoRefresh("domain_16").isPresent());
    }

    @Test
    void aRegionIsAskedAboutOnceAtATime() {
        FakeDomainsApi api = new FakeDomainsApi();
        CompletableFuture<HashMap<Integer, DomainRegionSummary>> pending = new CompletableFuture<>();
        api.answer = q -> pending;
        RegionDomainResolver resolver = resolver(api, Duration.ofSeconds(-1));
        resolver.registerDomain(districtSnapshot(17, "domain_17"));

        CompletableFuture<Void> first = resolver.refreshIfStale(Set.of("domain_17"));
        resolver.refreshIfStale(Set.of("domain_17"));
        assertEquals(1, api.queries.size(), "the second call finds the first in flight");
        assertTrue(resolver.getDomainByRegionIdNoRefresh("domain_17").isPresent(), "served meanwhile");

        pending.complete(new HashMap<>(Map.of(0, summary(17, "domain_17", false))));
        first.join();
        resolver.refreshIfStale(Set.of("domain_17")).join();
        assertEquals(2, api.queries.size(), "asked again once the first answer is in");
    }

    @Test
    void aSeveralRegionAnswerDoesNotForgetWhatItLeftOut() {
        // the API answers at most one Town, District and Structure per query: a region missing from a
        // multi-region answer may still have a domain
        FakeDomainsApi api = new FakeDomainsApi();
        RegionDomainResolver resolver = resolver(api, Duration.ofSeconds(-1));
        DomainSnapshot keep = districtSnapshot(16, "domain_16");
        resolver.registerDomain(keep);
        api.answer = q -> FakeDomainsApi.of(summary(17, "domain_17", true));

        resolver.resolveRegionsFromApi(Set.of("domain_16", "domain_17")).join();

        assertSame(keep, resolver.getDomainByRegionIdNoRefresh("domain_16").orElseThrow());
        assertTrue(resolver.getDomainByRegionIdNoRefresh("domain_17").isPresent());
    }

    @Test
    void cacheRefreshForgetsEveryRegion() {
        RegionDomainResolver resolver = new RegionDomainResolver();
        resolver.registerDomain(districtSnapshot(16, "domain_16"));

        resolver.clearRegionCache();

        assertTrue(resolver.getDomainByRegionIdNoRefresh("domain_16").isEmpty());
    }
}
