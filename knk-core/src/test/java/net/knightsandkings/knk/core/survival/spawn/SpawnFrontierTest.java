package net.knightsandkings.knk.core.survival.spawn;

import net.knightsandkings.knk.core.survival.SurvivalPoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN §6, S10, S22, S50, S63: ownership-driven activation, ALL/ANY, recapture. */
class SpawnFrontierTest {
    private static final SurvivalPoint O = new SurvivalPoint(0, 64, 0);
    private final SpawnZone outside = SpawnZone.initial("outside", 1, O);
    private final SpawnZone courtyard = SpawnZone.unlockedBy("courtyard", 1, O, PrerequisitePolicy.ALL, "outer");
    private final SpawnZone keepYard = SpawnZone.unlockedBy("keep-yard", 1, O, PrerequisitePolicy.ALL, "outer", "inner");
    private final SpawnZone flank = SpawnZone.unlockedBy("flank", 1, O, PrerequisitePolicy.ANY, "east", "west");
    private final List<SpawnZone> zones = List.of(outside, courtyard, keepYard, flank);

    @Test
    void initialZonesAreActiveAndBreachAloneActivatesNothing() {
        SpawnFrontier frontier = new SpawnFrontier(zones, false);
        assertEquals(Set.of("outside"), frontier.activeZoneIds());
        // A breached or open door is not an input: only ownership moves the frontier.
        assertTrue(frontier.update(Set.of()).isEmpty());
        assertEquals(Set.of("outside"), frontier.activeZoneIds());
    }

    @Test
    void allAndAnyPrerequisites() {
        SpawnFrontier frontier = new SpawnFrontier(zones, false);
        SpawnFrontier.Change change = frontier.update(Set.of("outer"));
        assertEquals(Set.of("courtyard"), change.activated());
        assertTrue(frontier.isActive("outside"), "earlier zones keep spawning");
        assertFalse(frontier.isActive("keep-yard"), "ALL needs inner too");
        frontier.update(Set.of("outer", "inner"));
        assertTrue(frontier.isActive("keep-yard"));
        assertEquals(Set.of("flank"), frontier.update(Set.of("outer", "inner", "west")).activated());
        assertEquals(4, frontier.activeZones().size());
    }

    @Test
    void withoutRecaptureActivationIsLatched() {
        SpawnFrontier frontier = new SpawnFrontier(zones, false);
        frontier.update(Set.of("outer"));
        assertTrue(frontier.update(Set.of()).isEmpty());
        assertTrue(frontier.isActive("courtyard"));
    }

    @Test
    void withRecaptureIneligibleZonesStopFutureSpawnsAndInitialZonesStay() {
        SpawnFrontier frontier = new SpawnFrontier(zones, true);
        frontier.update(Set.of("outer", "inner"));
        SpawnFrontier.Change change = frontier.update(Set.of("inner"));
        assertEquals(Set.of("courtyard", "keep-yard"), change.deactivated());
        assertTrue(change.activated().isEmpty());
        assertEquals(Set.of("outside"), frontier.activeZoneIds());
        assertEquals(Set.of("courtyard", "keep-yard"), frontier.update(Set.of("outer", "inner")).activated());
    }

    @Test
    void readinessNeedsAnInitialZoneAndKnownObjectives() {
        List<String> codes = SpawnZoneSelector.readiness(List.of(courtyard, courtyard), Set.of("inner")).stream()
            .map(i -> i.code()).toList();
        assertTrue(codes.contains("spawn.no-initial-zone"));
        assertTrue(codes.contains("spawn.unknown-objective"));
        assertTrue(codes.contains("spawn.duplicate-zone"));
        assertTrue(SpawnZoneSelector.readiness(zones, Set.of("outer", "inner", "east", "west")).isEmpty());
    }
}
