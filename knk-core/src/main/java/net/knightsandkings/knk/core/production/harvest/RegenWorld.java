package net.knightsandkings.knk.core.production.harvest;

/** Port: the world as the regeneration queue sees it (the Paper adapter, main thread). */
public interface RegenWorld {

    /** Whether the chunk is loaded (a restore never loads one). */
    boolean chunkLoaded(String world, int chunkX, int chunkZ);

    /** The block data at (x, y, z) as Bukkit writes it, or null when the world is not loaded. */
    String blockData(String world, int x, int y, int z);

    /** Whether a player or another living entity stands in the block (a restore would trap it). */
    boolean occupied(String world, int x, int y, int z);

    /** Sets the block without physics or drops. */
    void set(String world, int x, int y, int z, String blockData);
}
