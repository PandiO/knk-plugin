package net.knightsandkings.knk.paper.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

/** KNG-257: containment against a domain's main region and sub-regions together. */
class DomainRegionUnionTest {

    // A garrison: the house (main region) and the tower east of it, touching along x = 10/11.
    private final ProtectedRegion house = new ProtectedCuboidRegion("domain_11", BlockVector3.at(0, 60, 0), BlockVector3.at(10, 80, 10));
    private final ProtectedRegion tower = new ProtectedCuboidRegion("domain_11_sub_4", BlockVector3.at(11, 60, 3), BlockVector3.at(16, 90, 7));
    private final List<ProtectedRegion> garrison = List.of(house, tower);

    @Test
    void aPointInTheTowerIsInsideTheGarrison() {
        assertTrue(DomainRegionUnion.contains(garrison, BlockVector3.at(14, 85, 5)));
        assertEquals(false, DomainRegionUnion.contains(List.of(house), BlockVector3.at(14, 85, 5)));
    }

    @Test
    void aDoorSpanningHouseAndTowerIsInside() {
        ProtectedRegion door = new ProtectedCuboidRegion("door", BlockVector3.at(9, 62, 4), BlockVector3.at(12, 66, 6));

        assertTrue(DomainRegionUnion.outside(garrison, door, corners(door), 10).isEmpty());
    }

    @Test
    void cornersInsideButAGapBetweenThePartsIsFoundBySampling() {
        // Both ends lie in the garrison (house at z 0..10, tower at z 3..7), the middle at z 8..10 of x 11..16 does not.
        ProtectedRegion wall = new ProtectedCuboidRegion("wall", BlockVector3.at(8, 60, 6), BlockVector3.at(16, 60, 7));
        ProtectedRegion beyond = new ProtectedCuboidRegion("beyond", BlockVector3.at(8, 60, 6), BlockVector3.at(16, 60, 9));

        assertTrue(DomainRegionUnion.outside(garrison, wall, corners(wall), 10).isEmpty());
        List<BlockVector3> outside = DomainRegionUnion.outside(garrison, beyond, List.of(BlockVector3.at(8, 60, 6)), 10);
        assertTrue(!outside.isEmpty() && outside.stream().allMatch(p -> p.x() >= 11 && p.z() >= 8), outside.toString());
    }

    @Test
    void aSingleRegionOnlyChecksTheCorners() {
        ProtectedRegion box = new ProtectedCuboidRegion("box", BlockVector3.at(2, 60, 2), BlockVector3.at(4, 61, 4));

        assertTrue(DomainRegionUnion.outside(List.of(house), box, corners(box), 10).isEmpty());
        assertEquals(1, DomainRegionUnion.outside(List.of(house), box, List.of(BlockVector3.at(20, 60, 20)), 10).size());
    }

    private static List<BlockVector3> corners(ProtectedRegion region) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        return List.of(min, max, BlockVector3.at(min.x(), min.y(), max.z()), BlockVector3.at(max.x(), max.y(), min.z()));
    }
}
