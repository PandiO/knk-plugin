package net.knightsandkings.knk.paper.npcspike;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * #243 spike (throwaway, never merged): the spike's route ends on disk, {@code plugins/<plugin>/npcspike-routes.yml},
 * so they survive a restart. {@code current} is the route in use (saved on every {@code from}/{@code to}); named
 * routes are saved and loaded with {@code /knk npcspike route}. Main thread; the file is tiny, written synchronously.
 *
 * <pre>
 * current: {from: {world, x, y, z}, to: {...}, subject: uuid}
 * routes:
 *   keep-gate: {from: ..., to: ..., subject: ...}
 * </pre>
 */
final class SpikeRouteStore {

    record Route(Location from, Location to, UUID subject) {
    }

    private final File file;
    private final Logger logger;
    private final YamlConfiguration yaml;

    SpikeRouteStore(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "npcspike-routes.yml");
        this.logger = logger;
        this.yaml = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    Route current() {
        return read(yaml.getConfigurationSection("current"));
    }

    void saveCurrent(Location from, Location to, UUID subject) {
        write("current", from, to, subject);
    }

    Optional<Route> named(String name) {
        ConfigurationSection section = yaml.getConfigurationSection("routes." + key(name));
        return section == null ? Optional.empty() : Optional.of(read(section));
    }

    void saveNamed(String name, Location from, Location to, UUID subject) {
        write("routes." + key(name), from, to, subject);
    }

    boolean delete(String name) {
        if (!yaml.isConfigurationSection("routes." + key(name))) {
            return false;
        }
        yaml.set("routes." + key(name), null);
        save();
        return true;
    }

    List<String> names() {
        ConfigurationSection routes = yaml.getConfigurationSection("routes");
        return routes == null ? List.of() : new ArrayList<>(routes.getKeys(false));
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }

    static String describe(Location l) {
        return l == null ? "unset" : String.format(Locale.ROOT, "%s %d %d %d",
            l.getWorld() == null ? "?" : l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    private void write(String path, Location from, Location to, UUID subject) {
        yaml.set(path, null);
        writePoint(path + ".from", from);
        writePoint(path + ".to", to);
        yaml.set(path + ".subject", subject == null ? null : subject.toString());
        save();
    }

    private void writePoint(String path, Location l) {
        if (l == null || l.getWorld() == null) {
            return;
        }
        yaml.set(path + ".world", l.getWorld().getName());
        yaml.set(path + ".x", l.getX());
        yaml.set(path + ".y", l.getY());
        yaml.set(path + ".z", l.getZ());
    }

    private Route read(ConfigurationSection section) {
        if (section == null) {
            return new Route(null, null, null);
        }
        UUID subject = null;
        String uuid = section.getString("subject");
        if (uuid != null) {
            try {
                subject = UUID.fromString(uuid);
            } catch (IllegalArgumentException e) {
                logger.warning("[NpcSpike] bad subject in " + file.getName() + ": " + uuid);
            }
        }
        return new Route(readPoint(section.getConfigurationSection("from")),
            readPoint(section.getConfigurationSection("to")), subject);
    }

    private Location readPoint(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        World world = Bukkit.getWorld(section.getString("world", ""));
        if (world == null) {
            logger.warning("[NpcSpike] route point in unloaded or unknown world " + section.getString("world"));
            return null;
        }
        return new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"));
    }

    private void save() {
        try {
            file.getParentFile().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            logger.log(Level.WARNING, "[NpcSpike] could not save " + file, e);
        }
    }
}
