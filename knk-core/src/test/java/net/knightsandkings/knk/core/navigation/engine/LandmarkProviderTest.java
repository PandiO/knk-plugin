package net.knightsandkings.knk.core.navigation.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Mode;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Place;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine;
import net.knightsandkings.knk.core.navigation.destinations.DestinationEngine.Resolution.Outcome;
import net.knightsandkings.knk.core.navigation.destinations.DestinationList;
import net.knightsandkings.knk.core.navigation.destinations.InitialScopes;
import net.knightsandkings.knk.core.navigation.destinations.NavigationContext;
import net.knightsandkings.knk.core.navigation.destinations.Provider;
import net.knightsandkings.knk.core.navigation.destinations.Reason;
import net.knightsandkings.knk.core.navigation.model.Landmark;
import net.knightsandkings.knk.core.navigation.model.NavigationModel;

/**
 * The real landmark provider (off-road phase 5.1, PandiO/knk-workspace#708; destination engine § 3.2, E16) over the
 * navigation model, in the engine with the initial scope table: what {@code LandmarkLikeProviderTest} pins for a
 * stand-in, now for the model's {@link Landmark}s (A13, A14).
 */
class LandmarkProviderTest {

    private static final String WORLD = "world";

    private final Landmark throne = new Landmark(41, "Throne Room", WORLD, 12, 70, 30, List.of(), 9,
        "Kardenna Keep", true);
    private final Landmark well = new Landmark(42, "Old Well", WORLD, 5, 64, 5, List.of(), null, null,
        true);
    private final Landmark hall = new Landmark(43, "Great Hall", WORLD, 0, 64, 0, List.of("hall_43"), 9,
        "Kardenna Keep", true);
    private final Landmark secret = new Landmark(44, "Secret Door", WORLD, 1, 64, 1, List.of(), null,
        null, false);
    private final Landmark nether = new Landmark(45, "Nether Gate", "world_nether", 0, 64, 0, List.of(), null, null, true);

    private final AtomicReference<NavigationModel> model = new AtomicReference<>(model(throne, well, hall, secret, nether));
    private final LandmarkProvider provider = new LandmarkProvider(model::get);
    private final Provider siegeMatch = Provider.of("siege-match", ctx -> ctx.siege().matchRunning()
        ? List.of(new Candidate("objective:7", "Keep Gate", "objective", List.of(), 7, null, 0, "siege-match",
            new Place.Point(WORLD, 1, 64, 1)))
        : List.of());
    private final DestinationEngine engine = new DestinationEngine(InitialScopes.create(List.of(provider), siegeMatch));
    private final NavigationContext player = NavigationContext.of(java.util.UUID.randomUUID(), WORLD)
        .withPermissions(InitialScopes.NAVIGATE_NODE);

    private static NavigationModel model(Landmark... landmarks) {
        return new NavigationModel(Instant.parse("2026-11-18T12:00:00Z"), List.of(), List.of(), null, List.of(landmarks));
    }

    private static List<String> keys(DestinationList list) {
        return list.candidates().stream().map(Candidate::key).toList();
    }

    @Test
    void enabledLandmarksOfThePlayersWorldAreListedByName() {
        DestinationList list = engine.listFor(player);
        assertEquals(InitialScopes.WORLD, list.scopeId());
        assertEquals(List.of("landmark:43", "landmark:42", "landmark:41"), keys(list), "Secret Door is disabled (A14)");
        Candidate throneRoom = list.find("landmark:41").orElseThrow();
        assertEquals("Throne Room", throneRoom.label());
        assertEquals("landmark", throneRoom.typeWord());
        assertEquals(41, throneRoom.id());
        assertEquals("in Kardenna Keep", throneRoom.detail());
        assertEquals(LandmarkProvider.ID, throneRoom.source());
        assertNull(list.find("landmark:42").orElseThrow().detail());
        assertEquals(List.of("landmark:45"), list.otherWorld().stream().map(Candidate::key).toList(), "E11");
    }

    @Test
    void thePlaceFollowsTheArea() {
        Place.Point point = assertInstanceOf(Place.Point.class, LandmarkProvider.place(throne));
        assertFalse(point.hasArrivalRadius(), "no radius extent any more (LD08): within arrive-distance");
        assertEquals(12, point.x());
        Place.Point none = assertInstanceOf(Place.Point.class, LandmarkProvider.place(well));
        assertFalse(none.hasArrivalRadius(), "within arrive-distance (A13)");
        Place.Region region = assertInstanceOf(Place.Region.class, LandmarkProvider.place(hall));
        assertEquals(("hall_43" + WORLD), region.regionId() + region.world());
        assertTrue(region.isArea(), "a landmark's region is an area");
        assertEquals(List.of(), region.areaRegionIds());
        Landmark brokenRegion = new Landmark(46, "x", WORLD, 0, 0, 0, List.of(" "), null, null, true);
        assertInstanceOf(Place.Point.class, LandmarkProvider.place(brokenRegion), "a Region without a region id: the pin");
    }

