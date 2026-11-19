package net.knightsandkings.knk.paper.items.identity;

import net.knightsandkings.knk.core.items.identity.ItemIdentityFacts;
import net.knightsandkings.knk.paper.mapper.BlueprintTag;
import net.knightsandkings.knk.paper.mapper.ItemInstanceTag;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Item identity (#141): an {@link ItemStack} → the Bukkit-free {@link ItemIdentityFacts} the resolver reads. */
public final class ItemIdentityFactsReader {

    private ItemIdentityFactsReader() {
    }

    /** Null for null or air. */
    public static ItemIdentityFacts read(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        String materialKey = item.getType().getKey().toString();
        if (!item.hasItemMeta()) {
            return ItemIdentityFacts.plain(materialKey);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return ItemIdentityFacts.plain(materialKey);
        }
        boolean otherData = false;
        for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
            if (!key.equals(BlueprintTag.BLUEPRINT_KEY) && !key.equals(ItemInstanceTag.INSTANCE_KEY)) {
                otherData = true;
                break;
            }
        }
        return new ItemIdentityFacts(
                materialKey,
                ItemInstanceTag.read(meta).orElse(null),
                BlueprintTag.read(meta).orElse(null),
                meta.hasDisplayName(),
                meta.hasLore(),
                otherData,
                BlueprintTag.isPlainWithoutBlueprintTag(item));
    }
}
