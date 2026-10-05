package net.knightsandkings.knk.core.lootbox;

import java.util.List;

/**
 * The odds preview of one box grade ({@code GET api/LootboxTypes/{id}/odds}), trimmed to what {@code /lootbox odds}
 * shows and to what the opening reel needs to dress its passing items like real drops. Every percentage is 0-100:
 * item grades within the normal roll, items and specials of the whole box.
 */
public record KnkLootboxOdds(
        int lootboxTypeId,
        String lootboxTypeName,
        int boxStars,
        double normalRollPercent,
        List<Grade> itemGrades,
        List<Item> items,
        List<Special> specials,
        List<Enchantment> enchantments
) {
    public KnkLootboxOdds {
        itemGrades = itemGrades == null ? List.of() : List.copyOf(itemGrades);
        items = items == null ? List.of() : List.copyOf(items);
        specials = specials == null ? List.of() : List.copyOf(specials);
        enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
    }

    public KnkLootboxOdds(int lootboxTypeId, String lootboxTypeName, int boxStars, double normalRollPercent,
                          List<Grade> itemGrades, List<Item> items, List<Special> specials) {
        this(lootboxTypeId, lootboxTypeName, boxStars, normalRollPercent, itemGrades, items, specials, List.of());
    }

    public record Grade(String name, int stars, double percent, Integer itemCount) {
    }

    /**
     * {@code itemBlueprintId} lets the opening reel show the real item; null from older APIs. {@code quantity} is the
     * stack size a claim gives (0 = unknown); {@code rollsEnchantments} is false for books and stackable items, which
     * never get rolled enchantments.
     */
    public record Item(String name, int stars, double percent, Integer itemBlueprintId, int quantity, boolean rollsEnchantments) {
        public Item(String name, int stars, double percent, Integer itemBlueprintId) {
            this(name, stars, percent, itemBlueprintId, 0, false);
        }

        public Item(String name, int stars, double percent) {
            this(name, stars, percent, null);
        }
    }

    public record Special(String name, double percent, Integer itemBlueprintId) {
        public Special(String name, double percent) {
            this(name, percent, null);
        }
    }

    /**
     * One enchant roll of the box: it {@code hitPercent} of the time (per enchantable item) gives a uniform level from
     * the range of the item's grade. A grade missing from {@code levelsByGrade}, or with no levels, never gets it.
     */
    public record Enchantment(int definitionId, String key, boolean isCustom, double hitPercent, List<LevelRange> levelsByGrade) {
        public Enchantment {
            levelsByGrade = levelsByGrade == null ? List.of() : List.copyOf(levelsByGrade);
        }
    }

    /** The levels a roll can give on item grade {@code stars} after the grade cap; null min/max = always dropped. */
    public record LevelRange(int stars, Integer minLevel, Integer maxLevel) {
    }
}
