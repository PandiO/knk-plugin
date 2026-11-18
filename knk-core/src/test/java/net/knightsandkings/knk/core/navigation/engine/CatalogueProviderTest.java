package net.knightsandkings.knk.core.navigation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;
import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Mode;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Place;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine.Resolution.Outcome;
import net.knightsandkings.knk.core.navigation.destinations.DestinationList;
import net.knightsandkings.knk.core.navigation.destinations.InitialScopes;
import net.knightsandkings.knk.core.navigation.destinations.NavigationContext;
import net.knightsandkings.knk.core.navigation.destinations.Provider;
import net.knightsandkings.knk.core.navigation.model.DomainPlace;
import net.knightsandkings.knk.core.navigation.model.ModelPoint;
import net.knightsandkings.knk.core.navigation.model.NavigationModel;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;

/**
 * The catalogue provider (phase 3, PandiO/knk-workspace#69, A17, A07, A08): today's catalogue entries from the
 * navigation model and the road network, with every place in the candidate (no lookup at click time).
 */
class CatalogueProviderTest {

    static final String WORLD = "world";
    static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    static ModelPoint at(String world, double x) {
        return new ModelPoint(null, null, x, 64, 5, 0f, 0f, world);
    }

    static DomainPlace domain(int id, String name, String type, String navigationDefault, String region, ModelPoint spawn) {
        return new DomainPlace(id, name, type, navigationDefault, "Enforced", region, WORLD, spawn, true);
    }

    static NavigationModel model() {
        return new NavigationModel(Instant.parse("2026-11-18T10:00:00Z"), List.of(
            domain(1, "Kardenna", "Town", "Spawn", "kardenna", at(WORLD, 10)),
            domain(2, "Market", "District", "Region", "market", null),
            domain(3, "Market", "Town", null, null, at(WORLD, 30)),
            domain(4, "Keep Gate", "GateStructure", null, "keep_gate", at(WORLD, 40)),
            domain(5, "Farland", "Town", null, "farland", at("other_world", 50)),
            domain(6, "Somewhere", "Street", null, null, null),
            new DomainPlace(7, "Unknown Place", "Structure", null, "Enforced", null, WORLD, null, false)),
            List.of(new ModelPoint(41, "Fountain", 1, 65, 1, 0f, 0f, WORLD)),
            CompassSettings.defaults(), List.of());
    }

    static RoadNetworkSnapshot network() {
        RoadNetworkSnapshot.Builder b = RoadNetworkSnapshot.builder(WORLD);
        b.addStreet(new RoadNetworkSnapshot.Street(5, "Main Street"));
        b.addStreet(new RoadNetworkSnapshot.Street(6, " "));
        b.addNode(new RoadNode(88, 3, 64, 3, RoadNodeKind.JUNCTION, "West End", 1));
        b.addNode(new RoadNode(89, 4, 64, 4, RoadNodeKind.JUNCTION, null, 1));
        return b.build();
    }

    private final AtomicReference<NavigationModel> current = new AtomicReference<>(model());
    private final RoadNetworkSnapshot snapshot = network();
    private final CatalogueProvider provider = new CatalogueProvider(current::get, w -> WORLD.equals(w) ? snapshot : null);
    private final DestinationEngine engine = new DestinationEngine(InitialScopes.create(List.of(provider),
        Provider.fixed("siege-match", List.of())));

    static NavigationContext player() {
        return NavigationContext.of(PLAYER, WORLD).withPermissions(InitialScopes.NAVIGATE_NODE);
    }

