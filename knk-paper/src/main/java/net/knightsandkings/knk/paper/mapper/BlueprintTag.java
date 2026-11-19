package net.knightsandkings.knk.paper.mapper;

import net.knightsandkings.knk.core.items.identity.ItemIdentityResolver;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.Optional;

/**
 * Item identity (PandiO/knk-workspace#141, production DESIGN §8): the PDC tag {@code knightsandkings:knk_blueprint}
 * holding knk-web-api's {@code ItemBlueprint.Id} (an INTEGER). {@code BlueprintItemAssembler} stamps it on the items it
 * builds while {@code items.identity.enabled} is on ({@link #setStampingEnabled}), but only on items that differ from
 * the plain vanilla item: the plain item resolves through its material's vanilla match and stays untagged, so the tag
 * never splits a stack (the retag rule in {@link ItemIdentityResolver}). Unique items keep
 * {@link ItemInstanceTag} as well.
 */
public final class BlueprintTag {

    /** Same namespace as {@link ItemInstanceTag#INSTANCE_KEY}. */
    public static final NamespacedKey BLUEPRINT_KEY = Objects.requireNonNull(NamespacedKey.fromString("knightsandkings:knk_blueprint"));

    /** Off by default: with {@code items.identity.enabled: false} items are built exactly as before. */
    private static volatile boolean stampingEnabled;

    private BlueprintTag() {
    }

    public static boolean stampingEnabled() {
        return stampingEnabled;
    }

    /** Set by the plugin at enable/disable from {@code items.identity.enabled}. */
    public static void setStampingEnabled(boolean enabled) {
        stampingEnabled = enabled;
    }

    public static Optional<Integer> read(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        return read(item.getItemMeta());
    }

    public static Optional<Integer> read(ItemMeta meta) {
        if (meta == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(meta.getPersistentDataContainer().get(BLUEPRINT_KEY, PersistentDataType.INTEGER));
    }

    /**
     * Stamps {@code blueprintId} when stamping is on and the item is not the plain vanilla item; true when it stamped.
     * Nothing for a null item or id.
     */
    public static boolean stampIfEnabled(ItemStack item, Integer blueprintId) {
        if (!stampingEnabled || item == null || blueprintId == null || blueprintId <= 0 || item.getType().isAir()) {
            return false;
        }
        if (!ItemIdentityResolver.shouldStampBlueprintTag(isPlainWithoutBlueprintTag(item))) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        meta.getPersistentDataContainer().set(BLUEPRINT_KEY, PersistentDataType.INTEGER, blueprintId);
        item.setItemMeta(meta);
        return true;
    }

    /** Removes the tag (the retag rule's normalisation); true when there was one. */
    public static boolean remove(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(BLUEPRINT_KEY, PersistentDataType.INTEGER)) {
            return false;
        }
        meta.getPersistentDataContainer().remove(BLUEPRINT_KEY);
        item.setItemMeta(meta);
        return true;
    }

    /** Whether the item, without its blueprint tag, is exactly the plain vanilla item of its material (amount aside). */
    public static boolean isPlainWithoutBlueprintTag(ItemStack item) {
        if (item == null) {
            return false;
        }
        if (!item.hasItemMeta()) {
            return true;
        }
        ItemStack copy = item.clone();
        remove(copy);
        return copy.isSimilar(new ItemStack(copy.getType()));
    }
}
