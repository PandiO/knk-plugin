package net.knightsandkings.knk.core.lootbox;

import java.util.List;

/**
 * The odds preview of one box grade ({@code GET api/LootboxTypes/{id}/odds}), trimmed to what {@code /lootbox odds}
 * shows. Every percentage is 0-100: item grades within the normal roll, items and specials of the whole box.
 */
public record KnkLootboxOdds(
        int lootboxTypeId,
        String lootboxTypeName,
        int boxStars,
        double normalRollPercent,
        List<Grade> itemGrades,
        List<Item> items,
        List<Special> specials
) {
    public KnkLootboxOdds {
        itemGrades = itemGrades == null ? List.of() : List.copyOf(itemGrades);
        items = items == null ? List.of() : List.copyOf(items);
        specials = specials == null ? List.of() : List.copyOf(specials);
    }

    public record Grade(String name, int stars, double percent, Integer itemCount) {
    }

    /** {@code itemBlueprintId} lets the opening reel show the real item; null from older APIs. */
    public record Item(String name, int stars, double percent, Integer itemBlueprintId) {
        public Item(String name, int stars, double percent) {
            this(name, stars, percent, null);
        }
    }

    public record Special(String name, double percent, Integer itemBlueprintId) {
        public Special(String name, double percent) {
            this(name, percent, null);
        }
    }
}
