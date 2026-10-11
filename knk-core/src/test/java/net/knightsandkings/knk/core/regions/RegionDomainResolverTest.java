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

    // ---- KNG-122: one request per region ------------------------------------------------------------

    /**
     * The API's answer as {@code DomainService.SearchDomainRegionDecisionAsync} gives it: of the regions asked about
     * that have a domain, at most one Town, one District and one Structure (the least nested of each).
     */
    private static Function<Set<String>, CompletableFuture<HashMap<Integer, DomainRegionSummary>>> likeTheApi(
            DomainRegionSummary... known) {
        return asked -> {
            HashMap<Integer, DomainRegionSummary> out = new HashMap<>();
            Set<String> types = new java.util.HashSet<>();
            for (DomainRegionSummary s : known) {
                if (asked.contains(s.wgRegionId()) && types.add(s.domainType())) {
                    out.put(out.size(), s);
                }
            }
            return CompletableFuture.completedFuture(out);
        };
    }

    @Test
    void warmingSeveralDistrictsCachesEveryOne() {
        // live test 2026-10-10 (KNG-110 G2): "preloading 4 regions: [district_1000004, district_1000006, domain_16,
        // domain_17]" cached 1 domain, and domain_17 stayed unknown
        FakeDomainsApi api = new FakeDomainsApi();
        api.answer = likeTheApi(summary(4, "district_1000004", true), summary(6, "district_1000006", true),
            summary(16, "domain_16", false), summary(17, "domain_17", false));
        RegionDomainResolver resolver = resolver(api, Duration.ofMinutes(1));

        resolver.warmCache(List.of("district_1000004", "district_1000006", "domain_16", "domain_17")).join();

        for (String region : List.of("district_1000004", "district_1000006", "domain_16", "domain_17")) {
            assertTrue(resolver.getDomainByRegionIdNoRefresh(region).isPresent(), region);
        }
        assertEquals(List.of(Set.of("district_1000004"), Set.of("district_1000006"), Set.of("domain_16"),
            Set.of("domain_17")), api.queries, "one request per region");
    }

    @Test
    void resolvingTwoNestedDistrictsAtOneSpotReturnsBoth() {
        FakeDomainsApi api = new FakeDomainsApi();
        api.answer = likeTheApi(summary(4, "district_1000004", true), summary(17, "domain_17", false));
        RegionDomainResolver resolver = resolver(api, Duration.ofMinutes(1));

        RegionSnapshot snapshot = resolver.resolveRegionsFromApi(Set.of("district_1000004", "domain_17")).join();

        assertEquals(Set.of(4, 17), snapshot.domains().stream().map(DomainSnapshot::id).collect(
            java.util.stream.Collectors.toSet()));
    }

    @Test
    void aRegionWithoutADomainIsLeftOutAndACachedOneIsNotAskedAgain() {
        FakeDomainsApi api = new FakeDomainsApi();
        api.answer = likeTheApi(summary(17, "domain_17", false));
        RegionDomainResolver resolver = resolver(api, Duration.ofMinutes(1));
        DomainSnapshot keep = districtSnapshot(16, "domain_16");
        resolver.registerDomain(keep);

        RegionSnapshot snapshot = resolver.resolveRegionsFromApi(Set.of("domain_16", "domain_17", "lootbox_test")).join();

        assertEquals(2, snapshot.domains().size());
        assertSame(keep, resolver.getDomainByRegionIdNoRefresh("domain_16").orElseThrow());
        assertTrue(resolver.getDomainByRegionIdNoRefresh("lootbox_test").isEmpty());
        assertEquals(List.of(Set.of("domain_17"), Set.of("lootbox_test")), api.queries);
    }

    @Test
    void atMostFourRequestsAreInFlight() {
        FakeDomainsApi api = new FakeDomainsApi();
        List<CompletableFuture<HashMap<Integer, DomainRegionSummary>>> pending = new ArrayList<>();
        api.answer = q -> {
            CompletableFuture<HashMap<Integer, DomainRegionSummary>> f = new CompletableFuture<>();
            pending.add(f);
            return f;
        };
        RegionDomainResolver resolver = resolver(api, Duration.ofMinutes(1));

        CompletableFuture<Void> warm = resolver.warmCache(List.of("r1", "r2", "r3", "r4", "r5", "r6"));
        assertEquals(RegionDomainResolver.MAX_REQUESTS_IN_FLIGHT, api.queries.size());

        pending.get(0).complete(new HashMap<>());
        assertEquals(5, api.queries.size(), "the next one starts when one is answered");
        assertFalse(warm.isDone());
        for (int i = 1; i < 6; i++) {
            pending.get(i).complete(new HashMap<>());
        }
        assertTrue(warm.isDone());
        assertEquals(6, api.queries.size());
    }

    @Test
    void aFailedRequestDoesNotStopTheOthers() {
        FakeDomainsApi api = new FakeDomainsApi();
        api.answer = q -> q.contains("domain_16") ? CompletableFuture.failedFuture(new RuntimeException("API down"))
            : likeTheApi(summary(17, "domain_17", false)).apply(q);
        RegionDomainResolver resolver = resolver(api, Duration.ofMinutes(1));

        RegionSnapshot snapshot = resolver.resolveRegionsFromApi(Set.of("domain_16", "domain_17")).join();

        assertEquals(1, snapshot.domains().size());
        assertTrue(resolver.getDomainByRegionIdNoRefresh("domain_17").isPresent());
        resolver.warmCache(List.of("domain_16")).join(); // completes, does not throw
    }

    @Test
    void aFailedRefreshCompletesAndKeepsTheOldEntry() {
        FakeDomainsApi api = new FakeDomainsApi();
        api.answer = q -> CompletableFuture.failedFuture(new RuntimeException("API down"));
        RegionDomainResolver resolver = resolver(api, Duration.ofSeconds(-1));
        DomainSnapshot keep = districtSnapshot(17, "domain_17");
        resolver.registerDomain(keep);

        resolver.refreshIfStale(Set.of("domain_17")).join(); // does not throw

        assertSame(keep, resolver.getDomainByRegionIdNoRefresh("domain_17").orElseThrow());
    }

    @Test
    void cacheRefreshForgetsEveryRegion() {
        RegionDomainResolver resolver = new RegionDomainResolver();
        resolver.registerDomain(districtSnapshot(16, "domain_16"));

        resolver.clearRegionCache();

        assertTrue(resolver.getDomainByRegionIdNoRefresh("domain_16").isEmpty());
    }
}