    private Candidate byKey(String key) {
        return provider.candidates(player()).stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void domainsCarryTheirSpawnRegionDefaultAndTypeWords() {
        Candidate kardenna = byKey("domain:Town:1");
        assertEquals("Kardenna", kardenna.label());
        assertEquals("town", kardenna.typeWord());
        assertEquals(1, kardenna.id());
        assertEquals(CatalogueProvider.ID, kardenna.source());
        Place.Domain place = assertInstanceOf(Place.Domain.class, kardenna.place());
        assertEquals(10, place.spawn().x());
        assertEquals("kardenna", place.regionId());
        assertEquals(Mode.SPAWN, place.defaultMode());

        Place.Domain market = (Place.Domain) byKey("domain:District:2").place();
        assertNull(market.spawn());
        assertEquals(Mode.REGION, market.defaultMode());

        Candidate gate = byKey("domain:Structure:4");
        assertEquals("structure", gate.typeWord());
        assertEquals(List.of("gatestructure", "gate"), gate.aliases());
    }

    @Test
    void otherDomainTypesAreLeftOutAndAnUnknownPlaceOffersNothing() {
        assertTrue(provider.candidates(player()).stream().noneMatch(c -> c.label().equals("Somewhere")));
        Place.Domain unknown = (Place.Domain) byKey("domain:Structure:7").place();
        assertFalse(unknown.offers(Mode.DEFAULT));
        assertEquals(Outcome.NO_LOCATION, engine.resolve(player(), "Unknown Place", Mode.DEFAULT).outcome());
    }

    @Test
    void locationsStreetsAndNamedNodesAreListed() {
        Candidate fountain = byKey("location:41");
        assertEquals("location", fountain.typeWord());
        assertInstanceOf(Place.Point.class, fountain.place());
        Candidate street = byKey("street:5");
        assertEquals(new Place.Street(WORLD, 5), street.place());
        Candidate node = byKey("node:88");
        assertEquals("West End", node.label());
        assertEquals(88, ((Place.Node) node.place()).nodeId());
        assertTrue(provider.candidates(player()).stream().noneMatch(c -> c.key().equals("street:6") || c.key().equals("node:89")));
    }

    @Test
    void theOrderIsTodaysCatalogueOrder() {
        DestinationList list = engine.listFor(player());
        List<String> kinds = list.candidates().stream().map(c -> c.key().substring(0, c.key().indexOf(':'))).distinct().toList();
        assertEquals(List.of("domain", "location", "street", "node"), kinds);
    }

    @Test
    void namesResolveAsTodayTypeNameIdAmbiguityAndSuggestions() {
        assertEquals("domain:Town:1", engine.resolve(player(), "Kardenna", Mode.DEFAULT).destination().candidateKey());
        assertEquals("domain:Town:1", engine.resolve(player(), "town:Kardenna", Mode.DEFAULT).destination().candidateKey());
        assertEquals("domain:Town:1", engine.resolve(player(), "town:#1", Mode.DEFAULT).destination().candidateKey());
        assertEquals("domain:Structure:4", engine.resolve(player(), "gate:Keep Gate", Mode.DEFAULT).destination().candidateKey());
        DestinationEngine.Resolution market = engine.resolve(player(), "Market", Mode.DEFAULT);
        assertEquals(Outcome.AMBIGUOUS, market.outcome());
        assertEquals(List.of("district:Market", "town:Market"), market.choiceNames());
        DestinationEngine.Resolution typo = engine.resolve(player(), "Karden", Mode.DEFAULT);
        assertEquals(Outcome.NOT_FOUND, typo.outcome());
        assertEquals(List.of("Kardenna"), typo.suggestionNames());
        assertTrue(engine.resolve(player(), "street:Main Street", Mode.DEFAULT).found());
        assertTrue(engine.resolve(player(), "location:Fountain", Mode.DEFAULT).found());
    }

    @Test
    void anotherWorldsPlaceIsNotCompletedButResolvesApart() {
        assertTrue(engine.complete(player(), List.of("Far")).isEmpty());
        assertEquals(Outcome.OTHER_WORLD, engine.resolve(player(), "Farland", Mode.DEFAULT).outcome());
        assertEquals(List.of("Kardenna"), engine.complete(player(), List.of("Kar")));
    }

    @Test
    void withoutAModelOnlyTheNetworkIsOffered() {
        current.set(null);
        List<Candidate> limited = provider.candidates(player());
        assertEquals(List.of("street:5", "node:88"), limited.stream().map(Candidate::key).toList());
    }

    @Test
    void theModelPartIsBuiltOncePerModelAndAgainForANewOne() {
        List<Candidate> first = provider.candidates(player());
        List<Candidate> second = provider.candidates(player());
        assertSame(first.get(0), second.get(0));
        current.set(model().withAsOf(Instant.parse("2026-11-18T11:00:00Z")));
        assertFalse(first.get(0) == provider.candidates(player()).get(0), "a new model builds new candidates");
    }

    @Test
    void theNetworkPartFollowsTheWorldsSnapshot() {
        AtomicInteger asked = new AtomicInteger();
        CatalogueProvider counting = new CatalogueProvider(() -> null, w -> {
            asked.incrementAndGet();
            return snapshot;
        });
        assertEquals(2, counting.candidates(player()).size());
        assertEquals(2, counting.candidates(player().withWorld("WORLD")).size());
        assertEquals(2, asked.get(), "the snapshot is asked for every call, so a swapped network is picked up");
        assertTrue(new CatalogueProvider(() -> null, w -> null).candidates(player()).isEmpty());
    }
}
