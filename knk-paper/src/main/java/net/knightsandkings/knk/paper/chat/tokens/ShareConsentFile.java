package net.knightsandkings.knk.paper.chat.tokens;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;

import net.knightsandkings.knk.core.chat.tokens.ShareConsent;

/**
 * Keeps the "never ask" answers of the chat-token share prompt (PandiO/knk-workspace#233) in
 * {@code plugins/KnK/chat-token-consent.yml}: {@code <uuid>: [pvp_kills, deaths]}. Local to this server; a lost file
 * only means the prompt is shown again.
 */
public final class ShareConsentFile {

    private static final Logger LOGGER = Logger.getLogger(ShareConsentFile.class.getName());

    private final File file;
    private final ShareConsent consent;

    public ShareConsentFile(File dataFolder, ShareConsent consent) {
        this.file = new File(dataFolder, "chat-token-consent.yml");
        this.consent = consent;
    }

    public void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<UUID, List<String>> stored = new HashMap<>();
        for (String key : yaml.getKeys(false)) {
            try {
                stored.put(UUID.fromString(key), yaml.getStringList(key));
            } catch (IllegalArgumentException e) {
                LOGGER.warning("chat-token-consent.yml: skipping '" + key + "' (not a UUID)");
            }
        }
        consent.load(stored);
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        consent.snapshot().forEach((player, keys) -> yaml.set(player.toString(), keys.stream().sorted().toList()));
        try {
            yaml.save(file);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Couldn't save " + file.getName(), e);
        }
    }
}
