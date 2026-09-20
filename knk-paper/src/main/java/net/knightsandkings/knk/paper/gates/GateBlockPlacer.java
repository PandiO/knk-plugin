package net.knightsandkings.knk.paper.gates;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.Vector;

import java.util.logging.Logger;

/**
 * Utility class for placing and removing gate blocks during animation.
 * Handles block physics, fallback materials, and error recovery.
 */
public class GateBlockPlacer {
    private static final Logger LOGGER = Logger.getLogger(GateBlockPlacer.class.getName());

    /**
     * Place a block at the specified location with the given block data.
     * 
     * @param world The world to place the block in
     * @param position The position to place the block at
     * @param blockData The block data string (e.g., "minecraft:stone", "minecraft:oak_door[facing=north]")
     * @param fallbackMaterial Fallback material if blockData is invalid
     * @return True if block was placed successfully
     */
    public static boolean placeBlock(World world, Vector position, String blockData, Material fallbackMaterial) {
        if (world == null || position == null) {
            LOGGER.warning("Cannot place block: world or position is null");
            return false;
        }

        // Create location
        Location location = new Location(
            world,
            position.getBlockX(),
            position.getBlockY(),
            position.getBlockZ()
        );

        // Check if chunk is loaded
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            LOGGER.fine("Chunk not loaded at " + location + ", skipping block placement");
            return false;
        }

        Block block = world.getBlockAt(location);

