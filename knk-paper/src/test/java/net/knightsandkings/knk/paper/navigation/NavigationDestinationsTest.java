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
            List<KnkDomainSummary> all = List.of(new KnkDomainSummary(1, "Kardenna", "Town"), new KnkDomainSummary(2, "Market", "Town"),
                new KnkDomainSummary(3, "Market", "District"), new KnkDomainSummary(9, "Mill House", "Structure"),
                new KnkDomainSummary(11, "Keep Gate", "GateStructure"), new KnkDomainSummary(50, "Wild", "Kingdom"));
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
        assertEquals(Located.Failure.NO_LOCATION, NavigationDestinations.locateDomain(bare, Mode.DEFAULT, "world").failure());
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
