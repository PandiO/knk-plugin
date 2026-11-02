package net.knightsandkings.knk.paper.regions;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.minecart.StorageMinecart;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PandiO/knk-workspace#157: storage bypass for staff/owner mode and knk.region.bypass.containers holders. */
class ContainerAccessBypassTest {

    private final Player moderator = mock(Player.class);
    private final Player staffInMode = mock(Player.class);
    private final Player player = mock(Player.class);
    private final Set<Player> inMode = new HashSet<>(Set.of(staffInMode));
    private final Set<Player> holders = new HashSet<>(Set.of(moderator));
    private final ContainerAccessBypass bypass = new ContainerAccessBypass(inMode::contains, holders::contains);

    private static Block block(Class<? extends BlockState> state) {
        Block block = mock(Block.class);
        BlockState blockState = mock(state);
        when(block.getState(false)).thenReturn(blockState);
        return block;
    }

    @Test
    void theNodeOpensStorage() {
        assertTrue(bypass.allowsBlocks(moderator, List.of(block(Chest.class))));
        assertTrue(bypass.allowsBlocks(moderator, List.of(block(Chest.class), block(Barrel.class)))); // a double chest
    }

    @Test
    void staffOrOwnerModeAlwaysOpensStorage() {
        assertTrue(bypass.allowsBlocks(staffInMode, List.of(block(Barrel.class))));
    }

    @Test
    void aPlayerWithoutEitherStaysRefused() {
        assertFalse(bypass.allowsBlocks(player, List.of(block(Chest.class))));
        assertFalse(bypass.allowsEntity(player, mock(StorageMinecart.class)));
    }

    @Test
    void onlyStorageIsAffected() {
        assertFalse(bypass.allowsBlocks(moderator, List.of(block(BlockState.class)))); // a door, a lever, ...
        assertFalse(bypass.allowsBlocks(moderator, List.of(block(Chest.class), block(BlockState.class))));
        assertFalse(bypass.allowsBlocks(moderator, List.of()));
        assertTrue(bypass.allowsEntity(moderator, mock(StorageMinecart.class)));
        assertFalse(bypass.allowsEntity(moderator, mock(Villager.class))); // holds an inventory, but isn't storage
    }
}
