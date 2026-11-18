package net.knightsandkings.knk.core.navigation.compass;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompassCycleTest {

    private static final List<String> KEYS = List.of("a", "b", "c");

    @Test
    void startsAtTheTopWithoutACurrentEntry() {
        assertEquals(0, CompassCycle.next(KEYS, null));
    }

    @Test
    void movesOnAndWrapsRound() {
        assertEquals(1, CompassCycle.next(KEYS, "a"));
        assertEquals(2, CompassCycle.next(KEYS, "b"));
        assertEquals(0, CompassCycle.next(KEYS, "c"));
    }

    @Test
    void anEntryNoLongerListedRestartsAtTheTop() {
        assertEquals(0, CompassCycle.next(KEYS, "gone"));
    }

    @Test
    void followsTheCurrentEntryWhenTheListIsReordered() {
        // "c" came under attack and moved to the top; the click after "b" must not skip anything it hasn't shown yet
        assertEquals(2, CompassCycle.next(List.of("c", "a", "b"), "a"));
        assertEquals(0, CompassCycle.next(List.of("c", "a", "b"), "b"));
    }

    @Test
    void nothingToSelectInAnEmptyList() {
        assertEquals(-1, CompassCycle.next(List.of(), "a"));
        assertEquals(-1, CompassCycle.next(null, null));
    }
}
