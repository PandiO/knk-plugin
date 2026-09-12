package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for GatePassThroughService's pure geometry helpers (isBlockInPassThroughPath,
 * computeTeleportDestination) - the InstantOpen block-selection and Teleport destination math
 * that underpins the pass-through modes described in REQUIREMENTS_GATE_ADVANCED_FEATURES.md
 * Feature 1. Kept independent of Bukkit Player/World/scheduler so they run without a live server,
 * matching GateFrameCalculatorTest's approach for the same kind of basis-vector math.
 */
class GatePassThroughServiceTest {
    private CachedGateDoor axisAlignedGate;

    @BeforeEach
    void setUp() {
        axisAlignedGate = new CachedGateDoor(
            1, 1, "TestGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1,
            new Vector(100, 64, 100),
            5, 5, 1,
            500.0, 500.0, true, false, true, 90,
            "north"
        );
        axisAlignedGate.setUAxis(new Vector(1, 0, 0));
        axisAlignedGate.setVAxis(new Vector(0, 1, 0));
        axisAlignedGate.setNAxis(new Vector(0, 0, 1));
    }

    @Test
    void blockDirectlyAtPlayerPositionIsInPath() {
        Vector playerPosition = new Vector(100, 64, 100);
        Vector blockClosedWorldPos = new Vector(100, 64, 100);

        assertTrue(GatePassThroughService.isBlockInPassThroughPath(axisAlignedGate, blockClosedWorldPos, playerPosition, 1));
    }

    @Test
    void blockOutsideHorizontalRadiusIsNotInPath() {
        Vector playerPosition = new Vector(100, 64, 100);
        // 3 blocks sideways (u-axis) - beyond radius 1 (tolerance 1.5)
        Vector blockClosedWorldPos = new Vector(103, 64, 100);

        assertFalse(GatePassThroughService.isBlockInPassThroughPath(axisAlignedGate, blockClosedWorldPos, playerPosition, 1));
    }

    @Test
    void blockWithinVerticalToleranceAboveHeadIsInPath() {
        Vector playerPosition = new Vector(100, 64, 100);
        // 1 block up (v-axis) - within the fixed 1.5 vertical tolerance (covers player height)
        Vector blockClosedWorldPos = new Vector(100, 65, 100);

        assertTrue(GatePassThroughService.isBlockInPassThroughPath(axisAlignedGate, blockClosedWorldPos, playerPosition, 1));
    }

    @Test
    void horizontalFacingDirectionMatchesBukkitYawConvention() {
        // Bukkit yaw: 0 = south (+Z), 90 = west (-X), 180 = north (-Z), 270 = east (+X).
        assertVectorEquals(new Vector(0, 0, 1), GatePassThroughService.horizontalFacingDirection(0f));
        assertVectorEquals(new Vector(-1, 0, 0), GatePassThroughService.horizontalFacingDirection(90f));
        assertVectorEquals(new Vector(0, 0, -1), GatePassThroughService.horizontalFacingDirection(180f));
        assertVectorEquals(new Vector(1, 0, 0), GatePassThroughService.horizontalFacingDirection(270f));
    }

    private static void assertVectorEquals(Vector expected, Vector actual) {
        assertEquals(expected.getX(), actual.getX(), 1e-9);
        assertEquals(expected.getY(), actual.getY(), 1e-9);
        assertEquals(expected.getZ(), actual.getZ(), 1e-9);
    }

    @Test
    void blockOnlyAheadOfPlayerIsInPathWhenCheckedAgainstForwardProjectedPoint() {
        // Player standing 2 blocks off to the side of the doorway (u=102) but facing straight
        // toward it (west, i.e. -X, since the gate's u-axis here is world +X) - the door block at
        // u=100 isn't in path against their exact standing position, but is against a point
        // projected 1 block ahead along their facing direction (toward decreasing u).
        Vector playerPosition = new Vector(102, 64, 100);
        Vector doorBlock = new Vector(100, 64, 100);

        assertFalse(GatePassThroughService.isBlockInPassThroughPath(axisAlignedGate, doorBlock, playerPosition, 1));

        Vector forwardPosition = playerPosition.clone().add(GatePassThroughService.horizontalFacingDirection(90f));
        assertTrue(GatePassThroughService.isBlockInPassThroughPath(axisAlignedGate, doorBlock, forwardPosition, 1));
    }

