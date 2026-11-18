package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.navigation.DomainLocationResolver.DomainPlace;
import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.Candidate.Place;

/**
 * Turning an engine handle's place into today's {@link Destination} (phase 3, PandiO/knk-workspace#69, A17): for every
 * domain shape and mode the answer is {@link NavigationDestinations#locateDomain}'s, so a route goes where it goes today.
 */
class HandleDestinationsTest {

    private static final String WORLD = "world";

    private static Candidate domain(boolean spawn, boolean region, Candidate.Mode defaultMode) {
        return new Candidate("domain:Town:1", "Kardenna", "town", List.of(), 1, null, 0, "catalogue",
            new Place.Domain(spawn ? new Place.Point(WORLD, 10, 64, 20) : null, region ? "kardenna" : null, defaultMode));
    }

    private static DomainPlace today(boolean spawn, boolean region) {
        return new DomainPlace(1, "Kardenna", "Town", region ? "kardenna" : null,
            spawn ? Optional.of(new KnkLocation(5, "spawn", 10.0, 64.0, 20.0, 0f, 0f, WORLD)) : Optional.empty());
    }

    private static NavigationDestinations.Mode todayMode(Candidate.Mode mode) {
        return NavigationDestinations.Mode.valueOf(mode.name());
    }

    @Test
    void everyDomainShapeAndModeGoesWhereItGoesToday() {
        for (boolean spawn : new boolean[] {true, false}) {
            for (boolean region : new boolean[] {true, false}) {
                for (Candidate.Mode defaultMode : List.of(Candidate.Mode.SPAWN, Candidate.Mode.REGION)) {
                    for (Candidate.Mode mode : Candidate.Mode.values()) {
                        NavigationDestinations.Located expected = NavigationDestinations.locateDomain(today(spawn, region),
                            todayMode(mode), todayMode(defaultMode), WORLD);
                        Destination actual = HandleDestinations.of(domain(spawn, region, defaultMode), mode, WORLD);
                        String which = "spawn=" + spawn + " region=" + region + " default=" + defaultMode + " mode=" + mode;
                        if (expected.ok()) {
                            assertTrue(expected.destination().sameAs(actual), which + ": " + actual + " vs " + expected.destination());
                        } else {
                            assertNull(actual, which);
                        }
                    }
                }
            }
        }
    }

    @Test
    void pointsStreetsNodesAndRegionsAreTodaysDestinations() {
        Destination point = HandleDestinations.of(new Candidate("location:41", "Fountain", "location", List.of(), 41, null, 0,
            "catalogue", new Place.Point(WORLD, 1, 65, 1)), Candidate.Mode.DEFAULT, WORLD);
        assertTrue(Destination.point("Fountain", WORLD, 1, 65, 1).sameAs(point));
        Destination street = HandleDestinations.of(new Candidate("street:5", "Main Street", "street", List.of(), 5, null, 0,
            "catalogue", new Place.Street(WORLD, 5)), Candidate.Mode.DEFAULT, WORLD);
        assertTrue(Destination.street("Main Street", WORLD, 5).sameAs(street));
        double[] floor = {3, 64, 3};
        Destination node = HandleDestinations.of(new Candidate("node:88", "West End", "node", List.of(), 88, null, 0,
            "catalogue", new Place.Node(WORLD, 88, floor)), Candidate.Mode.DEFAULT, WORLD);
        assertTrue(Destination.node("West End", WORLD, 88, floor).sameAs(node));
        Destination region = HandleDestinations.of(new Candidate("landmark:1", "Old Oak", "landmark", List.of(), 1, null, 0,
            "landmarks", new Place.Region(null, "old_oak")), Candidate.Mode.DEFAULT, WORLD);
        assertEquals(Destination.Kind.REGION, region.kind());
        assertEquals(WORLD, region.world());
    }

    @Test
    void aSiegeObjectiveIsAPointOfItsLabel() {
        Destination keep = HandleDestinations.of(new Candidate("objective:7", "Keep Gate", "objective", List.of(), 7, null, 0,
            "siege-match", new Place.Point("siege_world", 100, 64, 100)), Candidate.Mode.DEFAULT, "siege_world");
        assertTrue(Destination.point("Keep Gate", "siege_world", 100, 64, 100).sameAs(keep));
    }
}
