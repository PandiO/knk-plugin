package net.knightsandkings.knk.paper.mapper;

import net.knightsandkings.knk.core.domain.item.ItemLoreLayout;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprintOrigin;
import net.knightsandkings.knk.core.util.LoreWrap;
import net.knightsandkings.knk.paper.enchantbook.EnchantBookItems;
import net.knightsandkings.knk.paper.utils.DisplayTextFormatter;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ItemBlueprintBukkitMapper {

    /** Dark gray, the colour of an ordinary blueprint description. */
    static final String DESCRIPTION_COLOR = "&8";

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

            List<String> authoredDescription = descriptionLines(blueprint.defaultDisplayDescription());
            List<String> lore = new ArrayList<>(LoreWrap.wrapAll(authoredDescription));
            // #212/#40: when the wrap changed the description, keep the authored lines for an item scan.
            ItemDescriptionTag.stamp(meta, authoredDescription, lore);

            String gradeLoreLine = buildGradeLoreLine(blueprint);
            if (gradeLoreLine != null) {
                lore.add(gradeLoreLine);
            }

            String originLoreLine = buildOriginLoreLine(blueprint);
            if (originLoreLine != null) {
                lore.addAll(LoreWrap.wrap(originLoreLine));
            }

            lore = ItemLoreLayout.compose(List.of(), lore);

            if (!lore.isEmpty()) {
                meta.setLore(lore);
            }

            // Machine-readable grade for the enchant-book level cap (KNG-6); the star lore above is display only.
            ItemGradeTag.stamp(meta, blueprint);

            // #232: a shield's banner (base colour + layers) and an armour piece's trim, as the blueprint (or the
            // instance a lootbox claim carries) stores them. Nothing for other materials or a plain item.
            ItemAppearanceBukkitMapper.apply(meta, blueprint.shieldBanner(), blueprint.armorTrim(), "blueprint " + blueprint.id());

            itemStack.setItemMeta(meta);
        }

        // Permanent enchantment books (KNG-5): an enchanted_book blueprint with a default enchantment.
        if (EnchantBookItems.isBookBlueprint(blueprint, material)) {
            EnchantBookItems.decorate(itemStack, blueprint);
        }

        return itemStack;
    }

    /**
     * The description's lines as lore. A line without a color of its own is dark gray ({@code &8}): lore without one
     * is vanilla's purple, which no description should end up in by accident. A color the description sets itself
     * (also at the start of the line) overrides it. A line wider than {@code lore.max-width} visible characters
     * continues on the next lore line in the same colour ({@link LoreWrap}, PandiO/knk-workspace#212); an item scan
     * gives the authored lines back ({@link ItemDescriptionTag}, {@code ScannedItemJsonBuilder}'s {@code descriptionLore}).
     */
    static List<String> buildLore(String description) {
        return new ArrayList<>(LoreWrap.wrapAll(descriptionLines(description)));
    }

    /** The description's lines in their colour, before the lore wrap ({@link #buildLore} without the wrap). */
    static List<String> descriptionLines(String description) {
        List<String> lines = new ArrayList<>();
        if (description == null || description.isBlank()) {
            return lines;
        }
        for (String line : description.split("\\r?\\n")) {
            String trimmed = line != null ? line.trim() : "";
            if (!trimmed.isEmpty()) {
                lines.add(DisplayTextFormatter.translateToLegacy(DESCRIPTION_COLOR + trimmed));
            }
        }
        return lines;
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
