package net.knightsandkings.knk.paper.worlds;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import net.knightsandkings.knk.core.worlds.KnkWorldsConfig;
import net.knightsandkings.knk.core.worlds.WorldLoader;
import net.knightsandkings.knk.core.worlds.WorldReadiness;

/**
 * The minimal KnK world loader (KNG-114, hub DESIGN §16 decision 1: no Multiverse). {@link #loadAndGuard} runs first in
 * the plugin's enable, so gates, region repair, Game Settings and admission all start with every configured world
 * loaded. Worlds an admin adds to {@code worlds.load} come up at the next restart.
 */
public final class KnkWorlds {

    private static final Logger LOGGER = Logger.getLogger(KnkWorlds.class.getName());

    private volatile WorldReadiness readiness = WorldReadiness.none();

    /** Reads {@code worlds}, loads or creates the listed worlds, logs the result and registers the unload/join guard. */
    public WorldReadiness loadAndGuard(JavaPlugin plugin) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("worlds");
        KnkWorldsConfig config = KnkWorldsConfig.fromMap(section != null ? toMap(section) : null);
        config.warnings().forEach(warning -> LOGGER.warning("[KnK Worlds] config: " + warning));

        readiness = new WorldLoader(new BukkitWorldHost(plugin.getServer())).loadAll(config.worlds());
        for (WorldReadiness.Status status : readiness.worlds()) {
            if (status.state().available()) {
                LOGGER.info("[KnK Worlds] " + status.describe());
            } else {
                LOGGER.severe("[KnK Worlds] " + status.describe());
            }
        }
        if (!readiness.ready()) {
            LOGGER.severe("[KnK Worlds] A required world is not available: only operators may join until it is fixed"
                + " (worlds.load in config.yml, then restart)");
        }
        plugin.getServer().getPluginManager().registerEvents(
            new KnkWorldsListener(() -> readiness, uuid -> Bukkit.getOfflinePlayer(uuid).isOp()), plugin);
        return readiness;
    }

    public WorldReadiness readiness() {
        return readiness;
    }

    private static Map<String, Object> toMap(ConfigurationSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            map.put(key, value instanceof ConfigurationSection child ? toMap(child) : value);
        }
        return map;
    }
}
