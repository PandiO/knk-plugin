package net.knightsandkings.knk.paper.menu.opener;

import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.knightsandkings.knk.core.menu.opener.MenuOpenerPlacement;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * {@link MenuOpenerItems} on Bukkit. The item is recognised by the PDC tag {@code knightsandkings:menu_opener},
 * whose value is the settings' {@linkplain MenuOpenerSettings#fingerprint() fingerprint}: a renamed or re-coloured
 * look in {@code config.yml} replaces the items players hold at their next join. Name and lore are display only;
 * an ordinary item with the same name is no menu item. Stack size 1, so copies never merge into one slot.
 */
public final class BukkitMenuOpenerItems implements MenuOpenerItems {

    /** Same namespace as the plugin's own keys ({@code new NamespacedKey(plugin, ...)}). */
    public static final NamespacedKey KEY = Objects.requireNonNull(NamespacedKey.fromString("knightsandkings:menu_opener"));

    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    @Override
    public ItemStack create(MenuOpenerSettings settings) {
        Material material = Material.matchMaterial(settings.material());
        if (material == null || material.isAir() || !material.isItem()) {
            return null;
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        meta.displayName(plain(AMPERSAND.deserialize(settings.name())));
        meta.lore(settings.lore().stream().map(line -> plain(AMPERSAND.deserialize(line))).toList());
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, settings.fingerprint());
        stack.setItemMeta(meta);
        return stack;
    }

    /** A custom name or lore line is italic unless said otherwise; this item should look like a button. */
    private static Component plain(Component text) {
        return text.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    @Override
    public boolean isOpener(ItemStack item) {
        return tag(item) != null;
    }

    @Override
    public MenuOpenerPlacement.Slot classify(ItemStack item, MenuOpenerSettings settings) {
        if (item == null || item.getType().isAir()) {
            return MenuOpenerPlacement.Slot.EMPTY;
        }
        String tag = tag(item);
        if (tag == null) {
            return MenuOpenerPlacement.Slot.OTHER;
        }
        return tag.equals(settings.fingerprint()) && item.getType().name().equals(settings.material())
            ? MenuOpenerPlacement.Slot.OPENER
            : MenuOpenerPlacement.Slot.OPENER_OUTDATED;
    }

    private static String tag(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
    }
}
