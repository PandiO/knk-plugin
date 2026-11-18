package net.knightsandkings.knk.paper.navigation;

import java.util.List;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.knightsandkings.knk.core.navigation.compass.CompassItemRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** {@link CompassItems} on Bukkit: a plain item with the rule's material and display name, and a how-to lore. */
public final class BukkitCompassItems implements CompassItems {

    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();

    @Override
    public String nameOf(CompassItemRule rule) {
        String name = rule.displayName().isBlank() ? CompassItemRule.DEFAULT_DISPLAY_NAME : rule.displayName();
        return PlainTextComponentSerializer.plainText().serialize(AMPERSAND.deserialize(name));
    }

    @Override
    public ItemStack create(CompassItemRule rule) {
        Material material = Material.matchMaterial(rule.material());
        if (material == null || material.isAir() || !material.isItem()) {
            return null;
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        String name = rule.displayName().isBlank() ? CompassItemRule.DEFAULT_DISPLAY_NAME : rule.displayName();
        Component display = AMPERSAND.deserialize(name);
        // a custom name is italic unless said otherwise; an item meant to look like a tool should not
        meta.displayName(display.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        meta.lore(List.of(
            Component.text("Right-click: next destination", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Shift-right-click: stop / resume", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        stack.setItemMeta(meta);
        // what we hand out must be what the rule recognises, or the player holds a dead item
        return rule.matches(material.name(), SECTION.serialize(meta.displayName())) ? stack : null;
    }

    @Override
    public boolean holds(Player player, CompassItemRule rule) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            String name = meta != null && meta.hasDisplayName() ? SECTION.serialize(meta.displayName()) : null;
            if (rule.matches(item.getType().name(), name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean give(Player player, ItemStack item) {
        return player.getInventory().addItem(item).isEmpty();
    }
}
