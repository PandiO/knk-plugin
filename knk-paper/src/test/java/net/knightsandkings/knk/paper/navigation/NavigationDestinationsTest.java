package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.dataaccess.DomainCatalogDataAccess;
import net.knightsandkings.knk.core.dataaccess.LocationsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.domains.KnkDomainSummary;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.towns.TownDetail;
import net.knightsandkings.knk.core.navigation.DomainLocationResolver;
import net.knightsandkings.knk.core.navigation.DomainLocationResolver.DomainPlace;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Located;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Mode;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations.Resolution;

/** The /navigate catalogue: API places + snapshot streets and nodes, resolution, locating (DESIGN §6.1). */
class NavigationDestinationsTest {

    private final NavigationTestNetwork network = new NavigationTestNetwork();
    private final DomainCatalogDataAccess domains = mock(DomainCatalogDataAccess.class);
    private final LocationsDataAccess locations = mock(LocationsDataAccess.class);
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final KnkLocation mill = new KnkLocation(30, "Kardenna Mill", 400.0, 65.0, 10.0, 0f, 0f, "world");
    private final KnkLocation nether = new KnkLocation(31, "Nether Hub", 0.0, 65.0, 0.0, 0f, 0f, "world_nether");
    private NavigationDestinations destinations;

    @BeforeEach
    void setUp() {
        when(domains.searchAsync(any())).thenAnswer(inv -> {
            PagedQuery q = inv.getArgument(0);
            List<KnkDomainSummary> all = List.of(new KnkDomainSummary(1, "Kardenna", "Town", "Spawn"), new KnkDomainSummary(2, "Market", "Town"),
                new KnkDomainSummary(3, "Market", "District", null, "Applies"), new KnkDomainSummary(9, "Mill House", "Structure", null, "Ignored"),
                new KnkDomainSummary(11, "Keep Gate", "GateStructure", "Region"), new KnkDomainSummary(50, "Wild", "Kingdom", null, "ignored"));
            return CompletableFuture.completedFuture(q.pageNumber() == 1 ? new Page<>(all, all.size(), 1, q.pageSize()) : new Page<>(List.of(), all.size(), q.pageNumber(), q.pageSize()));
        });
        when(locations.searchAsync(any())).thenAnswer(inv -> {
            PagedQuery q = inv.getArgument(0);
            List<KnkLocation> all = List.of(mill, nether);
            return CompletableFuture.completedFuture(q.pageNumber() == 1 ? new Page<>(all, 2, 1, q.pageSize()) : new Page<>(List.of(), 2, q.pageNumber(), q.pageSize()));
        });
        DomainLocationResolver resolver = new DomainLocationResolver(
            id -> CompletableFuture.completedFuture(Optional.empty()),
            id -> CompletableFuture.completedFuture(id == 1
                ? Optional.of(new TownDetail(1, "Kardenna", null, null, true, true, "kardenna", null,
                    new TownDetail.Location(5, "Kardenna Spawn", 50.0, 65.0, 5.0, 0f, 0f, "world"), List.of(), List.of(), List.of(), List.of()))
                : id == 2
                    ? Optional.of(new TownDetail(2, "Market", null, null, true, true, "market_town", null, null, List.of(), List.of(), List.of(), List.of()))
                    : Optional.empty()),
            id -> CompletableFuture.completedFuture(Optional.empty()),
            id -> CompletableFuture.completedFuture(Optional.empty()));
        destinations = new NavigationDestinations(domains, locations, resolver, w -> network.snapshot, clock::get);
        destinations.refresh().join();
    }

