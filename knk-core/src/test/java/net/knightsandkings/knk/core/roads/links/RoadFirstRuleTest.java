package net.knightsandkings.knk.core.roads.links;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Off-road phase 2 (#705): the developer's road-first wish (#698, 2026-11-18) as a rule for direct mode. */
class RoadFirstRuleTest {

    private final RoadFirstRule rule = RoadFirstRule.defaults();

    @Test
    void aRoadThatCarriesMostOfTheWayAtASimilarLengthWins() {
        // scene 7 shape: a 30-block walk path; the road 26 blocks, 2 to it and 2 from it (straight)
        RoadFirstRule.Decision d = rule.decide(30, 2, 26, 2);
        assertTrue(d.useRoad(), d.describe());
        assertEquals(32, d.mixed(), 1e-9);
        assertEquals(26 / 32.0, d.roadShare(), 1e-9);
    }

    @Test
    void aRoadThatCarriesLittleOfTheWayLoses() {
        // the road would carry 10 of 40 blocks: walk
        assertFalse(rule.decide(30, 10, 10, 10).useRoad());
    }

    @Test
    void aRoadMuchLongerThanTheWalkLoses() {
        // the road carries all of it but is 3 times the walk path (round a block of houses)
        RoadFirstRule.Decision d = rule.decide(20, 0.5, 60, 0.5);
        assertFalse(d.useRoad(), d.describe());
        assertTrue(d.ratio() > 1.5);
    }

    @Test
    void theLimitsAreInclusive() {
        // share exactly 0.6, ratio exactly 1.5
        RoadFirstRule exact = new RoadFirstRule(0.6, 1.5);
        assertTrue(exact.decide(10, 2, 9, 2).useRoad(), exact.decide(10, 2, 9, 2).describe());
    }

    @Test
    void noRoadNeverWins() {
        assertFalse(rule.decide(10, 1, 0, 1).useRoad());
        assertFalse(new RoadFirstRule(0, 100).decide(10, 1, 0, 1).useRoad());
    }

    @Test
    void theKeysAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new RoadFirstRule(1.1, 1.5));
        assertThrows(IllegalArgumentException.class, () -> new RoadFirstRule(0.5, 0.9));
    }
}
