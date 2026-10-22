package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Fire;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * GateFireVisuals: the burn of a door block is drawn as regular fire on the side the attacker is on.
 */
class GateFireVisualsTest {

    private static final Predicate<BlockFace> ALL_FREE = face -> true;

    private GateFireVisuals visuals;
    private CachedGateDoor gate;
    private World world;
    private Player viewer;
    private Fire fire;
    private MockedStatic<Bukkit> bukkitMock;

    @BeforeEach
    void setUp() {
        visuals = new GateFireVisuals();
        gate = new CachedGateDoor(
            1, 1, "TestGate", "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(100, 64, 100), 5, 5, 3,
            500.0, 500.0, true, false, false, 90, "north"
        );
        gate.setWorldName("world");

        world = mock(World.class);
        viewer = mock(Player.class);
        when(world.getNearbyPlayers(any(Location.class), anyDouble())).thenReturn(List.of(viewer));
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);

        fire = mock(Fire.class);
        when(fire.getAllowedFaces()).thenReturn(EnumSet.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP));
        bukkitMock = mockStatic(Bukkit.class);
        bukkitMock.when(() -> Bukkit.getWorld(anyString())).thenReturn(world);
        bukkitMock.when(() -> Bukkit.createBlockData(Material.FIRE)).thenReturn(fire);
    }

    @AfterEach
    void tearDown() {
        bukkitMock.close();
    }

    /** A door block at (100,64,100) whose neighbours are all air unless listed in {@code solid}. */
    private Block doorBlock(BlockFace... solid) {
        Block door = mock(Block.class);
        when(door.getX()).thenReturn(100);
        when(door.getY()).thenReturn(64);
        when(door.getZ()).thenReturn(100);
        when(door.getWorld()).thenReturn(world);
        Set<BlockFace> solidFaces = EnumSet.noneOf(BlockFace.class);
        solidFaces.addAll(List.of(solid));
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)) {
            Block neighbor = mock(Block.class);
            when(neighbor.getX()).thenReturn(100 + face.getModX());
            when(neighbor.getY()).thenReturn(64 + face.getModY());
            when(neighbor.getZ()).thenReturn(100 + face.getModZ());
            when(neighbor.isEmpty()).thenReturn(!solidFaces.contains(face));
            when(door.getRelative(face)).thenReturn(neighbor);
        }
        return door;
    }

    private Player attackerAt(double x, double y, double z) {
        Player attacker = mock(Player.class);
        when(attacker.getWorld()).thenReturn(world);
        when(attacker.getLocation()).thenReturn(new Location(world, x, y, z));
        return attacker;
    }

    // ===== which side =====

    @Test
    void theStruckFaceIsTakenWhenItIsFree() {
        assertEquals(BlockFace.EAST, GateFireVisuals.chooseSide(BlockFace.EAST, new Vector(0, 0, -9), ALL_FREE));
    }

    @Test
    void aBlockedStruckFaceFallsBackToTheSideTheAttackerIsOn() {
        Predicate<BlockFace> eastBlocked = face -> face != BlockFace.EAST;

        assertEquals(BlockFace.NORTH, GateFireVisuals.chooseSide(BlockFace.EAST, new Vector(0, 0, -9), eastBlocked));
        assertEquals(BlockFace.WEST, GateFireVisuals.chooseSide(null, new Vector(-9, 1, 2), ALL_FREE));
        assertEquals(BlockFace.UP, GateFireVisuals.chooseSide(null, new Vector(1, 9, 1), ALL_FREE));
    }

    @Test
    void whenTheAttackersSideIsBlockedTheNextBestSideIsUsed() {
        Predicate<BlockFace> northBlocked = face -> face != BlockFace.NORTH;

        BlockFace side = GateFireVisuals.chooseSide(null, new Vector(1, 0, -9), northBlocked);

        assertEquals(BlockFace.EAST, side); // nearest to the attacker among the free sides
    }

    @Test
    void noSideAtAllWhenTheBlockIsWalledIn() {
        assertNull(GateFireVisuals.chooseSide(BlockFace.NORTH, new Vector(0, 0, -9), face -> false));
    }

    @Test
    void aFaceThatIsNotOneOfTheSixSidesIsNotAPreference() {
        assertEquals(BlockFace.NORTH, GateFireVisuals.chooseSide(BlockFace.SELF, null, ALL_FREE));
        assertEquals(BlockFace.NORTH, GateFireVisuals.chooseSide(BlockFace.NORTH_EAST, null, ALL_FREE));
    }

    // ===== showing, refreshing, putting out =====

    @Test
    void fireAppearsNextToTheDoorBlockOnTheAttackersSide() {
        Block door = doorBlock();

        visuals.show(gate, door, attackerAt(100.5, 64, 90.5), null); // the attacker is north of the gate

        Location cell = new Location(world, 100, 64, 99);
        verify(viewer).sendBlockChange(cell, fire);
        verify(fire).setFace(BlockFace.SOUTH, true); // the fire clings to the door block, which lies south of it
        assertEquals(new GateFireVisuals.Cell(new Vector(100, 64, 99), BlockFace.SOUTH),
            visuals.cellsOf(gate).get(new Vector(100, 64, 100)));
    }

    @Test
    void theStruckFaceBeatsWhereTheAttackerStands() {
        Block door = doorBlock();

        visuals.show(gate, door, attackerAt(100.5, 64, 90.5), BlockFace.EAST);

        verify(viewer).sendBlockChange(new Location(world, 101, 64, 100), fire);
    }

    @Test
    void fireOnTopOfTheBlockIsFloorFire() {
        Block door = doorBlock(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);

        visuals.show(gate, door, attackerAt(100.5, 80, 100.5), null); // an archer high above

        verify(viewer).sendBlockChange(new Location(world, 100, 65, 100), fire);
        verify(fire, never()).setFace(any(BlockFace.class), eq(true)); // floor fire: no face flag, and a fire block has no down face
    }

    @Test
    void aReignitedBlockKeepsItsFire() {
        Block door = doorBlock();
        visuals.show(gate, door, attackerAt(100.5, 64, 90.5), null);

        visuals.show(gate, door, attackerAt(100.5, 64, 110.5), null); // now an attacker south of the gate

        assertEquals(new Vector(100, 64, 99), visuals.cellsOf(gate).get(new Vector(100, 64, 100)).at());
    }

    @Test
    void nothingIsShownWhenEverySideIsBlocked() {
        Block door = doorBlock(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN);

        visuals.show(gate, door, null, null);

        assertTrue(visuals.cellsOf(gate).isEmpty());
        verifyNoInteractions(viewer);
    }

    @Test
    void aBlockInAnUnknownWorldShowsNothing() {
        Block door = mock(Block.class);
        when(door.getWorld()).thenReturn(null);

        visuals.show(gate, door, null, null);

        assertTrue(visuals.cellsOf(gate).isEmpty());
    }

    @Test
    void syncResendsTheFiresStillBurning() {
        Block door = doorBlock();
        gate.getBurningBlocks().put(new Vector(100, 64, 100), System.currentTimeMillis() + 5000L);
        visuals.show(gate, door, attackerAt(100.5, 64, 90.5), null);
        clearInvocations(viewer);

        visuals.sync(gate);

        verify(viewer).sendBlockChange(new Location(world, 100, 64, 99), fire);
    }

    @Test
    void syncTakesDownTheFireOfABlockThatBurnedOut() {
        Block door = doorBlock();
        visuals.show(gate, door, attackerAt(100.5, 64, 90.5), null);
        Block realBlock = mock(Block.class);
        BlockData air = mock(BlockData.class);
        when(realBlock.getBlockData()).thenReturn(air);
        when(world.getBlockAt(any(Location.class))).thenReturn(realBlock);
        clearInvocations(viewer);
        // the gate has no burning block any more

        visuals.sync(gate);

        verify(viewer).sendBlockChange(new Location(world, 100, 64, 99), air);
        assertTrue(visuals.cellsOf(gate).isEmpty());
    }

    @Test
    void extinguishingOneBlockLeavesTheOthersBurning() {
        Block first = doorBlock();
        visuals.show(gate, first, attackerAt(100.5, 64, 90.5), null);
        Block second = mock(Block.class);
        when(second.getX()).thenReturn(105);
        when(second.getY()).thenReturn(64);
        when(second.getZ()).thenReturn(100);
        when(second.getWorld()).thenReturn(world);
        Block air = mock(Block.class);
        when(air.getX()).thenReturn(105);
        when(air.getY()).thenReturn(64);
        when(air.getZ()).thenReturn(99);
        when(air.isEmpty()).thenReturn(true);
        when(second.getRelative(BlockFace.NORTH)).thenReturn(air);
        visuals.show(gate, second, null, BlockFace.NORTH);
        Block realBlock = mock(Block.class);
        when(realBlock.getBlockData()).thenReturn(mock(BlockData.class));
        when(world.getBlockAt(any(Location.class))).thenReturn(realBlock);

        visuals.extinguish(gate, new Vector(100, 64, 100));

        assertEquals(1, visuals.cellsOf(gate).size());
        assertTrue(visuals.cellsOf(gate).containsKey(new Vector(105, 64, 100)));
    }

    @Test
    void aCellSharedByTwoBurningBlocksStaysUntilTheLastOneIsGone() {
        // two door blocks (100,64,100) and (101,64,99)... both burning with their fire in the same air cell (101,64,100)
        Block shared = mock(Block.class);
        when(shared.isEmpty()).thenReturn(true);
        when(shared.getX()).thenReturn(101);
        when(shared.getY()).thenReturn(64);
        when(shared.getZ()).thenReturn(100);
        Block left = mock(Block.class);
        when(left.getX()).thenReturn(100);
        when(left.getY()).thenReturn(64);
        when(left.getZ()).thenReturn(100);
        when(left.getWorld()).thenReturn(world);
        when(left.getRelative(BlockFace.EAST)).thenReturn(shared);
        Block right = mock(Block.class);
        when(right.getX()).thenReturn(102);
        when(right.getY()).thenReturn(64);
        when(right.getZ()).thenReturn(100);
        when(right.getWorld()).thenReturn(world);
        when(right.getRelative(BlockFace.WEST)).thenReturn(shared);
        visuals.show(gate, left, null, BlockFace.EAST);
        visuals.show(gate, right, null, BlockFace.WEST);
        Block realBlock = mock(Block.class);
        BlockData real = mock(BlockData.class);
        when(realBlock.getBlockData()).thenReturn(real);
        when(world.getBlockAt(any(Location.class))).thenReturn(realBlock);
        clearInvocations(viewer);

        visuals.extinguish(gate, new Vector(100, 64, 100));
        verify(viewer, never()).sendBlockChange(any(Location.class), eq(real)); // the other block still burns there

        visuals.extinguish(gate, new Vector(102, 64, 100));
        verify(viewer).sendBlockChange(new Location(world, 101, 64, 100), real);
    }

    @Test
    void clearTakesDownEveryFireOfTheGate() {
        visuals.show(gate, doorBlock(), attackerAt(100.5, 64, 90.5), null);
        Block realBlock = mock(Block.class);
        BlockData real = mock(BlockData.class);
        when(realBlock.getBlockData()).thenReturn(real);
        when(world.getBlockAt(any(Location.class))).thenReturn(realBlock);
        clearInvocations(viewer);

        visuals.clear(gate);

        verify(viewer).sendBlockChange(new Location(world, 100, 64, 99), real);
        assertTrue(visuals.cellsOf(gate).isEmpty());
    }

    @Test
    void anUnloadedChunkIsNotLoadedJustToPutFireOut() {
        visuals.show(gate, doorBlock(), attackerAt(100.5, 64, 90.5), null);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        clearInvocations(viewer);

        visuals.clear(gate);

        verify(world, never()).getBlockAt(any(Location.class));
        verifyNoInteractions(viewer);
    }
}
