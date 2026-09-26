package net.knightsandkings.knk.paper.mapper;

import net.knightsandkings.knk.core.domain.item.GradeCatalog;
import net.knightsandkings.knk.core.domain.item.GradeLore;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Objects;
import java.util.Optional;

/**
 * The machine-readable grade of an item (Linear KNG-6, docs/specs/items/GRADE_DROPCHANCE.md §4): a PDC tag
 * {@code knightsandkings:knk_grade} holding the grade's <b>stars</b>, stamped by
 * {@link ItemBlueprintBukkitMapper#fromBlueprint}. It holds stars rather than the cap divisor so the divisor
 * is looked up live in {@link GradeCatalog} at click time: retuning a grade applies to existing items too.
 */
public final class ItemGradeTag {

    /** Same namespace {@code new NamespacedKey(plugin, ...)} gives this plugin ("KnightsAndKings"). */
    public static final NamespacedKey GRADE_KEY = Objects.requireNonNull(NamespacedKey.fromString("knightsandkings:knk_grade"));

    private ItemGradeTag() {
    }

    /** Stamps the blueprint's grade stars on {@code meta}; nothing when the blueprint has no (resolvable) grade. */
    public static void stamp(ItemMeta meta, KnkItemBlueprint blueprint) {
        if (meta == null || blueprint == null) {
            return;
        }
        GradeCatalog.getInstance().starsOf(blueprint.grade())
                .ifPresent(stars -> meta.getPersistentDataContainer().set(GRADE_KEY, PersistentDataType.INTEGER, stars));
    }

    /**
     * The item's grade stars: the tag, else the {@code Grade: ★★★} lore line older blueprint items carry,
     * else empty (ungraded: vanilla-crafted or not from a graded blueprint).
     */
    public static Optional<Integer> stars(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = item.getItemMeta();
        Integer tagged = meta.getPersistentDataContainer().get(GRADE_KEY, PersistentDataType.INTEGER);
        if (tagged != null && tagged > 0) {
            return Optional.of(tagged);
        }
        return GradeLore.starsFromLore(meta.hasLore() ? meta.getLore() : null);
    }
}
