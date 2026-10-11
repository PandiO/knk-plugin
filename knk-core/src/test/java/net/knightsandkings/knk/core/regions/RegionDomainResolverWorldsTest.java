package net.knightsandkings.knk.core.regions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.domains.DomainRegionQuery;
import net.knightsandkings.knk.core.domain.domains.DomainRegionSummary;
import net.knightsandkings.knk.core.ports.api.DomainsQueryApi;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KNG-112: the same WorldGuard region id can exist in several worlds, each with its own domain. The resolver caches
 * domains by world and region, asks the API for one world's domains, and never answers with another world's domain.
 */
class RegionDomainResolverWorldsTest {

    /** Answers every region-decision search with {@code answer} and records the queries. */
    private static final class FakeDomainsApi implements DomainsQueryApi {
        final List<DomainRegionQuery> queries = new ArrayList<>();
        final HashMap<Integer, DomainRegionSummary> answer = new HashMap<>();

        @Override
        public CompletableFuture<DomainRegionSummary> getByWorldGuardRegionId(String wgRegionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<HashMap<Integer, DomainRegionSummary>> searchDomainRegionDecisions(DomainRegionQuery query) {
            queries.add(query);
            return CompletableFuture.completedFuture(answer);
        }
    }

    private static DomainSnapshot town(int id, String name) {
        return new DomainSnapshot(id, name, null, "town_1", true, true, "Town", Set.of(), Set.of(), Set.of(), Set.of());
    }

    private static RegionDomainResolver resolver(FakeDomainsApi api) {
        return new RegionDomainResolver(null, null, null, api);
    }

    @Test
    void theApiIsAskedForTheRegionsOfOneWorld() {
        FakeDomainsApi api = new FakeDomainsApi();
        api.answer.put(0, new DomainRegionSummary(7, "Hubtown", "", "town_1", true, true, "Town", List.of(), "hub"));
        RegionDomainResolver resolver = resolver(api);

        RegionDomainResolver.RegionSnapshot snapshot = resolver.resolveRegionsFromApi("hub", Set.of("town_1")).join();

        assertEquals("hub", api.queries.get(0).worldName());
        assertEquals(7, snapshot.domains().iterator().next().id());
        assertTrue(resolver.getDomainByRegionIdNoRefresh("gameplay", "town_1").isEmpty(), "cached for the hub only");
    }

    @Test
    void aDomainTheApiHasNoWorldForIsCachedForTheWorldThatAskedAboutIt() {
        FakeDomainsApi api = new FakeDomainsApi();
        api.answer.put(0, new DomainRegionSummary(8, "Old town", "", "town_1", true, true, "Town", List.of()));
        RegionDomainResolver resolver = resolver(api);

        resolver.resolveRegionsFromApi("gameplay", Set.of("town_1")).join();

        assertEquals(8, resolver.getDomainByRegionIdNoRefresh("gameplay", "town_1").orElseThrow().id());
        assertTrue(resolver.getDomainByRegionIdNoRefresh("hub", "town_1").isEmpty());
    }

    @Test
    void aWorldBlindQueryStillWorksAndIsCachedUnderTheDomainsOwnWorld() {
        FakeDomainsApi api = new FakeDomainsApi();
        api.answer.put(0, new DomainRegionSummary(9, "Hubtown", "", "town_1", true, true, "Town", List.of(), "hub"));
        RegionDomainResolver resolver = resolver(api);

        resolver.resolveRegionsFromApi(Set.of("town_1")).join();

        assertNull(api.queries.get(0).worldName());
        assertEquals(9, resolver.getDomainByRegionIdNoRefresh("HUB", "town_1").orElseThrow().id());
        assertEquals(9, resolver.getDomainByRegionIdNoRefresh("town_1").orElseThrow().id(), "only one world has it");
    }

    @Test
    void aDomainCachedWithoutAWorldServesEveryWorldUntilAWorldsOwnArrives() {
        RegionDomainResolver resolver = new RegionDomainResolver();
        resolver.registerDomain(town(1, "Legacy"));

        assertEquals("Legacy", resolver.getDomainByRegionIdNoRefresh("hub", "town_1").orElseThrow().name());

        resolver.registerDomain("hub", town(2, "Hubtown"));
        assertEquals("Hubtown", resolver.getDomainByRegionIdNoRefresh("hub", "town_1").orElseThrow().name());
        assertEquals("Legacy", resolver.getDomainByRegionIdNoRefresh("gameplay", "town_1").orElseThrow().name());
    }

    @Test
    void aWorldBlindLookupOfARegionInTwoWorldsGivesNoAnswer() {
        RegionDomainResolver resolver = new RegionDomainResolver();
        resolver.registerDomain("hub", town(1, "Hubtown"));
        resolver.registerDomain("gameplay", town(2, "Oakhaven"));

        assertTrue(resolver.getDomainByRegionIdNoRefresh("town_1").isEmpty());
        assertTrue(resolver.resolveRegions(Set.of("town_1")).domains().isEmpty());
        assertEquals(2, resolver.resolveRegions("gameplay", Set.of("town_1")).domains().iterator().next().id());
    }

    @Test
    void aWorldBlindRefreshReAsksAboutAnEntryTheApiCachedUnderItsWorld() {
        // KNG-104 (navigation refreshes world-blind): the API now names each domain's world, so the entry sits under it.
        FakeDomainsApi api = new FakeDomainsApi();
        RegionDomainResolver resolver = new RegionDomainResolver(null, null, null, api, null, null, null,
            java.time.Duration.ofSeconds(-1));
        resolver.registerDomain("hub", town(7, "Hubtown"));
        api.answer.put(0, new DomainRegionSummary(7, "Hubtown", "", "town_1", false, true, "Town", List.of(), "hub"));

        resolver.refreshIfStale(Set.of("town_1")).join();

        assertEquals("hub", api.queries.get(0).worldName(), "asked about the entry's own world");
        assertEquals(Boolean.FALSE, resolver.getDomainByRegionIdNoRefresh("town_1").orElseThrow().allowEntry());
    }

    @Test
    void aTransitionBetweenWorldsLeavesOneTownAndEntersTheOther() {
        RegionDomainResolver resolver = new RegionDomainResolver();
        resolver.registerDomain("hub", town(1, "Hubtown"));
        resolver.registerDomain("gameplay", town(2, "Oakhaven"));
        SimpleRegionTransitionService service = new SimpleRegionTransitionService(resolver, null, null, false);

        RegionTransitionDecision decision = service.handleRegionTransition(java.util.UUID.randomUUID(),
            "hub", Set.of("town_1"), "gameplay", Set.of("town_1"));

        assertEquals("You are now entering Oakhaven.", decision.getMessage().orElseThrow());
    }
}
