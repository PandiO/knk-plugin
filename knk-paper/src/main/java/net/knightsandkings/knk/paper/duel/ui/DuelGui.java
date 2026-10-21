package net.knightsandkings.knk.paper.duel.ui;

import net.knightsandkings.knk.paper.mapper.ItemGradeTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * The Bukkit side of the duel menus (inventories and icons), so the editor, the offer view and the kit picker can be
 * tested without a server. {@link #paper()} is the real one.
 */
public interface DuelGui {

    Inventory create(InventoryHolder holder, int size, Component title);

    /** A button or card: {@code material} named {@code name} with {@code lore}. */
    ItemStack icon(Material material, Component name, List<Component> lore);

    /** A player head card. */
    ItemStack head(UUID player, Component name, List<Component> lore);

    /** A staked stack's icon: a copy with {@code extraLore} appended (the stack itself is never touched). */
    ItemStack stakeIcon(ItemStack copy, List<Component> extraLore);

    /** A stack for chat and lore: "Diamond Sword ★★★ x2". */
    String describe(ItemStack stack);

    /** The real one: Paper inventories and item meta. */
    static DuelGui paper() {
        return new DuelGui() {
            @Override
            public Inventory create(InventoryHolder holder, int size, Component title) {
                return Bukkit.createInventory(holder, size, title);
            }

            @Override
            public ItemStack icon(Material material, Component name, List<Component> lore) {
                ItemStack item = new ItemStack(material);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.displayName(plain(name));
                    meta.lore(lore.stream().map(DuelGui::plain).toList());
                    item.setItemMeta(meta);
                }
                return item;
            }

            @Override
            public ItemStack head(UUID player, Component name, List<Component> lore) {
                ItemStack item = icon(Material.PLAYER_HEAD, name, lore);
                if (item.getItemMeta() instanceof SkullMeta skull) {
                    skull.setOwningPlayer(Bukkit.getOfflinePlayer(player));
                    item.setItemMeta(skull);
                }
                return item;
            }

            @Override
            public ItemStack stakeIcon(ItemStack copy, List<Component> extraLore) {
                if (copy == null) return icon(Material.BARRIER, Component.text("Unknown item", NamedTextColor.GRAY), extraLore);
                ItemStack shown = copy.clone();
                ItemMeta meta = shown.getItemMeta();
                if (meta != null) {
                    List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    lore.add(Component.empty());
                    extraLore.forEach(line -> lore.add(plain(line)));
                    meta.lore(lore);
                    shown.setItemMeta(meta);
                }
                return shown;
            }

            @Override
            public String describe(ItemStack stack) {
                if (stack == null) return "an unknown item";
                ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
                String name = meta != null && meta.hasDisplayName() && meta.displayName() != null
                        ? PlainTextComponentSerializer.plainText().serialize(Objects.requireNonNull(meta.displayName()))
                        : DuelGui.materialName(stack.getType().name());
                String stars = ItemGradeTag.stars(stack).map(n -> " " + "★".repeat(Math.max(0, n))).orElse("");
                return name + stars + (stack.getAmount() > 1 ? " x" + stack.getAmount() : "");
            }
        };
    }

    /** "DIAMOND_SWORD" → "Diamond Sword". */
    static String materialName(String material) {
        StringBuilder out = new StringBuilder();
        for (String word : material.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** Menu text without the default italic lore style. */
    private static Component plain(Component line) {
        return line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
