package net.knightsandkings.knk.core.production.survey;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A {@link BlockSnapshotSource} over a map of positions (air elsewhere); every chunk is captured unless left out. */
final class FakeBlocks implements BlockSnapshotSource {

    final Map<Long, String> materials = new HashMap<>();
    final Map<Long, String> biomes = new HashMap<>();
    final Set<Long> flowing = new HashSet<>();
    final Set<Long> missingChunks = new HashSet<>();
    String defaultBiome = "PLAINS";
    int minY = -64;
    int maxY = 320;

    FakeBlocks set(int x, int y, int z, String material) {
        materials.put(PlaceholderOverlay.key(x, y, z), material);
        return this;
    }

    FakeBlocks fill(int x1, int y1, int z1, int x2, int y2, int z2, String material) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    set(x, y, z, material);
                }
            }
        }
        return this;
    }

    FakeBlocks biome(int x, int y, int z, String biome) {
        biomes.put(PlaceholderOverlay.key(x, y, z), biome);
        return this;
    }

    FakeBlocks missingChunk(int cx, int cz) {
        missingChunks.add(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
        return this;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    @Override
    public ChunkBlocks chunk(int chunkX, int chunkZ) {
        if (missingChunks.contains(((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL))) {
            return null;
        }
        return new ChunkBlocks() {
            @Override
            public String material(int localX, int y, int localZ) {
                return materials.getOrDefault(PlaceholderOverlay.key(chunkX * 16 + localX, y, chunkZ * 16 + localZ), "AIR");
            }

            @Override
            public String biome(int localX, int y, int localZ) {
                return biomes.getOrDefault(PlaceholderOverlay.key(chunkX * 16 + localX, y, chunkZ * 16 + localZ), defaultBiome);
            }

            @Override
            public boolean waterSource(int localX, int y, int localZ) {
                long key = PlaceholderOverlay.key(chunkX * 16 + localX, y, chunkZ * 16 + localZ);
                return "WATER".equals(materials.get(key)) && !flowing.contains(key);
            }

            @Override
            public int highestY(int localX, int localZ) {
                for (int y = maxY - 1; y >= minY; y--) {
                    if (!material(localX, y, localZ).endsWith("AIR")) {
                        return y;
                    }
                }
                return minY;
            }
        };
    }
}
