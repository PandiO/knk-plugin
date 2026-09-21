package net.knightsandkings.knk.paper.mapper;

import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.paper.utils.DisplayTextFormatter;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class ItemBlueprintBukkitMapper {

    private ItemBlueprintBukkitMapper() {
    }

    public static ItemStack fromBlueprint(KnkItemBlueprint blueprint, String materialNamespaceKey) {
        if (blueprint == null) {
            throw new IllegalArgumentException("blueprint must not be null");
        }

        Material material = MaterialNamespaceResolver.resolve(materialNamespaceKey);
        if (material == null) {
            throw new IllegalArgumentException("Unknown material namespace key: " + materialNamespaceKey);
        }

        ItemStack itemStack = new ItemStack(material);
        int requestedAmount = blueprint.defaultQuantity() != null ? blueprint.defaultQuantity() : 1;
        itemStack.setAmount(Math.max(1, Math.min(requestedAmount, itemStack.getMaxStackSize())));

        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            if (blueprint.defaultDisplayName() != null && !blueprint.defaultDisplayName().isBlank()) {
                meta.setDisplayName(DisplayTextFormatter.translateToLegacy(blueprint.defaultDisplayName()));
            }

            List<String> lore = buildLore(blueprint.defaultDisplayDescription());
            if (!lore.isEmpty()) {
                meta.setLore(lore);
            }

            itemStack.setItemMeta(meta);
        }

        return itemStack;
    }

    private static List<String> buildLore(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }

        String[] lines = description.split("\\r?\\n");
        List<String> lore = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line != null ? line.trim() : "";
            if (!trimmed.isEmpty()) {
                lore.add(DisplayTextFormatter.translateToLegacy(trimmed));
            }
        }

        return lore;
    }
}