    @Test
    void blockFarAboveVerticalToleranceIsNotInPath() {
        Vector playerPosition = new Vector(100, 64, 100);
        Vector blockClosedWorldPos = new Vector(100, 68, 100);

        assertFalse(GatePassThroughService.isBlockInPassThroughPath(axisAlignedGate, blockClosedWorldPos, playerPosition, 1));
    }

    @Test
    void isBlockInPassThroughPathReturnsFalseWhenBasisVectorsMissing() {
        CachedGateDoor noBasisGate = new CachedGateDoor(
            2, 2, "NoBasisGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 64, 0), 5, 5, 1,
            500.0, 500.0, true, false, true, 90, "north"
        );

        assertFalse(GatePassThroughService.isBlockInPassThroughPath(
            noBasisGate, new Vector(0, 64, 0), new Vector(0, 64, 0), 1));
    }

    @Test
    void blockInPassThroughPathHandlesDiagonalBasisVectors() {
        // Regression guard: isBlockInPassThroughPath projects onto the unit uAxis/vAxis, never
        // the new lattice uStep/vStep - deliberately left unset here to prove it isn't needed.
        CachedGateDoor diagonalGate = new CachedGateDoor(
            5, 5, "DiagonalPathGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 64, 0), 5, 5, 1,
            500.0, 500.0, true, false, true, 90, "north"
        );
        double invSqrt2 = 1.0 / Math.sqrt(2.0);
        diagonalGate.setUAxis(new Vector(invSqrt2, 0, invSqrt2));
        diagonalGate.setVAxis(new Vector(0, 1, 0));

        Vector playerPosition = new Vector(0, 64, 0);
        // 1 block along the diagonal u-axis - within radius 1 (tolerance 1.5).
        Vector nearBlock = new Vector(invSqrt2, 64, invSqrt2);
        assertTrue(GatePassThroughService.isBlockInPassThroughPath(diagonalGate, nearBlock, playerPosition, 1));

        // 3 blocks along the diagonal u-axis - beyond radius 1.
        Vector farBlock = new Vector(3 * invSqrt2, 64, 3 * invSqrt2);
        assertFalse(GatePassThroughService.isBlockInPassThroughPath(diagonalGate, farBlock, playerPosition, 1));
    }

    @Test
    void teleportDestinationCrossesToTheFarSideAlongNAxis() {
        // Player standing at the anchor (offsetN = 0, treated as the "positive" side) with depth 1
        // should land 2 blocks in the -n direction (depth 1 + 1 block clearance).
        Vector playerPosition = new Vector(100, 64, 100);

        Vector destination = GatePassThroughService.computeTeleportDestination(axisAlignedGate, playerPosition);

        assertEquals(new Vector(100, 64, 98), destination);
    }

    @Test
    void teleportDestinationPreservesPlayerSidewaysAndVerticalOffset() {
        // 2 blocks right (u) and 1 block up (v) from the anchor, standing behind the gate (-n side)
        Vector playerPosition = new Vector(102, 65, 99);

        Vector destination = GatePassThroughService.computeTeleportDestination(axisAlignedGate, playerPosition);

        // Same u/v offset preserved (102, 65), stepped to the +n side by depth(1) + 1 = 2 blocks
        assertEquals(new Vector(102, 65, 102), destination);
    }

    @Test
    void teleportDestinationReturnsNullWhenBasisVectorsMissing() {
        CachedGateDoor noBasisGate = new CachedGateDoor(
            3, 3, "NoBasisGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 64, 0), 5, 5, 1,
            500.0, 500.0, true, false, true, 90, "north"
        );

        assertNull(GatePassThroughService.computeTeleportDestination(noBasisGate, new Vector(0, 64, 0)));
    }

    @Test
    void teleportDestinationHandlesDiagonalBasisVectors() {
        CachedGateDoor diagonalGate = new CachedGateDoor(
            4, 4, "DiagonalGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(0, 64, 0), 5, 5, 2,
            500.0, 500.0, true, false, true, 90, "north"
        );
        // 45-degree diagonal door plane in the XZ plane, height straight up
        double invSqrt2 = 1.0 / Math.sqrt(2.0);
        diagonalGate.setUAxis(new Vector(invSqrt2, 0, invSqrt2));
        diagonalGate.setVAxis(new Vector(0, 1, 0));
        diagonalGate.setNAxis(new Vector(invSqrt2, 0, -invSqrt2));

        // Player standing exactly at the anchor (all offsets zero) - depth 2 + 1 clearance = 3
        // blocks along -nAxis from the anchor (offsetN = 0 treated as the positive side).
        Vector destination = GatePassThroughService.computeTeleportDestination(diagonalGate, new Vector(0, 64, 0));

        assertEquals(-3 * invSqrt2, destination.getX(), 1e-9);
        assertEquals(64.0, destination.getY(), 1e-9);
        assertEquals(3 * invSqrt2, destination.getZ(), 1e-9);
    }

