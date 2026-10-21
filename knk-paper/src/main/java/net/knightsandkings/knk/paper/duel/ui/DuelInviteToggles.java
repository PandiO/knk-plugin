package net.knightsandkings.knk.paper.duel.ui;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code /duel toggle}, persisted per player (arena duels DESIGN §6.1; P3: "the runtime persists the flag and sets it
 * again on join"): the players who refuse every duel invite, in {@code duel-toggles.yml}. Written through a temp file
 * and an atomic move. Main thread.
 */
public final class DuelInviteToggles {

    private final File file;
    private final Logger logger;
    private final Set<UUID> disabled = new HashSet<>();

    public DuelInviteToggles(File file, Logger logger) {
        this.file = Objects.requireNonNull(file, "file");
        this.logger = Objects.requireNonNull(logger, "logger");
        load();
    }

    public boolean isDisabled(UUID player) {
        return disabled.contains(player);
    }

    /** Flips the player's flag and saves; returns the new "invites off" value. */
    public boolean toggle(UUID player) {
        boolean off = !disabled.remove(player);
        if (off) disabled.add(player);
        save();
        return off;
    }

    public Set<UUID> disabled() {
        return Set.copyOf(disabled);
    }

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String id : yaml.getStringList("invites-off")) {
            try {
                disabled.add(UUID.fromString(id));
            } catch (IllegalArgumentException bad) {
                logger.warning("[Duel] Ignoring a bad player id in " + file.getName() + ": " + id);
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("invites-off", List.copyOf(disabled.stream().map(UUID::toString).sorted().toList()));
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null) Files.createDirectories(parent.toPath());
            File temp = new File(parent, file.getName() + ".tmp");
            yaml.save(temp);
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.log(Level.WARNING, "[Duel] Could not save " + file.getName(), e);
        }
    }
}
