package net.knightsandkings.knk.core.navigation.model;

import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.T0;
import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.compass;
import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.model;
import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.place;
import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.point;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;
import org.junit.jupiter.api.Test;

/** Destination engine DESIGN § 7.2 (PandiO/knk-workspace#103): what the model holds and the answers it gives. */
class NavigationModelTest {

    @Test
    void itHoldsDomainPlacesLocationsCompassSettingsAndAnEmptyLandmarksSection() {
        NavigationModel model = model(T0);

        assertEquals(List.of(1, 3, 9), model.domains().stream().map(DomainPlace::id).toList());
        assertEquals(Optional.of(point(7, "Kardenna spawn", 10, "world")), model.domain(1).flatMap(DomainPlace::spawnPoint));
        assertEquals(List.of("Old Well"), model.locations().stream().map(ModelPoint::name).toList());
        assertEquals(compass(), model.compass());
        assertTrue(model.landmarks().isEmpty(), "E16: the section exists and is empty until #708");
    }

    // ==================== road-access answers (E19): the same as NavigationDestinations' ====================

    @Test
    void onlyAnExplicitIgnoredLiftsTheRuleOffTheRoads() {
        NavigationModel model = new NavigationModel(T0, List.of(
            place(1, "Kardenna", "Town", "Applies", "town_1", null),
            place(2, "Brink", "Town", null, "town_2", null),
            place(9, "Mill House", "Structure", "ignored", "Mill_House", null),
            place(10, "Wild", "Kingdom", "Ignored", null, null)), List.of(), null, null);

        assertFalse(model.roadAccessIgnored(1));
        assertFalse(model.roadAccessIgnored(2), "unknown keeps the rule");
        assertTrue(model.roadAccessIgnored(9), "any case");
        assertTrue(model.roadAccessIgnored(10));
        assertFalse(model.roadAccessIgnored(404), "an unknown domain keeps the rule");
    }

    @Test
    void regionsOfIgnoredDomainsAreAnsweredInAnyCase() {
        NavigationModel model = model(T0);

        assertTrue(model.roadsIgnoreRegion("mill_house"));
        assertTrue(model.roadsIgnoreRegion("MILL_HOUSE"));
        assertFalse(model.roadsIgnoreRegion("town_1"), "Applies");
        assertFalse(model.roadsIgnoreRegion("nowhere"));
        assertFalse(model.roadsIgnoreRegion(null));
        assertEquals(java.util.Set.of("mill_house"), model.roadAccessIgnoredRegions());
    }

    // ==================== content and places ====================

    @Test
    void theSameContentAtAnotherTimeIsTheSameContentButNotEqual() {
        NavigationModel a = model(T0);
        NavigationModel b = model(T0.plusSeconds(60));

        assertTrue(a.sameContent(b));
        assertNotEquals(a, b);
        assertEquals(a, model(T0));
        assertFalse(a.sameContent(a.withPlace(3, point(8, "Market spawn", 5, "world"))));
    }

    @Test
    void duplicateDomainsKeepTheFirstAndNamelessLocationsAreDropped() {
        NavigationModel model = new NavigationModel(T0,
            List.of(place(1, "Kardenna", "Town", "Applies", "t", null), place(1, "Copy", "Town", "Applies", "t", null)),
            List.of(point(41, "Old Well", 1, "world"), new ModelPoint(42, " ", 1, 2, 3, 0, 0, "world"),
                new ModelPoint(43, null, 1, 2, 3, 0, 0, "world")),
            null, null);

        assertEquals(List.of("Kardenna"), model.domains().stream().map(DomainPlace::name).toList());
        assertEquals(List.of(41), model.locations().stream().map(ModelPoint::id).toList());
        assertEquals(CompassSettings.defaults(), model.compass(), "no settings: the defaults, as CompassSettingsStore");
    }

    @Test
    void unknownPlacesAreFilledOneByOneAndCarriedOverByARefresh() {
        DomainPlace unknown = new DomainPlace(3, "Market", "District", "Spawn", "Applies", "district_3", "world", null, false);
        NavigationModel model = new NavigationModel(T0, List.of(unknown), List.of(), null, null);
        assertEquals(List.of(unknown), model.unknownPlaces());

        NavigationModel filled = model.withPlace(3, point(8, "Market spawn", 5, "world"));
        assertTrue(filled.unknownPlaces().isEmpty());
        assertSame(model, model.withPlace(404, null), "an unknown id changes nothing");

        NavigationModel refreshed = new NavigationModel(T0.plusSeconds(60), List.of(unknown), List.of(), null, null)
            .withKnownPlacesOf(filled);
        assertEquals(Optional.of(point(8, "Market spawn", 5, "world")), refreshed.domain(3).flatMap(DomainPlace::spawnPoint));

        NavigationModel none = model.withPlace(3, null);
        assertTrue(none.domain(3).orElseThrow().placeKnown(), "looked up: it has no Location");
        assertNull(none.domain(3).orElseThrow().spawn());
    }

    @Test
    void aKnownPlaceIsNeverOverwrittenByAnEarlierModel() {
        NavigationModel now = model(T0);
        NavigationModel earlier = now.withPlace(1, point(99, "Old spawn", 1, "world"));

        assertSame(now, now.withKnownPlacesOf(earlier), "nothing unknown: kept as it is");
    }

    @Test
    void aPointNeedsAWorldAndCoordinates() {
        assertEquals(Optional.empty(), ModelPoint.of(null));
        assertEquals(Optional.empty(), ModelPoint.of(new KnkLocation(1, "a", 1.0, 2.0, 3.0, 0f, 0f, null)));
        assertEquals(Optional.empty(), ModelPoint.of(new KnkLocation(1, "a", null, 2.0, 3.0, 0f, 0f, "world")));
        assertEquals(Optional.empty(), ModelPoint.of(new KnkLocation(1, "a", Double.NaN, 2.0, 3.0, 0f, 0f, "world")));
        assertEquals(new ModelPoint(1, "a", 1, 2, 3, 0f, 0f, "world"),
            ModelPoint.of(new KnkLocation(1, "a", 1.0, 2.0, 3.0, null, null, "world")).orElseThrow());
        assertEquals(new KnkLocation(1, "a", 1.0, 2.0, 3.0, 0f, 0f, "world"),
            new ModelPoint(1, "a", 1, 2, 3, 0f, 0f, "world").toKnkLocation());
        assertThrows(IllegalArgumentException.class, () -> new DomainPlace(1, "a", "Town", null, null, null, null,
            point(1, "x", 1, "world"), false));
    }
}
