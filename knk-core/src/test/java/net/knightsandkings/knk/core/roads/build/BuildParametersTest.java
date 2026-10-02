package net.knightsandkings.knk.core.roads.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BuildParametersTest {

    @Test
    void defaultsAreTheDesignValues() {
        BuildParameters p = BuildParameters.defaults();
        assertEquals(512, p.tileSize());
        assertEquals(32, p.tileMargin());
        assertEquals(250_000, p.maxCellsPerTile());
        assertEquals(3, p.junctionClusterRadius());
        assertEquals(4, p.minSpurLength());
        assertEquals(3, p.ambiguousReach());
        assertEquals(0.75, p.rdpEpsilon());
        assertEquals(BuildParameters.DEFAULT_PLAZA_GROWTH, p.plazaGrowth());
        assertEquals(BuildParameters.DEFAULT_LOCKED_NODE_REACH, p.lockedNodeReach());
        assertEquals(8, p.seedSnapRadius());
        assertEquals(3.0, p.nodeMatchDistance());
        assertEquals(2.0, p.edgeMatchDistance());
    }

    @Test
    void withersChangeOnlyTheirFields() {
        BuildParameters p = BuildParameters.defaults().withTile(32, 4).withMaxCells(100).withAmbiguousReach(1)
            .withGraphRules(2, 3);
        assertEquals(32, p.tileSize());
        assertEquals(4, p.tileMargin());
        assertEquals(100, p.maxCellsPerTile());
        assertEquals(1, p.ambiguousReach());
        assertEquals(2, p.junctionClusterRadius());
        assertEquals(3, p.minSpurLength());
        assertEquals(0.75, p.rdpEpsilon());
    }

    @Test
    void invalidValuesAreRejected() {
        BuildParameters d = BuildParameters.defaults();
        assertThrows(IllegalArgumentException.class, () -> d.withTile(0, 0));
        assertThrows(IllegalArgumentException.class, () -> d.withTile(16, -1));
        assertThrows(IllegalArgumentException.class, () -> d.withMaxCells(0));
        assertThrows(IllegalArgumentException.class, () -> d.withAmbiguousReach(-1));
        assertThrows(IllegalArgumentException.class, () -> new BuildParameters(16, 0, 10, 1, 1, 1, -0.1, 1, 1, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> d.withLockedNodeReach(-1));
        assertEquals(2.5, d.withLockedNodeReach(2.5).lockedNodeReach());
        assertThrows(IllegalArgumentException.class, () -> d.withPlazaGrowth(-1));
        assertEquals(5, d.withPlazaGrowth(5).plazaGrowth());
    }
}
