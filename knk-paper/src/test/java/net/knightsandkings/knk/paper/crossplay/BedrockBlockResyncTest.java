package net.knightsandkings.knk.paper.crossplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.crossplay.BlockResyncWatch;

/** PandiO/knk-workspace#161: refused breaks are sent to Bedrock players again. */
class BedrockBlockResyncTest {

    private final World world = mock(World.class);
    private final Player bedrock = mock(Player.class);
    private final Player java = mock(Player.class);
    private final UUID bedrockId = UUID.randomUUID();
    private final UUID javaId = UUID.randomUUID();
    private final Block block = mock(Block.class);
    private final BlockData stone = mock(BlockData.class);
    private final Location location = mock(Location.class);
    private final Set<UUID> online = new HashSet<>();
    private BedrockBlockResync resync;

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn("world");
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(10);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(-5);
        when(block.getBlockData()).thenReturn(stone);
        when(block.getLocation()).thenReturn(location);
        when(bedrock.getUniqueId()).thenReturn(bedrockId);
        when(bedrock.getWorld()).thenReturn(world);
        when(java.getUniqueId()).thenReturn(javaId);
        when(java.getWorld()).thenReturn(world);
        online.add(bedrockId);
        online.add(javaId);
        resync = new BedrockBlockResync(new BlockResyncWatch(10, 5, 8), id -> id.equals(bedrockId),
            id -> !online.contains(id) ? null : id.equals(bedrockId) ? bedrock : java);
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            resync.tick();
        }
    }

    private BlockBreakEvent cancelledBreak(Player player) {
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        event.setCancelled(true);
        return event;
    }

    @Test
    void aCancelledBreakIsSentAgainWhileWatched() {
        resync.onBreak(cancelledBreak(bedrock));
        ticks(10);
        // the tick after, every 5 ticks, and the last one when the window ends: ticks 1, 6, 10
        verify(bedrock, times(3)).sendBlockChange(location, stone);
        ticks(20);
        verify(bedrock, times(3)).sendBlockChange(location, stone);
    }

    @Test
    void anAllowedBreakIsLeftAlone() {
        resync.onBreak(new BlockBreakEvent(block, bedrock));
        ticks(20);
        verify(bedrock, never()).sendBlockChange(any(Location.class), any(BlockData.class));
    }

    @Test
    void javaPlayersAreLeftAlone() {
        resync.onBreak(cancelledBreak(java));
        ticks(20);
        verify(java, never()).sendBlockChange(any(Location.class), any(BlockData.class));
    }

    @Test
    void aBreakRefusedAtItsStartIsWatchedToo() {
        PlayerInteractEvent click = new PlayerInteractEvent(bedrock, Action.LEFT_CLICK_BLOCK, null, block, BlockFace.UP);
        click.setUseInteractedBlock(Event.Result.DENY);
        resync.onInteract(click);
        BlockDamageEvent damage = mock(BlockDamageEvent.class);
        when(damage.isCancelled()).thenReturn(true);
        when(damage.getPlayer()).thenReturn(bedrock);
        when(damage.getBlock()).thenReturn(block);
        resync.onDamage(damage);
        ticks(1);
        verify(bedrock, times(1)).sendBlockChange(location, stone);
    }

    @Test
    void swingingKeepsItWatched() {
        resync.onBreak(cancelledBreak(bedrock));
        ticks(8);
        resync.onSwing(new PlayerAnimationEvent(bedrock, PlayerAnimationType.ARM_SWING));
        ticks(12);
        // 1, 6, 11, 16, and the last at 18
        verify(bedrock, times(5)).sendBlockChange(location, stone);
    }

    @Test
    void aPlayerWhoLeftIsForgotten() {
        resync.onBreak(cancelledBreak(bedrock));
        online.remove(bedrockId);
        ticks(20);
        verify(bedrock, never()).sendBlockChange(any(Location.class), any(BlockData.class));
    }

    @Test
    void anotherWorldOrAnUnloadedChunkIsSkipped() {
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        resync.onBreak(cancelledBreak(bedrock));
        ticks(20);
        verify(bedrock, never()).sendBlockChange(any(Location.class), any(BlockData.class));
    }

    @Test
    void doorsAndBedsBringTheirOtherHalf() {
        Block upper = mock(Block.class);
        Door door = mock(Door.class);
        when(door.getHalf()).thenReturn(Bisected.Half.BOTTOM);
        when(block.getBlockData()).thenReturn(door);
        when(block.getRelative(BlockFace.UP)).thenReturn(upper);
        assertEquals(List.of(block, upper), BedrockBlockResync.withPartners(block));

        Block head = mock(Block.class);
        Bed bed = mock(Bed.class);
        when(bed.getPart()).thenReturn(Bed.Part.FOOT);
        when(bed.getFacing()).thenReturn(BlockFace.NORTH);
        when(block.getBlockData()).thenReturn(bed);
        when(block.getRelative(BlockFace.NORTH)).thenReturn(head);
        assertEquals(List.of(block, head), BedrockBlockResync.withPartners(block));

        when(block.getBlockData()).thenReturn(stone);
        assertEquals(List.of(block), BedrockBlockResync.withPartners(block));
    }
}