        try {
            // Parse block data
            BlockData data = parseBlockData(blockData, fallbackMaterial);
            
            if (data == null) {
                LOGGER.warning("Failed to parse block data: " + blockData);
                return false;
            }

            // Set block with physics disabled to prevent water flow, gravel fall, etc.
            block.setBlockData(data, false);
            return true;

        } catch (Exception e) {
            LOGGER.warning("Error placing block at " + location + ": " + e.getMessage());
            
            // Try fallback material as last resort
            if (fallbackMaterial != null && fallbackMaterial != Material.AIR) {
                try {
                    block.setType(fallbackMaterial, false);
                    LOGGER.fine("Used fallback material " + fallbackMaterial + " at " + location);
                    return true;
                } catch (Exception ex) {
                    LOGGER.severe("Failed to place fallback material: " + ex.getMessage());
                }
            }
            
            return false;
        }
    }

    /**
     * Place a gate block only when the target cell is free, or already holds the exact
     * block the gate wants there. Anything else (player builds, terrain) is left untouched.
     *
     * @return True if the block was placed, false if the position was skipped
     */
    public static boolean placeBlockIfVacant(World world, Vector position, String blockData, Material fallbackMaterial) {
        if (world == null || position == null) {
            return false;
        }

        Block block = getBlockIfLoaded(world, position);
        if (block == null) {
            return false;
        }

        BlockData desired = parseBlockData(blockData, fallbackMaterial);
        if (desired == null) {
            return false;
        }

        if (isExpectedGateBlock(block, desired)) {
            clearSnowLayerAbove(world, position);
            return placeBlock(world, position, blockData, fallbackMaterial);
        }

        if (!isReplaceable(block)) {
            LOGGER.fine("Skipped gate block at " + describe(position) + ": occupied by "
                + block.getType() + " which the gate did not place");
            return false;
        }

        clearSnowLayerAbove(world, position);
        return placeBlock(world, position, blockData, fallbackMaterial);
    }

    /**
     * Remove a gate block only when the cell still holds the block the gate put there.
     * Protects against wiping blocks a player placed in the gate's path mid-animation.
     *
     * @return True if the block was removed, false if the position was skipped
     */
    public static boolean removeBlockIfMatches(World world, Vector position, String expectedBlockData, Material fallbackMaterial) {
        if (world == null || position == null) {
            return false;
        }

        Block block = getBlockIfLoaded(world, position);
        if (block == null) {
            return false;
        }

        if (block.getType() == Material.AIR) {
            return true;
        }

        BlockData expected = parseBlockData(expectedBlockData, fallbackMaterial);
        if (expected == null || !isExpectedGateBlock(block, expected)) {
            LOGGER.fine("Skipped gate block removal at " + describe(position) + ": found "
                + block.getType() + " instead of the expected gate block");
            return false;
        }

        clearSnowLayerAbove(world, position);
        return removeBlock(world, position);
    }

    /**
     * Silently clears a snow layer (not a solid snow block) directly above a gate block
     * position, if one is present. Snow physics are disabled everywhere gate blocks are
     * placed/removed ({@code setBlockData}/{@code setType} called with {@code applyPhysics=
     * false}), so a snow layer resting on a gate block never gets its usual "break when
     * unsupported" check when that gate block moves away - left unhandled, it hovers in
     * mid-air. Presence/absence only, no item drop or layer-height tracking - see
     * QOL_BUGFIX_BACKLOG.md #7.
     */
    private static void clearSnowLayerAbove(World world, Vector position) {
        Block above = getBlockIfLoaded(world, position.clone().add(new Vector(0, 1, 0)));
        if (above != null && above.getType() == Material.SNOW) {
            above.setType(Material.AIR, false);
        }
    }

    /**
     * Read-only, tri-state comparison used by gate world/DB sync verification (see
     * GateWorldSyncChecker): {@code null} means "chunk not loaded, cannot determine" - the
     * caller decides whether that counts as a mismatch or is simply skipped. Never mutates
     * the world.
     *
     * @return null if the chunk isn't loaded or expectedBlockData can't be parsed; otherwise
     *         whether the block at position matches expectedBlockData
     */
    public static Boolean blockMatches(World world, Vector position, String expectedBlockData, Material fallbackMaterial) {
        if (world == null || position == null) {
            return null;
        }

        Block block = getBlockIfLoaded(world, position);
        if (block == null) {
            return null;
        }

        BlockData expected = parseBlockData(expectedBlockData, fallbackMaterial);
        if (expected == null) {
            return null;
        }

        return isExpectedGateBlock(block, expected);
    }

    private static Block getBlockIfLoaded(World world, Vector position) {
        if (!world.isChunkLoaded(position.getBlockX() >> 4, position.getBlockZ() >> 4)) {
            return null;
        }
        return world.getBlockAt(position.getBlockX(), position.getBlockY(), position.getBlockZ());
    }

    private static boolean isReplaceable(Block block) {
        return block.getType() == Material.AIR || block.isLiquid() || block.isReplaceable();
    }

    private static boolean isExpectedGateBlock(Block block, BlockData expected) {
        return block.getBlockData().matches(expected) || block.getType() == expected.getMaterial();
    }

    private static String describe(Vector position) {
        return "(" + position.getBlockX() + ", " + position.getBlockY() + ", " + position.getBlockZ() + ")";
    }

    /**
     * Remove a block by setting it to air.
     * 
     * @param world The world to remove the block from
     * @param position The position of the block to remove
     * @return True if block was removed successfully
     */
    public static boolean removeBlock(World world, Vector position) {
        if (world == null || position == null) {
            return false;
        }

        Location location = new Location(
            world,
            position.getBlockX(),
            position.getBlockY(),
            position.getBlockZ()
        );

        // Check if chunk is loaded
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return false;
        }

        try {
            Block block = world.getBlockAt(location);
            block.setType(Material.AIR, false); // Physics disabled
            return true;
        } catch (Exception e) {
            LOGGER.warning("Error removing block at " + location + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Parse a block data string into a BlockData object.
     * Supports formats like:
     * - "minecraft:stone"
     * - "stone"
     * - "minecraft:oak_door[facing=north,half=lower]"
     * 
     * @param blockDataString The block data string
     * @param fallback Fallback material if parsing fails
     * @return BlockData object or null if parsing failed
     */
    private static BlockData parseBlockData(String blockDataString, Material fallback) {
        if (blockDataString == null || blockDataString.isEmpty()) {
            return fallback != null ? fallback.createBlockData() : null;
        }

        try {
            // Try to parse as full block data string (with properties)
            return Bukkit.createBlockData(blockDataString);
        } catch (IllegalArgumentException e) {
            // If that fails, try to parse as material name
            try {
                Material material = Material.matchMaterial(blockDataString);
                if (material != null && material.isBlock()) {
                    return material.createBlockData();
                }
            } catch (Exception ex) {
                LOGGER.fine("Failed to parse material: " + blockDataString);
            }

            // Use fallback
            if (fallback != null) {
                LOGGER.fine("Using fallback material for: " + blockDataString);
                return fallback.createBlockData();
            }

            return null;
        }
    }

    /**
     * Check if a chunk is loaded at the given position.
     * 
     * @param world The world to check
     * @param position The position to check
     * @return True if chunk is loaded
     */
    public static boolean isChunkLoaded(World world, Vector position) {
        if (world == null || position == null) {
            return false;
        }

        int chunkX = position.getBlockX() >> 4;
        int chunkZ = position.getBlockZ() >> 4;
        
        return world.isChunkLoaded(chunkX, chunkZ);
    }

    /**
     * Batch place multiple blocks efficiently.
     * 
     * @param world The world to place blocks in
     * @param positions Array of positions
     * @param blockDataStrings Array of block data strings (same length as positions)
     * @param fallbackMaterial Fallback material for all blocks
     * @return Number of blocks successfully placed
     */
    public static int placeBlocks(World world, Vector[] positions, String[] blockDataStrings, Material fallbackMaterial) {
        if (positions == null || blockDataStrings == null || positions.length != blockDataStrings.length) {
            LOGGER.warning("Invalid arguments for batch block placement");
            return 0;
        }

        int successCount = 0;
        
        for (int i = 0; i < positions.length; i++) {
            if (placeBlock(world, positions[i], blockDataStrings[i], fallbackMaterial)) {
                successCount++;
            }
        }

        return successCount;
    }

    /**
     * Check if a block position is safe to modify (not in protected regions, etc.).
     * For future integration with WorldGuard/protection plugins.
     * 
     * @param world The world
     * @param position The position to check
     * @return True if safe to modify
     */
    public static boolean isSafeToModify(World world, Vector position) {
        // TODO: Add WorldGuard integration to check if position is in a protected region
        // For now, assume all positions are safe
        return true;
    }
}
