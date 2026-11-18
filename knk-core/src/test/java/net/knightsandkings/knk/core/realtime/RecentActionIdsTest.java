package net.knightsandkings.knk.core.realtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#574: a web kick/ban is carried out once per action id (DESIGN §6.10). */
class RecentActionIdsTest {

    @Test
    void firstTimeOnlyOnce() {
        RecentActionIds ids = new RecentActionIds();
        assertTrue(ids.firstTime("5b0c"));
        assertFalse(ids.firstTime("5b0c"));
        assertFalse(ids.firstTime(" 5b0c "), "surrounding spaces are the same id");
        assertTrue(ids.firstTime("5b0d"));
    }

    @Test
    void nullOrBlank_NeverActedOn() {
        RecentActionIds ids = new RecentActionIds();
        assertFalse(ids.firstTime(null));
        assertFalse(ids.firstTime(""));
        assertFalse(ids.firstTime("  "));
        assertEquals(0, ids.size());
    }

    @Test
    void bounded_OldestForgottenFirst() {
        RecentActionIds ids = new RecentActionIds(2);
        assertTrue(ids.firstTime("a"));
        assertTrue(ids.firstTime("b"));
        assertFalse(ids.firstTime("a"), "a repeat does not refresh its place");
        assertTrue(ids.firstTime("c"));
        assertEquals(2, ids.size());
        assertTrue(ids.firstTime("a"), "a was the oldest and is forgotten");
        assertFalse(ids.firstTime("c"));
    }

    @Test
    void capacityAtLeastOne() {
        assertThrows(IllegalArgumentException.class, () -> new RecentActionIds(0));
    }
}