    @Test
    void theCatalogueMergesApiPlacesWithTheSnapshotsStreetsAndNodes() {
        List<NavTarget> all = destinations.catalogue("world");

        assertTrue(all.stream().anyMatch(t -> t.type() == NavTarget.Type.TOWN && t.name().equals("Kardenna")));
        assertTrue(all.stream().anyMatch(t -> t.type() == NavTarget.Type.LOCATION && t.name().equals("Kardenna Mill")));
        assertTrue(all.stream().anyMatch(t -> t.type() == NavTarget.Type.STREET && t.name().equals("Main Street")));
        assertTrue(all.stream().anyMatch(t -> t.type() == NavTarget.Type.NODE && t.name().equals("Cinix Keep")));
        assertFalse(all.stream().anyMatch(t -> t.name().equals("Wild")), "unknown domain types are skipped");
        assertEquals(5, destinations.remote().stream().filter(t -> t.type().isDomain()).count());
    }

    @Test
    void theCatalogueKnowsWhichDomainsRulesAreLiftedOffTheRoads() {
        // rev. 7 Part C (KNG-92): "Ignored" on the search, whatever the type; "Applies" or nothing keeps the rule
        assertTrue(destinations.roadAccessIgnored(9));
        assertTrue(destinations.roadAccessIgnored(50), "not a /navigate target, still a domain along a road");
        assertFalse(destinations.roadAccessIgnored(3));
        assertFalse(destinations.roadAccessIgnored(1));
        assertFalse(destinations.roadAccessIgnored(999));

        NavigationDestinations notLoaded = new NavigationDestinations(domains, locations,
            new DomainLocationResolver(id -> CompletableFuture.completedFuture(Optional.empty()),
                id -> CompletableFuture.completedFuture(Optional.empty()),
                id -> CompletableFuture.completedFuture(Optional.empty()),
                id -> CompletableFuture.completedFuture(Optional.empty())),
            w -> network.snapshot, clock::get);
        assertFalse(notLoaded.roadAccessIgnored(9), "before the first load every rule applies");
    }

    @Test
    void namesResolveAcrossEveryTypeAndAmbiguityNeedsTheTypeWord() {
        assertEquals(NavTarget.Type.TOWN, destinations.resolve("kardenna", "world").target().type());
        assertEquals(NavTarget.Type.NODE, destinations.resolve("node:Cinix Keep", "world").target().type());
        assertEquals(NavTarget.Type.STREET, destinations.resolve("main street", "world").target().type());
        assertEquals(NavTarget.Type.LOCATION, destinations.resolve("location:#30", "world").target().type());

        Resolution market = destinations.resolve("Market", "world");
        assertTrue(market.ambiguous());
        assertEquals(List.of("town:Market", "district:Market"), market.choiceNames());
        assertEquals(NavTarget.Type.DISTRICT, destinations.resolve("district:market", "world").target().type());

        Resolution unknown = destinations.resolve("Kard", "world");
        assertFalse(unknown.found());
        assertEquals(List.of("Kardenna", "Kardenna Mill", "Kardenna Castle"), unknown.suggestionNames(), "API places first, then the snapshot's nodes");
    }

    @Test
    void gatesAreStructuresThatAlsoAnswerToTheirOwnWordAndItsNickname() {
        NavTarget gate = destinations.resolve("Keep Gate", "world").target();
        assertEquals(NavTarget.Type.STRUCTURE, gate.type(), "a GateStructure is a Structure, not dropped");
        assertEquals(11, gate.id());
        assertEquals(List.of("gatestructure", "gate"), gate.aliases());
        assertEquals("structure:Keep Gate", gate.qualifiedName());
        assertEquals(gate, destinations.resolve("structure:Keep Gate", "world").target());
        assertEquals(gate, destinations.resolve("gatestructure:keep gate", "world").target());
        assertEquals(gate, destinations.resolve("gate:#11", "world").target());
        assertFalse(destinations.resolve("gate:Mill House", "world").found(), "a plain Structure is no gate");

        assertEquals(List.of("structure:Mill", "structure:Keep"), destinations.complete(List.of("structure:"), "world"));
        assertEquals(List.of("gate:Keep"), destinations.complete(List.of("gate:"), "world"));
        assertEquals(List.of("Gate"), destinations.complete(List.of("gatestructure:Keep", ""), "world"));
    }

