package net.knightsandkings.knk.paper.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector2;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.CylinderRegion;
import com.sk89q.worldedit.regions.EllipsoidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

/**
 * KNG-257: a //sel cyl tower becomes a WorldGuard polygon holding exactly the cylinder's columns (and its height), not
 * the box around it.
 */
class SelectionShapesTest {

    private static final BlockVector3 CENTER = BlockVector3.at(100, 64, -50);

    @Test
    void aCylinderBecomesAPolygonCoveringItsColumnsAndNotTheBoxCorners() {
        CylinderRegion tower = new CylinderRegion(CENTER, Vector2.at(5, 5), 60, 90);

        ProtectedRegion region = WorldGuardIntegration.createRegionFromSelection(tower, "tower", 30);

        ProtectedPolygonalRegion polygon = assertInstanceOf(ProtectedPolygonalRegion.class, region);
        assertEquals(30, polygon.getPriority());
        assertEquals(60, polygon.getMinimumPoint().y());
        assertEquals(90, polygon.getMaximumPoint().y());
        BlockVector3 min = tower.getMinimumPoint();
        BlockVector3 max = tower.getMaximumPoint();
        for (int x = min.x(); x <= max.x(); x++) {
            for (int z = min.z(); z <= max.z(); z++) {
                if (tower.contains(BlockVector3.at(x, 70, z))) {
                    assertTrue(polygon.contains(BlockVector3.at(x, 70, z)), "column " + x + "," + z + " of the cylinder");
                }
            }
        }
        assertFalse(polygon.contains(BlockVector3.at(min.x(), 70, min.z())), "the box corner is not the tower's");
        assertFalse(polygon.contains(BlockVector3.at(100, 91, -50)), "above the tower");
    }

    @Test
    void theOutlineSaysWhatTheSelectionBecame() {
        var outline = SelectionShapes.outlineOf(new CylinderRegion(CENTER, Vector2.at(3, 3), 64, 70)).orElseThrow();

        assertTrue(outline.note().startsWith("The cylinder is stored as a polygon with "), outline.note());
        assertEquals(outline.points().size() + "", outline.note().replaceAll("\\D+", " ").trim().split(" ")[0]);
    }

    @Test
    void anEllipsoidUsesItsWidestSliceOverItsFullHeight() {
        EllipsoidRegion dome = new EllipsoidRegion(CENTER, Vector3.at(6, 4, 6));

        var outline = SelectionShapes.outlineOf(dome).orElseThrow();
        ProtectedRegion region = WorldGuardIntegration.createRegionFromSelection(dome, "dome", 0);

        assertEquals(dome.getMinimumPoint().y(), outline.minY());
        assertEquals(dome.getMaximumPoint().y(), outline.maxY());
        assertTrue(outline.note().contains("top and bottom"));
        assertTrue(region.contains(BlockVector3.at(106, dome.getMinimumPoint().y(), -50)), "widest slice at the bottom");
    }

    @Test
    void aCuboidStaysACuboid() {
        CuboidRegion house = new CuboidRegion(BlockVector3.at(0, 60, 0), BlockVector3.at(10, 70, 8));

        assertTrue(SelectionShapes.outlineOf(house).isEmpty());
        assertInstanceOf(ProtectedCuboidRegion.class, WorldGuardIntegration.createRegionFromSelection(house, "house", 0));
    }
}