    /** A14 (landmark-domain LD22, #985): the area's sub-regions travel with the place; routing goes to the main region. */
    @Test
    void anAreaWithSubRegions_IsItsMainRegion_WithTheOthersAsArrival() {
        Landmark tower = new Landmark(47, "Tower", WORLD, 0, 64, 0, List.of("domain_47", "domain_47_sub_1", "domain_47_sub_2"),
            null, null, true);
        Place.Region region = assertInstanceOf(Place.Region.class, LandmarkProvider.place(tower));
        assertEquals("domain_47", region.regionId());
        assertEquals(List.of("domain_47_sub_1", "domain_47_sub_2"), region.areaRegionIds());
        Landmark linked = new Landmark(48, "Market cross", WORLD, 0, 64, 0, List.of("town_1"), 1, "Rivia", true);
        Place.Region parent = assertInstanceOf(Place.Region.class, LandmarkProvider.place(linked));
        assertEquals("town_1", parent.regionId(), "LinkedDomain: the parent's main region");
    }

    /** A14: one destination per landmark; the catalogue leaves Landmark domains out (LD22). */
    @Test
    void theCatalogueSkipsLandmarkDomains_SoEachLandmarkIsListedOnce() {
        assertNull(CatalogueProvider.typeWordOf("Landmark"));
        assertNull(CatalogueProvider.domain(new net.knightsandkings.knk.core.navigation.model.DomainPlace(43, "Great Hall",
            "Landmark", null, null, "hall_43", WORLD, null, false)));
        assertEquals(1, LandmarkProvider.fromModel(model(hall)).size());
    }

    @Test
    void tabCompletionOffersOnlyLandmarksThePlayerMayNavigateTo() {
        List<String> completions = engine.complete(player, List.of(""));
        assertTrue(completions.contains("Throne"), String.valueOf(completions));
        assertFalse(completions.contains("Secret"), "disabled");
        assertFalse(completions.contains("Nether"), "another world");
        List<String> inMatch = engine.complete(player.withSiege(NavigationContext.Siege.match(1, 1)), List.of(""));
        assertFalse(inMatch.contains("Throne"), "a running match hides them (E2)");
    }

    @Test
    void resolvesByNameAndIdAndTheBypassListsThemInAMatch() {
        assertEquals("landmark:41", engine.resolve(player, "Throne Room", Mode.DEFAULT).destination().candidateKey());
        assertEquals("landmark:43", engine.resolve(player, "landmark:#43", Mode.DEFAULT).destination().candidateKey());
        assertEquals(Outcome.NOT_FOUND, engine.resolve(player, "Secret Door", Mode.DEFAULT).outcome());
        DestinationList staff = engine.listFor(player.withSiege(NavigationContext.Siege.match(1, 1)).withMode(ActiveMode.STAFF));
        assertTrue(staff.contains("landmark:41"));
    }

    @Test
    void aSessionSurvivesARenameAndEndsWhenTheLandmarkIsDeletedOrDisabled() {
        var handle = engine.resolve(player, "Throne Room", Mode.DEFAULT).destination();
        model.set(model(new Landmark(41, "King's Hall", WORLD, 12, 70, 30, List.of(), 9, "Kardenna Keep",
            true), well));
        DestinationEngine.Revalidation renamed = engine.revalidate(player, handle);
        assertTrue(renamed.allowed());
        assertEquals("King's Hall", renamed.candidate().label());

        model.set(model(new Landmark(41, "King's Hall", WORLD, 12, 70, 30, List.of(), 9, null, false)));
        assertEquals(Reason.NO_LONGER_LISTED, engine.revalidate(player, handle).reason().code());
        model.set(model(well));
        assertFalse(engine.revalidate(player, handle).allowed(), "deleted");
    }

    @Test
    void noModelNoLandmarks_andTheListIsBuiltOncePerModel() {
        AtomicReference<NavigationModel> none = new AtomicReference<>();
        assertEquals(List.of(), new LandmarkProvider(none::get).candidates(player));
        List<Candidate> first = provider.candidates(player);
        assertSame(first, provider.candidates(player));
        model.set(model(well));
        assertEquals(List.of("landmark:42"), provider.candidates(player).stream().map(Candidate::key).toList());
    }
}
