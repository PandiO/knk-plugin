package net.knightsandkings.knk.paper.mapper;

import net.knightsandkings.knk.core.util.LoreWrap;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Objects;

/**
 * The blueprint description as its author wrote it, for an item whose description lore was wrapped
 * (PandiO/knk-workspace#212 + #40): a PDC tag {@code knightsandkings:knk_description} holding the description's
 * lore lines before {@link LoreWrap} (newline-separated), stamped by {@link ItemBlueprintBukkitMapper#fromBlueprint}.
 * An item scan uses it to offer exactly the authored lines as the Display description instead of the wrapped ones.
 * <p>
 * Stamped only when the wrap changed something, so an item whose lore isn't wrapped carries no extra tag (and
 * stacks with copies built before the tag existed).
 */
public final class ItemDescriptionTag {

    /** Same namespace {@code new NamespacedKey(plugin, ...)} gives this plugin ("KnightsAndKings"). */
    public static final NamespacedKey DESCRIPTION_KEY =
            Objects.requireNonNull(NamespacedKey.fromString("knightsandkings:knk_description"));

    private ItemDescriptionTag() {
    }

    /** Stamps {@code authored} on {@code meta} when it differs from the {@code wrapped} lines the item shows. */
    public static void stamp(ItemMeta meta, List<String> authored, List<String> wrapped) {
        if (meta == null || authored == null || authored.isEmpty() || authored.equals(wrapped)) {
            return;
        }
        meta.getPersistentDataContainer().set(DESCRIPTION_KEY, PersistentDataType.STRING, String.join("\n", authored));
    }

    /** The authored description lines, or an empty list when the item has no tag. */
    public static List<String> read(ItemMeta meta) {
        if (meta == null) {
            return List.of();
        }
        String value = meta.getPersistentDataContainer().get(DESCRIPTION_KEY, PersistentDataType.STRING);
        return value == null || value.isEmpty() ? List.of() : List.of(value.split("\n", -1));
    }
}
