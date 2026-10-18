package net.knightsandkings.knk.paper.worlds;

import java.util.Random;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

/**
 * An empty world for a hub that is built, not generated (KNG-114, {@code generator: void}): no terrain, caves,
 * structures or mobs, only a small stone platform at the spawn so the first visitor does not fall.
 */
public final class VoidChunkGenerator extends ChunkGenerator {

    /** The platform's top block layer; players spawn on it. */
    static final int PLATFORM_Y = 64;
    /** The platform covers x and z 0..PLATFORM_SIZE-1 in chunk 0,0. */
    static final int PLATFORM_SIZE = 5;

    @Override
    public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        if (chunkX != 0 || chunkZ != 0 || PLATFORM_Y < chunkData.getMinHeight() || PLATFORM_Y >= chunkData.getMaxHeight()) {
            return;
        }
        for (int x = 0; x < PLATFORM_SIZE; x++) {
            for (int z = 0; z < PLATFORM_SIZE; z++) {
                chunkData.setBlock(x, PLATFORM_Y, z, Material.STONE);
            }
        }
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, PLATFORM_SIZE / 2.0, PLATFORM_Y + 1, PLATFORM_SIZE / 2.0);
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }
}
