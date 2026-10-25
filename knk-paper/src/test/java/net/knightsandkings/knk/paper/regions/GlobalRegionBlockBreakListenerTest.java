package net.knightsandkings.knk.paper.regions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;

/**
 * The global-region rule (QOL hotfix 2026-10-25): a break where only {@code __global__} applies is refused for a
 * player without bypass and announced once per cooldown; a region, a bypass or the config switch lets it through.
 */
class GlobalRegionBlockBreakListenerTest {
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicBoolean bypass = new AtomicBoolean(false);
    private final AtomicLong now = new AtomicLong(10_000L);
    private Set<String> regions = Set.of();
    private GlobalRegionBlockBreakListener listener;
    private World world;
    private Player player;

    @BeforeEach
    void setUp() {
        listener = new GlobalRegionBlockBreakListener(enabled::get, location -> regions, p -> bypass.get(), now::get);
        world = mock(World.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    }

    private BlockBreakEvent breakAt(int x, int y, int z) {
        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(new Location(world, x, y, z));
        return new BlockBreakEvent(block, player);
    }

    @Test
    void refusesADefaultPlayerOutsideEveryRegionAndTellsThem() {
        BlockBreakEvent event = breakAt(100, 64, 100);
        listener.onBreak(event);

        assertTrue(event.isCancelled());
        verify(player).sendActionBar(any(Component.class));
    }

    @Test
    void insideARegionTheRegionDecides() {
        regions = Set.of("domain_12");
        BlockBreakEvent event = breakAt(100, 64, 100);
        listener.onBreak(event);

        assertFalse(event.isCancelled());
        verify(player, never()).sendActionBar(any(Component.class));
    }

    @Test
    void bypassHoldersBreakAnywhere() {
        bypass.set(true);
        BlockBreakEvent event = breakAt(100, 64, 100);
        listener.onBreak(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void switchedOffInConfigDoesNothing() {
        enabled.set(false);
        BlockBreakEvent event = breakAt(100, 64, 100);
        listener.onBreak(event);

        assertFalse(event.isCancelled());
        verify(player, never()).sendActionBar(any(Component.class));
    }

    @Test
    void everyBreakIsRefusedButTheNoticeWaitsForTheCooldown() {
        BlockBreakEvent first = breakAt(1, 64, 1);
        BlockBreakEvent second = breakAt(2, 64, 1);
        BlockBreakEvent third = breakAt(3, 64, 1);

        listener.onBreak(first);
        now.addAndGet(GlobalRegionBlockBreakListener.NOTICE_COOLDOWN_MILLIS - 1);
        listener.onBreak(second);
        now.addAndGet(1);
        listener.onBreak(third);

        assertTrue(first.isCancelled());
        assertTrue(second.isCancelled());
        assertTrue(third.isCancelled());
        verify(player, times(2)).sendActionBar(any(Component.class));
    }
}
