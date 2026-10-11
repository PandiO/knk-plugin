package net.knightsandkings.knk.core.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch.MovementCell;

/** KNG-34 link 7: heatmap cells (floor division, per-world counts, drain clears). */
class MovementCellGridTest {

    @Test
    void samples_areCountedPerCell_withFloorDivisionForNegativeCoordinates() {
        MovementCellGrid grid = new MovementCellGrid(16);
        grid.sample("world", 0, 0);
        grid.sample("world", 15, 15);
        grid.sample("world", 16, 0);
        grid.sample("world", -1, -1);
        grid.sample("world", -16, -17);
        grid.sample("world_nether", 0, 0);

        List<MovementCell> cells = grid.drain().stream()
            .sorted(Comparator.comparing(MovementCell::world).thenComparingInt(MovementCell::cellX).thenComparingInt(MovementCell::cellZ))
            .toList();

        assertEquals(List.of(
            new MovementCell("world", 16, -1, -2, 1),
            new MovementCell("world", 16, -1, -1, 1),
            new MovementCell("world", 16, 0, 0, 2),
            new MovementCell("world", 16, 1, 0, 1),
            new MovementCell("world_nether", 16, 0, 0, 1)), cells);
    }

    @Test
    void drain_clearsTheGrid_andBlankWorldsAreIgnored() {
        MovementCellGrid grid = new MovementCellGrid(8);
        grid.sample("", 1, 1);
        grid.sample(null, 1, 1);
        grid.sample("world", 1, 1);
        assertEquals(1, grid.size());

        assertEquals(1, grid.drain().size());
        assertTrue(grid.isEmpty());
        assertTrue(grid.drain().isEmpty());
    }

    @Test
    void packedKeys_roundTripExtremes() {
        for (int[] xz : new int[][] {{0, 0}, {-1, -1}, {Integer.MAX_VALUE, Integer.MIN_VALUE}, {Integer.MIN_VALUE, 7}}) {
            long key = MovementCellGrid.pack(xz[0], xz[1]);
            assertEquals(xz[0], MovementCellGrid.unpackX(key));
            assertEquals(xz[1], MovementCellGrid.unpackZ(key));
        }
    }

    @Test
    void cellSize_mustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new MovementCellGrid(0));
    }
}
