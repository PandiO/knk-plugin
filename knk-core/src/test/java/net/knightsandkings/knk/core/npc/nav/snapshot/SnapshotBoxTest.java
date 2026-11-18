package net.knightsandkings.knk.core.npc.nav.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;

/** The box of a snapshot (link B1, PandiO/knk-workspace#670) and the region files it touches. */
class SnapshotBoxTest {

    @Test
    void theBoxIsTheLineWithItsMargins() {
        SnapshotBox box = SnapshotBox.around(List.of(new NavPoint("world", 1430.5, 48, -520.5),
            new NavPoint("world", 1414.5, 75, -544.5)));
        assertArrayEquals(new int[] {1414 - 8, 48 - 6, -545 - 8}, box.min());
        assertArrayEquals(new int[] {1430 + 8, 75 + 12, -521 + 8}, box.max());
        assertTrue(box.contains(1420, 60, -530));
        assertFalse(box.contains(1420, 100, -530));
        assertTrue(box.containsColumn(1438, -513));
        assertFalse(box.containsColumn(1439, -513));
    }

    @Test
    void theStairHouseRouteTouchesTheTwoRegionFilesOfDecision33() {
        // the developer's route through the stair house: r.2.-1.mca and r.2.-2.mca (chain report decision 33)
        SnapshotBox box = new SnapshotBox(new int[] {1100, 40, -600}, new int[] {1440, 90, -480});
        assertEquals(List.of("r.2.-2.mca", "r.2.-1.mca"), box.regionFiles());
        SnapshotBox negative = new SnapshotBox(new int[] {-513, 0, -1}, new int[] {-1, 10, 0});
        assertEquals(List.of("r.-2.-1.mca", "r.-1.-1.mca", "r.-2.0.mca", "r.-1.0.mca"), negative.regionFiles());
    }

    @Test
    void touchesIsAnyOverlap() {
        SnapshotBox box = new SnapshotBox(new int[] {0, 0, 0}, new int[] {10, 10, 10});
        assertTrue(box.touches(new int[] {10, 10, 10}, new int[] {20, 20, 20}));
        assertTrue(box.touches(new int[] {-100, -100, -100}, new int[] {100, 100, 100}));
        assertFalse(box.touches(new int[] {11, 0, 0}, new int[] {20, 10, 10}));
    }

    @Test
    void refusesAnEmptyLineAndAnUpsideDownBox() {
        assertThrows(IllegalArgumentException.class, () -> SnapshotBox.around(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotBox(new int[] {1, 0, 0}, new int[] {0, 0, 0}));
    }

    @Test
    void aRouteBoxCoversItsFromAndToAsWell() {
        // link C1 (PandiO/knk-workspace#700): the tower route's road ends at 1409 84 -516, its goal is 1401 113 -502
        List<NavPoint> line = List.of(new NavPoint("world", 1309, 50, -621), new NavPoint("world", 1409, 84, -516));
        SnapshotBox box = SnapshotBox.aroundRoute(line, new NavPoint("world", 1279.5, 64, -620.5),
            new NavPoint("world", 1401.5, 113, -502.5));
        assertTrue(box.contains(1401, 113, -502), "the goal upstairs");
        assertTrue(box.contains(1401, 113 + SnapshotBox.MARGIN_ABOVE, -502), "and the margin above it");
        assertTrue(box.contains(1279 - SnapshotBox.MARGIN, 64, -620), "the start off the road, with its margin");
        assertArrayEquals(SnapshotBox.around(line).min(), SnapshotBox.aroundRoute(line, null, null).min());
        assertArrayEquals(SnapshotBox.around(line).max(), SnapshotBox.aroundRoute(line, null, null).max());
        assertTrue(SnapshotBox.aroundRoute(List.of(), null, new NavPoint("world", 0, 64, 0)).contains(0, 64, 0));
    }
}
