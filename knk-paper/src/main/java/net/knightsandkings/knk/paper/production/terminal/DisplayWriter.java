package net.knightsandkings.knk.paper.production.terminal;

import java.util.List;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.knightsandkings.knk.core.production.terminal.DisplayLayout;
import net.knightsandkings.knk.core.production.terminal.DisplaySample;

/**
 * Fills a display container's own inventory with its sample (DESIGN § 6.2, one-way API → world), every stack tagged
 * {@code knightsandkings:knk_display_sample} so it is never mistaken for a real item. Main thread only.
 */
public final class DisplayWriter {

    public static final NamespacedKey SAMPLE_KEY = new NamespacedKey("knightsandkings", "knk_display_sample");

    private DisplayWriter() {
    }

    /** Replaces the inventory's contents with the sample, full stacks of each item. Returns the stacks written. */
    public static int write(Inventory inventory, DisplaySample sample, java.util.function.IntFunction<BlueprintItems.Template> items) {
        int size = inventory.getSize();
        List<Integer> slots = DisplayLayout.slots(sample, size);
        ItemStack[] contents = new ItemStack[size];
        int written = 0;
        for (int i = 0; i < size; i++) {
            Integer blueprintId = slots.get(i);
            if (blueprintId == null) {
                continue;
            }
            BlueprintItems.Template template = items.apply(blueprintId);
            if (template == null) {
                continue;
            }
            contents[i] = tag(template.copy(template.maxStack()));
            written++;
        }
        inventory.setContents(contents);
        return written;
    }

    static ItemStack tag(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(SAMPLE_KEY, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isSample(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(SAMPLE_KEY, PersistentDataType.BYTE);
    }
}
