package net.knightsandkings.knk.paper.mapper;

import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprintOrigin;
import net.knightsandkings.knk.paper.utils.DisplayTextFormatter;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
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

            String gradeLoreLine = buildGradeLoreLine(blueprint);
            if (gradeLoreLine != null) {
                lore.add(gradeLoreLine);
            }

            String originLoreLine = buildOriginLoreLine(blueprint);
            if (originLoreLine != null) {
                lore.add(originLoreLine);
            }

            if (!lore.isEmpty()) {
                meta.setLore(lore);
            }

            itemStack.setItemMeta(meta);
        }

        return itemStack;
    }

    private static List<String> buildLore(String description) {
        List<String> lore = new ArrayList<>();
        if (description == null || description.isBlank()) {
            return lore;
        }

        String[] lines = description.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line != null ? line.trim() : "";
            if (!trimmed.isEmpty()) {
                lore.add(DisplayTextFormatter.translateToLegacy(trimmed));
            }
        }

        return lore;
    }

    /**
     * Revives v1's getGradeLore ("§l§bGrade: ★★★") star-lore mechanic (docs/specs/legacy/items.md;
     * docs/specs/items/IMPLEMENTATION_PLAN.md §6/§7 open question 5, decided: include in Phase 1).
     * Returns null when the blueprint has no Grade or a non-positive star count, so nothing is appended.
     */
    private static String buildGradeLoreLine(KnkItemBlueprint blueprint) {
        if (blueprint.grade() == null || blueprint.grade().stars() == null || blueprint.grade().stars() <= 0) {
            return null;
        }

        String stars = "★".repeat(blueprint.grade().stars());
        return DisplayTextFormatter.translateToLegacy("&l&bGrade: " + stars);
    }

    /**
     * Renders the item's production/procurement origin (sequenceNumber 0 - the only entry
     * actually populated in practice, per docs/specs/items/IMPLEMENTATION_PLAN.md §3.2) into
     * lore, matching the same "worth doing since the data will be there anyway" precedent Phase 1
     * already set for Grade's star-lore. This is vision.md §9.1's ItemInstance-era "lore is
     * regenerated display output (soulbound, ghosted, grade stars, origin, enchantments...)"
     * design, applied early at the ItemBlueprint/template level rather than waiting for
     * ItemInstance to exist - developer-confirmed (2026-09-23) as worth doing now rather than
     * deferring with the rest of that end-state. Returns null when the blueprint has no origins,
     * so nothing is appended.
     */
    private static String buildOriginLoreLine(KnkItemBlueprint blueprint) {
        if (blueprint.origins() == null || blueprint.origins().isEmpty()) {
            return null;
        }

        KnkItemBlueprintOrigin productionOrigin = blueprint.origins().stream()
                .min(Comparator.comparing(KnkItemBlueprintOrigin::sequenceNumber))
                .orElse(null);

        if (productionOrigin == null || productionOrigin.domainName() == null || productionOrigin.domainName().isBlank()) {
            return null;
        }

        String label = productionOrigin.domainType() != null && !productionOrigin.domainType().isBlank()
                ? productionOrigin.domainName() + " (" + productionOrigin.domainType() + ")"
                : productionOrigin.domainName();

        return DisplayTextFormatter.translateToLegacy("&7Origin: " + label);
    }
}
