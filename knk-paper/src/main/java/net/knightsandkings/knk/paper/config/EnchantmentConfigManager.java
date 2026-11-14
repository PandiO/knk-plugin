package net.knightsandkings.knk.paper.config;

import net.knightsandkings.knk.core.enchantbook.EnchantBookCapSettings;
import net.knightsandkings.knk.core.random.ChanceBag;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.Map;

public class EnchantmentConfigManager {
    private static final String ROOT = "custom-enchantments";
    private static final String DEFAULT_COOLDOWN_MESSAGE = "&c%seconds% seconds remaining";
    private static final String BOOK_CAP_ROOT = "enchant-books.grade-cap";
    private static final String FAIR_CHANCE_ROOT = ROOT + ".fair-chance";

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

    /**
     * {@code custom-enchantments.fair-chance.enabled} (PandiO/knk-workspace#476): whether on-hit chances draw from a
     * marble bag per player and enchantment instead of rolling independently. On when the key is missing.
     */
    public boolean fairChanceEnabled() {
        FileConfiguration config = plugin.getConfig();
        return config == null || config.getBoolean(FAIR_CHANCE_ROOT + ".enabled", true);
    }

    /** {@code custom-enchantments.fair-chance.min-bag-size} / {@code max-bag-size}; {@link ChanceBag.Size#DEFAULT} when missing. */
    public ChanceBag.Size fairChanceBagSize() {
        FileConfiguration config = plugin.getConfig();
        ChanceBag.Size d = ChanceBag.Size.DEFAULT;
        if (config == null) {
            return d;
        }
        return new ChanceBag.Size(
                config.getInt(FAIR_CHANCE_ROOT + ".min-bag-size", d.min()),
                config.getInt(FAIR_CHANCE_ROOT + ".max-bag-size", d.max())
        );
    }

    public void reload() {
        plugin.reloadConfig();
    }
}