package net.knightsandkings.knk.core.production.harvest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** An in-memory world for the regeneration tests: blocks by position, loaded chunks, occupied blocks. */
final class FakeRegenWorld implements RegenWorld {

    final Map<String, String> blocks = new HashMap<>();
    final Set<Long> unloaded = new HashSet<>();
    final Set<String> occupied = new HashSet<>();
    int sets;

    static String key(int x, int y, int z) {
        return x + " " + y + " " + z;
    }

    void put(int x, int y, int z, String data) {
        blocks.put(key(x, y, z), data);
    }

    String at(int x, int y, int z) {
        return blocks.getOrDefault(key(x, y, z), "minecraft:air");
    }

    @Override
    public boolean chunkLoaded(String world, int chunkX, int chunkZ) {
        return !unloaded.contains(HarvestAreaIndex.chunkKey(chunkX, chunkZ));
    }

    @Override
    public String blockData(String world, int x, int y, int z) {
        return at(x, y, z);
    }

    @Override
    public boolean occupied(String world, int x, int y, int z) {
        return occupied.contains(key(x, y, z));
    }

    @Override
    public void set(String world, int x, int y, int z, String blockData) {
        sets++;
        put(x, y, z, blockData);
    }
}