    @Test
    void theCatalogueTypeWordMapsSubtypesToTheirType() {
        assertEquals(NavTarget.Type.STRUCTURE, NavTarget.Type.ofDomainType("GateStructure"));
        assertEquals(NavTarget.Type.STRUCTURE, NavTarget.Type.ofDomainType("structure"));
        assertEquals(NavTarget.Type.TOWN, NavTarget.Type.ofDomainType("Town"));
        assertEquals(null, NavTarget.Type.ofDomainType("Kingdom"));
        assertEquals(List.of(), NavTarget.domain(NavTarget.Type.STRUCTURE, 9, "Mill House", "Structure").aliases());
    }

    @Test
    void completionWorksOneWordAtATime() {
        assertEquals(List.of("Main"), destinations.complete(List.of("mai"), "world"));
        assertEquals(List.of("Street"), destinations.complete(List.of("Main", ""), "world"));
        assertTrue(destinations.isComplete(List.of("Main", "Street"), "world"));
        assertFalse(destinations.isComplete(List.of("Main"), "world"));
    }

    @Test
    void locatingPicksTheSpawnLocationTheRegionOrTheFailure() {
        NavTarget kardenna = destinations.resolve("Kardenna", "world").target();
        Located spawn = destinations.locate(kardenna, Mode.DEFAULT, "world").join();
        assertEquals(Destination.Kind.POINT, spawn.destination().kind());
        assertEquals("kardenna", spawn.destination().regionId(), "default: being in the region is being there (N9)");
        assertEquals(null, destinations.locate(kardenna, Mode.SPAWN, "world").join().destination().regionId(),
            "spawn: to the spawn point even from inside");
        assertEquals(50.0, spawn.destination().point()[0], 1e-9);

        Located region = destinations.locate(kardenna, Mode.REGION, "world").join();
        assertEquals(Destination.Kind.REGION, region.destination().kind());
        assertEquals("kardenna", region.destination().regionId());

        NavTarget marketTown = destinations.resolve("town:Market", "world").target();
        Located fallback = destinations.locate(marketTown, Mode.DEFAULT, "world").join();
        assertEquals(Destination.Kind.REGION, fallback.destination().kind(), "no Location → its region");

        NavTarget millHouse = destinations.resolve("Mill House", "world").target();
        assertEquals(Located.Failure.NOT_FOUND, destinations.locate(millHouse, Mode.DEFAULT, "world").join().failure());

        Located other = destinations.locate(destinations.resolve("Nether Hub", "world").target(), Mode.DEFAULT, "world").join();
        assertEquals(Located.Failure.OTHER_WORLD, other.failure());

        Located street = destinations.locate(destinations.resolve("Main Street", "world").target(), Mode.DEFAULT, "world").join();
        assertEquals(NavigationTestNetwork.STREET_MAIN, street.destination().streetId());
        Located node = destinations.locate(destinations.resolve("Cinix Keep", "world").target(), Mode.DEFAULT, "world").join();
        assertEquals(NavigationTestNetwork.C, node.destination().nodeId());
        assertEquals(65, node.destination().point()[1], 1e-9, "a node's feet position is floor + 1");
    }

    @Test
    void locateDomainWithoutAnythingFails() {
        DomainPlace bare = new DomainPlace(7, "Ruin", "Structure", null, Optional.empty());
        assertEquals(Located.Failure.NO_LOCATION, NavigationDestinations.locateDomain(bare, Mode.DEFAULT, Mode.SPAWN, "world").failure());
        assertEquals(Located.Failure.NO_LOCATION, NavigationDestinations.locateDomain(bare, Mode.DEFAULT, Mode.REGION, "world").failure());
    }