    /**
     * Mocks a single (x, z) column so getBlockAt(x, y, z) returns a Material looked up per y,
     * defaulting to STONE for any y not explicitly listed (simulating solid ground/terrain).
     */
    private World mockColumn(int x, int z, Map<Integer, Material> yToMaterial) {
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getBlockAt(eq(x), anyInt(), eq(z))).thenAnswer(invocation -> {
            int y = invocation.getArgument(1);
            Material material = yToMaterial.getOrDefault(y, Material.STONE);
            Block block = mock(Block.class);
            when(block.getType()).thenReturn(material);
            return block;
        });
        return world;
    }

    // Material.isSolid() needs a live Paper block registry (RegistryAccess), unavailable in a
    // plain unit test - matches this project's existing requires-bukkit convention for tests
    // that touch such calls (see build.gradle.kts excludeTags).
    @Test
    @Tag("requires-bukkit")
    void findStandableYReturnsStartYWhenAlreadyOnSolidGroundWithAirAbove() {
        Map<Integer, Material> column = new HashMap<>();
        column.put(64, Material.AIR);
        column.put(65, Material.AIR);
        column.put(63, Material.STONE);
        World world = mockColumn(0, 0, column);

        Integer safeY = GatePassThroughService.findStandableY(world, 0, 64, 0);

        assertEquals(64, safeY);
    }

    @Test
    @Tag("requires-bukkit")
    void findStandableYSearchesUpwardWhenStartingInsideSolidGround() {
        // Reproduces the reported bug: approaching from one block below the gate lands the raw
        // destination inside solid ground (y=64 solid) on the far side; open air with solid
        // footing exists two blocks higher (y=66/67 air, y=65 solid).
        Map<Integer, Material> column = new HashMap<>();
        column.put(65, Material.STONE);
        column.put(66, Material.AIR);
        column.put(67, Material.AIR);
        World world = mockColumn(10, 20, column);
        // Everything else in the column defaults to STONE (solid), including the start y=64.

        Integer safeY = GatePassThroughService.findStandableY(world, 10, 64, 20);

        assertEquals(66, safeY);
    }

    @Test
    @Tag("requires-bukkit")
    void findStandableYReturnsNullWhenNoStandableSpotWithinRadius() {
        // Entirely solid column (default STONE) - no air pocket anywhere nearby.
        World world = mockColumn(0, 0, Map.of());

        Integer safeY = GatePassThroughService.findStandableY(world, 0, 64, 0);

        assertNull(safeY);
    }

    @Test
    @Tag("requires-bukkit")
    void findNonSuffocatingYNudgesUpwardUntilFeetAndHeadArePassable() {
        Map<Integer, Material> column = new HashMap<>();
        column.put(70, Material.AIR);
        column.put(71, Material.AIR);
        World world = mockColumn(0, 0, column);
        // y=64..69 default to solid STONE, so it must climb to 70 before both feet/head are clear.

        int safeY = GatePassThroughService.findNonSuffocatingY(world, 0, 64, 0);

        assertEquals(70, safeY);
    }

    @Test
    @Tag("requires-bukkit")
    void findSafeTeleportLocationPreservesXZAndOrientationWhileFixingY() {
        Map<Integer, Material> column = new HashMap<>();
        column.put(66, Material.AIR);
        column.put(67, Material.AIR);
        column.put(65, Material.STONE);
        World world = mockColumn(5, 9, column);

        Vector rawDestination = new Vector(5.3, 64, 9.7);
        Location safeLocation = GatePassThroughService.findSafeTeleportLocation(world, rawDestination, 45f, 10f);

        assertEquals(5.3, safeLocation.getX(), 1e-9);
        assertEquals(66.0, safeLocation.getY(), 1e-9);
        assertEquals(9.7, safeLocation.getZ(), 1e-9);
        assertEquals(45f, safeLocation.getYaw());
        assertEquals(10f, safeLocation.getPitch());
    }
}
