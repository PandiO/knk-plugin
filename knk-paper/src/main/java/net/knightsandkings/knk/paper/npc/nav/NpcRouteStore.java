package net.knightsandkings.knk.paper.npc.nav;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import net.knightsandkings.knk.core.npc.nav.NavPoint;

/**
 * The test routes of {@code /knk npc nav} on disk, {@code plugins/<plugin>/npc-routes.yml}, so they survive a
 * restart: {@code current} is the route in use (saved whenever an end is set), named routes are saved and loaded
 * with {@code /knk npc nav route}. On first use the routes of the Citizens spike ({@code npcspike-routes.yml}) are
 * taken over, so the routes measured there can be walked again. Main thread; the file is tiny, written at once.
 *
 * <pre>
 * current: {from: {world, x, y, z}, to: {...}}
 * routes:
 *   south-gate: {from: ..., to: ...}
 * </pre>
 */
public final class NpcRouteStore {

    static final String FILE = "npc-routes.yml";
    static final String SPIKE_FILE = "npcspike-routes.yml";

    /** A route's two ends as feet positions; either may be null (not set). */
    public record Route(NavPoint from, NavPoint to) {
        public boolean complete() {
            return from != null && to != null;
        }
    }

    private final File file;
    private final Logger logger;
    private final YamlConfiguration yaml;

    public NpcRouteStore(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, FILE);
        this.logger = logger;
        File spike = new File(dataFolder, SPIKE_FILE);
        if (file.exists()) {
            this.yaml = YamlConfiguration.loadConfiguration(file);
        } else if (spike.exists()) {
            this.yaml = YamlConfiguration.loadConfiguration(spike);
            logger.info("[NPC] test routes taken over from " + SPIKE_FILE + ": " + names());
            save();
        } else {
            this.yaml = new YamlConfiguration();
        }
    }

    public Route current() {
        return read(yaml.getConfigurationSection("current"));
    }

    public void saveCurrent(Route route) {
        write("current", route);
    }

    public Optional<Route> named(String name) {
        ConfigurationSection section = yaml.getConfigurationSection("routes." + key(name));
        return section == null ? Optional.empty() : Optional.of(read(section));
    }

    public void saveNamed(String name, Route route) {
        write("routes." + key(name), route);
    }

    public boolean delete(String name) {
        if (!yaml.isConfigurationSection("routes." + key(name))) {
            return false;
        }
        yaml.set("routes." + key(name), null);
        save();
        return true;
    }

    public List<String> names() {
        ConfigurationSection routes = yaml.getConfigurationSection("routes");
        return routes == null ? List.of() : new ArrayList<>(routes.getKeys(false));
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }

    public static String describe(NavPoint p) {
        return p == null ? "unset" : String.format(Locale.ROOT, "%s %d %d %d", p.world(), p.blockX(), p.blockY(),
            p.blockZ());
    }

    private void write(String path, Route route) {
        yaml.set(path, null);
        writePoint(path + ".from", route.from());
        writePoint(path + ".to", route.to());
        save();
    }

    private void writePoint(String path, NavPoint p) {
        if (p == null) {
            return;
        }
        yaml.set(path + ".world", p.world());
        yaml.set(path + ".x", p.x());
        yaml.set(path + ".y", p.y());
        yaml.set(path + ".z", p.z());
    }

    private static Route read(ConfigurationSection section) {
        if (section == null) {
            return new Route(null, null);
        }
        return new Route(readPoint(section.getConfigurationSection("from")),
            readPoint(section.getConfigurationSection("to")));
    }

    private static NavPoint readPoint(ConfigurationSection section) {
        if (section == null || section.getString("world") == null) {
            return null;
        }
        return new NavPoint(section.getString("world"), section.getDouble("x"), section.getDouble("y"),
            section.getDouble("z"));
    }

    private void save() {
        try {
            file.getParentFile().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            logger.log(Level.WARNING, "[NPC] could not save " + file, e);
        }
    }
}
