package net.knightsandkings.knk.paper.npc.citizens;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.TrapDoor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Link H1 (PandiO/knk-workspace#779): an NPC with {@code open-doors} opens a closed roof hatch it is about to climb
 * through, and the hatch is closed again once its shaft is empty. A player body stands in for the NPC's (MockBukkit).
 */
class NpcHatchesTest {

    private ServerMock server;
    private WorldMock world;
    private NpcHatches hatches;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("hatch");
        hatches = new NpcHatches(MockBukkit.createMockPlugin());
        world.loadChunk(0, 0); // NPCs only open hatches in loaded chunks
        for (int y = 64; y <= 70; y++) {
            world.getBlockAt(0, y, 0).setType(Material.LADDER);
        }
        hatch(0, Material.OAK_TRAPDOOR);
        hatch(2, Material.IRON_TRAPDOOR);
        world.getBlockAt(2, 70, 0).setType(Material.LADDER);
        hatch(4, Material.OAK_TRAPDOOR); // over stone: no hatch
        world.getBlockAt(4, 70, 0).setType(Material.STONE);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void hatch(int x, Material material) {
        Block block = world.getBlockAt(x, 71, 0);
        block.setType(material);
        TrapDoor data = (TrapDoor) block.getBlockData();
        data.setOpen(false);
        block.setBlockData(data);
    }

    private boolean open(int x) {
        return ((TrapDoor) world.getBlockAt(x, 71, 0).getBlockData()).isOpen();
    }

    private PlayerMock bodyAt(double x, double y, double z) {
        PlayerMock body = server.addPlayer();
        body.teleport(new Location(world, x, y, z));
        return body;
    }

    @Test
    void aHatchRightAboveTheHeadIsOpenedOnTheWayUp() {
        PlayerMock body = bodyAt(0.5, 69.5, 0.5); // on the ladder, the head in the block under the hatch
        assertTrue(hatches.ahead(body, null));
        assertTrue(open(0));
        assertFalse(hatches.ahead(body, null), "open already: nothing to do");
    }

    @Test
    void aHatchInTheColumnOfTheNextWaypointIsOpenedOnTheWayDown() {
        PlayerMock body = bodyAt(1.5, 72, 0.5); // on the roof beside the hatch
        assertTrue(hatches.ahead(body, new Location(world, 0.5, 71, 0.5)));
        assertTrue(open(0));
    }

    @Test
    void anIronHatchATrapdoorWithoutALadderAndAFarHatchStayClosed() {
        assertFalse(hatches.ahead(bodyAt(2.5, 69.5, 0.5), null), "iron needs redstone");
        assertFalse(open(2));
        assertFalse(hatches.ahead(bodyAt(4.5, 69.5, 0.5), new Location(world, 4.5, 71, 0.5)), "no ladder below");
        assertFalse(open(4));
        assertFalse(hatches.ahead(bodyAt(0.5, 64, 0.5), new Location(world, 0.5, 65, 0.5)), "at the ladder's foot");
        assertFalse(open(0));
    }

    @Test
    void theHatchIsClosedOnceItsShaftIsEmpty() {
        PlayerMock body = bodyAt(0.5, 69.5, 0.5);
        hatches.ahead(body, null);
        server.getScheduler().performTicks(NpcHatches.CLOSE_AFTER_TICKS + 2L * NpcHatches.CHECK_TICKS);
        assertTrue(open(0), "someone is still in the shaft");

        body.teleport(new Location(world, 10.5, 72, 10.5));
        server.getScheduler().performTicks(NpcHatches.CLOSE_AFTER_TICKS + 2L * NpcHatches.CHECK_TICKS);
        assertFalse(open(0), "closed again, as a door behind the NPC");
        assertEquals(0, hatches.open().size());
    }

    @Test
    void aHatchSomeoneElseClosedIsLeftAlone() {
        PlayerMock body = bodyAt(0.5, 69.5, 0.5);
        hatches.ahead(body, null);
        hatch(0, Material.OAK_TRAPDOOR); // a player closed it again
        server.getScheduler().performTicks(NpcHatches.CHECK_TICKS);
        assertTrue(hatches.open().isEmpty());
        assertFalse(open(0));
    }
}
