package net.knightsandkings.knk.paper.mapper;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.Optional;

/**
 * An item's identity (docs/specs/lootboxes/DESIGN.md §3.2/§3.4, vision §9.1): the PDC tag
 * {@code knightsandkings:knk_item_instance} holding knk-web-api's {@code ItemInstance.Id} (a LONG). Stamped on every
 * non-stackable lootbox item; stackable ones get none so they still stack with ordinary items of their blueprint.
 * Lore stays display only; two items with the same id are a provable dupe.
 */
public final class ItemInstanceTag {

    /** Same namespace as {@link ItemGradeTag#GRADE_KEY}. */
    public static final NamespacedKey INSTANCE_KEY = Objects.requireNonNull(NamespacedKey.fromString("knightsandkings:knk_item_instance"));

    private ItemInstanceTag() {
    }

    /** Stamps {@code instanceId} on {@code meta}; nothing for a null meta or id. */
    public static void stamp(ItemMeta meta, Long instanceId) {
        if (meta == null || instanceId == null) {
            return;
        }
        meta.getPersistentDataContainer().set(INSTANCE_KEY, PersistentDataType.LONG, instanceId);
    }

    /** The item's instance id, or empty for an item without one (vanilla, stackable, or from another source). */
    public static Optional<Long> read(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        return read(item.getItemMeta());
    }

    public static Optional<Long> read(ItemMeta meta) {
        if (meta == null) {
            return Optional.empty();
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        return Optional.ofNullable(container.get(INSTANCE_KEY, PersistentDataType.LONG));
    }

    /** Whether any of {@code items} carries {@code instanceId} (the redelivery dedupe scan). */
    public static boolean containsInstance(ItemStack[] items, long instanceId) {
        if (items == null) {
            return false;
        }
        for (ItemStack item : items) {
            if (read(item).filter(id -> id == instanceId).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
