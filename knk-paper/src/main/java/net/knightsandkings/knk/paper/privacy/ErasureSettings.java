package net.knightsandkings.knk.paper.privacy;

import java.time.Duration;

import org.bukkit.configuration.ConfigurationSection;

/**
 * {@code config.yml} {@code realtime.erasure.*} (PandiO/knk-workspace#184). Only used with {@code realtime.enabled: true};
 * missing keys fall back to the defaults.
 *
 * @param kickMessage   what an online player sees when their account is erased (neutral: no reason, no names)
 * @param settle        wait between the kick and the deletion, so the quit handlers have run and Minecraft has written
 *                      the player's own files before they are listed (0-30 s, default 2)
 * @param feedFile      where the handled erasure position is kept, in the plugin's data folder
 * @param minecraftList where the Minecraft player files of erased accounts are listed for the owner, in the data folder
 */
public record ErasureSettings(String kickMessage, Duration settle, String feedFile, String minecraftList) {

    public static final String SECTION = "realtime.erasure";
    public static final String DEFAULT_KICK_MESSAGE = "Your account data has been deleted.";
    public static final String DEFAULT_FEED_FILE = "erasure-feed.json";
    public static final String DEFAULT_MINECRAFT_LIST = "erasure-minecraft-files.txt";

    public ErasureSettings {
        kickMessage = kickMessage == null || kickMessage.isBlank() ? DEFAULT_KICK_MESSAGE : kickMessage;
        settle = settle == null || settle.isNegative() ? Duration.ofSeconds(2)
            : (settle.compareTo(Duration.ofSeconds(30)) > 0 ? Duration.ofSeconds(30) : settle);
        feedFile = feedFile == null || feedFile.isBlank() ? DEFAULT_FEED_FILE : feedFile.trim();
        minecraftList = minecraftList == null || minecraftList.isBlank() ? DEFAULT_MINECRAFT_LIST : minecraftList.trim();
    }

    public static ErasureSettings defaults() {
        return new ErasureSettings(DEFAULT_KICK_MESSAGE, Duration.ofSeconds(2), DEFAULT_FEED_FILE, DEFAULT_MINECRAFT_LIST);
    }

    /** Reads {@code realtime.erasure} from the plugin's root config; null or missing gives the defaults. */
    public static ErasureSettings fromConfig(ConfigurationSection root) {
        ErasureSettings d = defaults();
        ConfigurationSection s = root == null ? null : root.getConfigurationSection(SECTION);
        if (s == null) {
            return d;
        }
        return new ErasureSettings(
            s.getString("kick-message", d.kickMessage()),
            Duration.ofMillis(Math.round(s.getDouble("settle-seconds", d.settle().toMillis() / 1000.0) * 1000)),
            s.getString("feed-file", d.feedFile()),
            s.getString("minecraft-files-list", d.minecraftList()));
    }
}