    @Test
    void theCatalogueCarriesEachDomainsDefaultMode() {
        assertEquals(Mode.SPAWN, destinations.resolve("Kardenna", "world").target().defaultMode());
        assertEquals(Mode.REGION, destinations.resolve("Keep Gate", "world").target().defaultMode());
        assertEquals(Mode.SPAWN, destinations.resolve("town:Market", "world").target().defaultMode(),
            "an API without the field: the spawn Location, as before KNG-73");
        assertEquals(Mode.SPAWN, destinations.resolve("Main Street", "world").target().defaultMode());
        assertEquals(Mode.REGION, NavTarget.domain(NavTarget.Type.TOWN, 1, "Kardenna", "Town", "region").defaultMode());
        assertEquals(Mode.SPAWN, NavTarget.domain(NavTarget.Type.TOWN, 1, "Kardenna", "Town", "Nearest").defaultMode());
    }

    @Test
    void aRegionDefaultGoesToTheRegionAndSpawnStillForcesTheSpawnLocation() {
        DomainPlace kardenna = new DomainPlace(1, "Kardenna", "Town", "kardenna", Optional.of(
            new KnkLocation(5, "Kardenna Spawn", 50.0, 65.0, 5.0, 0f, 0f, "world")));

        Located byDefault = NavigationDestinations.locateDomain(kardenna, Mode.DEFAULT, Mode.REGION, "world");
        assertEquals(Destination.Kind.REGION, byDefault.destination().kind(), "standing in a region goal is already there (N9)");
        assertEquals("kardenna", byDefault.destination().regionId());

        Located spawn = NavigationDestinations.locateDomain(kardenna, Mode.SPAWN, Mode.REGION, "world");
        assertEquals(Destination.Kind.POINT, spawn.destination().kind());
        assertEquals(null, spawn.destination().regionId(), "spawn: to the spawn point even from inside");

        Located spawnDefault = NavigationDestinations.locateDomain(kardenna, Mode.DEFAULT, Mode.SPAWN, "world");
        assertEquals(Destination.Kind.POINT, spawnDefault.destination().kind());
        assertEquals("kardenna", spawnDefault.destination().regionId(), "a spawn default keeps the already-there check");

        Located region = NavigationDestinations.locateDomain(kardenna, Mode.REGION, Mode.SPAWN, "world");
        assertEquals(Destination.Kind.REGION, region.destination().kind());
    }

    @Test
    void aRegionDefaultWithoutARegionFallsBackToTheSpawnLocation() {
        DomainPlace noRegion = new DomainPlace(4, "Well", "Structure", " ", Optional.of(
            new KnkLocation(6, "Well", 10.0, 64.0, 10.0, 0f, 0f, "world")));

        Located located = NavigationDestinations.locateDomain(noRegion, Mode.DEFAULT, Mode.REGION, "world");
        assertEquals(Destination.Kind.POINT, located.destination().kind());
        assertEquals(10.0, located.destination().point()[0], 1e-9);
        assertEquals(Located.Failure.OTHER_WORLD,
            NavigationDestinations.locateDomain(noRegion, Mode.DEFAULT, Mode.REGION, "world_nether").failure());
    }

    @Test
    void locatingATargetUsesItsDefaultMode() {
        NavTarget kardenna = destinations.resolve("Kardenna", "world").target();
        NavTarget byRegion = NavTarget.domain(kardenna.type(), kardenna.id(), kardenna.name(), "Town", "Region");

        assertEquals(Destination.Kind.POINT, destinations.locate(kardenna, Mode.DEFAULT, "world").join().destination().kind());
        assertEquals(Destination.Kind.REGION, destinations.locate(byRegion, Mode.DEFAULT, "world").join().destination().kind());
        assertEquals(Destination.Kind.POINT, destinations.locate(byRegion, Mode.SPAWN, "world").join().destination().kind());
    }

    @Test
    void theApiPartRefreshesOnlyWhenStale() {
        int before = destinations.remote().size();
        clock.addAndGet(NavigationDestinations.REFRESH_MILLIS - 1);
        destinations.catalogue("world");
        assertEquals(before, destinations.remote().size());
        clock.addAndGet(2);
        destinations.catalogue("world");
        assertEquals(before, destinations.remote().size(), "a refresh with the same data keeps the same list");
    }
}
