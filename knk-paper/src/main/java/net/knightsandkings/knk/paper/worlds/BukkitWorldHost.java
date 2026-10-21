package net.knightsandkings.knk.paper.worlds;

import java.io.File;
import java.nio.file.Files;

import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;

import net.knightsandkings.knk.core.worlds.WorldLoader;
import net.knightsandkings.knk.core.worlds.WorldSpec;

/**
 * {@link WorldLoader.Host} over the Bukkit server. {@link Server#createWorld} must run on the main thread and throws
 * while worlds are ticking, so it is only called from the plugin's enable.
 */
final class BukkitWorldHost implements WorldLoader.Host {

    private final Server server;

    BukkitWorldHost(Server server) {
        this.server = server;
    }

    @Override
    public boolean isLoaded(String name) {
        return server.getWorld(name) != null;
    }

    /**
     * A pre-26.1 world folder in the world container ({@code <name>/level.dat}), which Paper imports into the level
     * directory on its first load and then deletes, or the folder Paper 26.1+ keeps the world in:
     * {@code <level directory>/dimensions/<namespace>/<key>}, the key {@link WorldCreator} derives from the name.
     */
    @Override
    public boolean folderExists(String name) {
        if (new File(new File(server.getWorldContainer(), name), "level.dat").isFile()) {
            return true;
        }
        NamespacedKey key = new WorldCreator(name).key();
        return Files.isDirectory(server.getLevelDirectory().resolve("dimensions").resolve(key.getNamespace())
                .resolve(key.getKey()));
    }

    @Override
    public void load(WorldSpec spec) {
        World world = server.createWorld(creator(spec));
        if (world == null) {
            throw new IllegalStateException("the server returned no world");
        }
    }

    static WorldCreator creator(WorldSpec spec) {
        WorldCreator creator = new WorldCreator(spec.name()).environment(switch (spec.environment()) {
            case NORMAL -> World.Environment.NORMAL;
            case NETHER -> World.Environment.NETHER;
            case THE_END -> World.Environment.THE_END;
        });
        switch (spec.generator()) {
            case FLAT -> creator.type(WorldType.FLAT);
            case VOID -> creator.generator(new VoidChunkGenerator()).generateStructures(false);
            case DEFAULT -> {
            }
        }
        return creator;
    }
}
