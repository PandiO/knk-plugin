package net.knightsandkings.knk.paper.production.terminal;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import net.knightsandkings.knk.core.production.terminal.BlockKey;

/**
 * The container blocks a storage can be bound to (production DESIGN § 6.3: chests, trapped chests and barrels) and their
 * canonical block: a double chest is keyed by its left half (Bukkit's {@link DoubleChest#getLeftSide()}), so both halves
 * find the same binding.
 */
public final class ContainerBlocks {

    private ContainerBlocks() {
    }

    /** Chest, TrappedChest or Barrel; null for any other block. */
    public static String containerType(Material material) {
        if (material == null) {
            return null;
        }
        return switch (material) {
            case CHEST -> "Chest";
            case TRAPPED_CHEST -> "TrappedChest";
            case BARREL -> "Barrel";
            default -> null;
        };
    }

    public static boolean bindable(Block block) {
        return block != null && containerType(block.getType()) != null;
    }

    public static BlockKey key(Block block) {
        return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /** The block a binding is keyed by: the left half of a double chest, else the block itself. */
    public static Block canonical(Block block) {
        if (block == null || (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST)) {
            return block;
        }
        BlockState state = block.getState();
        if (state instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder();
            if (holder instanceof DoubleChest dbl && dbl.getLeftSide() instanceof Chest left) {
                return left.getBlock();
            }
        }
        return block;
    }

    /** Whether the chest at {@code block} is half of a double chest. */
    public static boolean isDouble(Block block) {
        BlockState state = block == null ? null : block.getState();
        return state instanceof Chest chest && chest.getInventory().getHolder() instanceof DoubleChest;
    }

    /** The canonical block of an inventory's holder (a container block or a double chest), or null. */
    public static Block canonical(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof DoubleChest dbl) {
            return dbl.getLeftSide() instanceof Chest left ? left.getBlock() : null;
        }
        if (holder instanceof Container container) {
            return canonical(container.getBlock());
        }
        return null;
    }

    /** The whole inventory of the container (both halves of a double chest), or null. */
    public static Inventory inventory(Block block) {
        BlockState state = block == null ? null : block.getState();
        return state instanceof Container container ? container.getInventory() : null;
    }
}
