package net.knightsandkings.knk.paper.config;

import net.knightsandkings.knk.core.enchantbook.EnchantBookCapSettings;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.Map;

public class EnchantmentConfigManager {
    private static final String ROOT = "custom-enchantments";
    private static final String DEFAULT_COOLDOWN_MESSAGE = "&c%seconds% seconds remaining";
    private static final String BOOK_CAP_ROOT = "enchant-books.grade-cap";

    private final Plugin plugin;

    public EnchantmentConfigManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean disableForCreative() {
        FileConfiguration config = plugin.getConfig();
        if (config == null) {
            return false;
        }
        return config.getBoolean(ROOT + ".disable-for-creative", false);
    }

    public String cooldownMessageTemplate() {
        FileConfiguration config = plugin.getConfig();
        if (config == null) {
            return DEFAULT_COOLDOWN_MESSAGE;
        }

        String configured = config.getString(ROOT + ".cooldown-message", DEFAULT_COOLDOWN_MESSAGE);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_COOLDOWN_MESSAGE;
        }
        return configured;
    }

    public String getMessage(String key, String fallback) {
        FileConfiguration config = plugin.getConfig();
        if (config == null) {
            return fallback;
        }
        return config.getString(ROOT + "." + key, fallback);
    }

    public String getMessage(String key, String fallback, Map<String, String> placeholders) {
        String value = getMessage(key, fallback);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            value = value.replace(entry.getKey(), entry.getValue());
        }
        return value;
    }

    /** {@code enchant-books.grade-cap} (KNG-6); missing keys take {@link EnchantBookCapSettings#DEFAULTS}. */
    public EnchantBookCapSettings enchantBookCapSettings() {
        FileConfiguration config = plugin.getConfig();
        EnchantBookCapSettings d = EnchantBookCapSettings.DEFAULTS;
        if (config == null) {
            return d;
        }
        return new EnchantBookCapSettings(
                config.getBoolean(BOOK_CAP_ROOT + ".enabled", d.enabled()),
                config.getBoolean(BOOK_CAP_ROOT + ".apply-to-custom", d.applyToCustom()),
                config.getInt(BOOK_CAP_ROOT + ".ungraded-stars", d.ungradedStars()),
                config.getDouble(BOOK_CAP_ROOT + ".bonus-level-chance", d.bonusLevelChance())
        );
    }

    public void reload() {
        plugin.reloadConfig();
    }
}