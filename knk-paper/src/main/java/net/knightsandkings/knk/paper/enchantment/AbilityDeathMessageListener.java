package net.knightsandkings.knk.paper.enchantment;

import net.knightsandkings.knk.core.domain.enchantment.AbilityDeathMessages;
import net.knightsandkings.knk.core.domain.enchantment.AreaKillAttribution;
import net.knightsandkings.knk.core.domain.enchantment.Enchantment;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * PandiO/knk-workspace#222: a player killed by Chaos or Flash Chaos gets a death message that names the ability and
 * the player who used it and the item ({@code Steve was killed with Flash Chaos by Alex using [Storm Blade]}), instead of vanilla's melee
 * {@code was slain by}. Only a death inside the ability's own hit counts ({@link AreaKillAttribution}); every other
 * death keeps the vanilla message (developer decision 2026-11-03).
 * <p>
 * Runs at HIGH: a siege match clears the death message at HIGHEST ({@code SiegeDeathRespawnListener}) and still
 * does. A message another plugin or the game already removed stays removed. The kill itself is untouched: the
 * ability damages with the user as the source, so {@code getKiller()} and the statistics credit the user as before.
 * The patterns are read from {@code config.yml} per death, so {@code /ce reload} applies a change.
 */
public final class AbilityDeathMessageListener implements Listener {
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    static final String CONFIG_PATH = "custom-enchantments.death-messages";

    private final Supplier<ConfigurationSection> config;
    private final AreaKillAttribution attribution;

    public AbilityDeathMessageListener(Plugin plugin) {
        this(() -> plugin.getConfig().getConfigurationSection(CONFIG_PATH), AreaKillAttribution.getInstance());
    }

    AbilityDeathMessageListener(Supplier<ConfigurationSection> config, AreaKillAttribution attribution) {
        this.config = config;
        this.attribution = attribution;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        AreaKillAttribution.Hit hit = attribution.current(victim.getUniqueId()).orElse(null);
        if (hit == null || event.deathMessage() == null) {
            return;
        }
        ConfigurationSection section = config.get();
        if (section != null && !section.getBoolean("enabled", true)) {
            return;
        }
        String enchantmentName = EnchantmentRegistry.getInstance().getById(hit.enchantmentId())
                .map(Enchantment::displayName)
                .orElse(hit.enchantmentId());
        Component item = itemName(hit.item());
        String text = messages(section).format(hit.enchantmentId(), victim.getName(), hit.attackerName(), enchantmentName,
                item != null);
        event.deathMessage(message(text, item));
    }

    /**
     * The item as vanilla shows it in {@code using [Sword]}: its name in brackets, hovering shows the item; null for
     * no item or air.
     */
    static Component itemName(Object item) {
        if (!(item instanceof ItemStack stack) || stack.isEmpty()) {
            return null;
        }
        return stack.displayName();
    }

    /** The patterns of the {@code custom-enchantments.death-messages} section; the built-in default without one. */
    static AbilityDeathMessages messages(ConfigurationSection section) {
        if (section == null) {
            return new AbilityDeathMessages(null, null);
        }
        Map<String, String> overrides = new HashMap<>();
        ConfigurationSection perEnchantment = section.getConfigurationSection("overrides");
        if (perEnchantment != null) {
            for (String key : perEnchantment.getKeys(false)) {
                String pattern = perEnchantment.getString(key);
                if (pattern != null) {
                    overrides.put(key.toLowerCase(Locale.ROOT), pattern);
                }
            }
        }
        return new AbilityDeathMessages(section.getString("default"), overrides);
    }

    /** {@code &} colour codes allowed, as in the plugin's other messages; {@code %item%} becomes the item's name. */
    static Component message(String text, Component item) {
        Component message = AMPERSAND.deserialize(text);
        if (item == null) {
            return message;
        }
        return message.replaceText(TextReplacementConfig.builder()
                .matchLiteral(AbilityDeathMessages.ITEM_PLACEHOLDER)
                .replacement(item)
                .build());
    }
}
