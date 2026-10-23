package net.knightsandkings.knk.paper.navigation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DestinationTest {

    @Test
    void thePointArraysAreComparedByValue() {
        assertTrue(Destination.point("Keep", "w", 1, 64, 2).sameAs(Destination.point("Keep", "W", 1, 64, 2)));
    }

    @Test
    void aDifferentNamePlaceOrKindIsAnotherDestination() {
        Destination keep = Destination.point("Keep", "w", 1, 64, 2);
        assertFalse(keep.sameAs(Destination.point("Gate", "w", 1, 64, 2)));
        assertFalse(keep.sameAs(Destination.point("Keep", "w", 1, 64, 3)));
        assertFalse(keep.sameAs(Destination.point("Keep", "other", 1, 64, 2)));
        assertFalse(keep.sameAs(Destination.region("Keep", "w", "keep_region")));
        assertFalse(keep.sameAs(null));
    }
}
