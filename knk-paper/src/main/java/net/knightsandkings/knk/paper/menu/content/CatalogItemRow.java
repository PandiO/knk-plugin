package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.menu.MenuRowKey;
import net.knightsandkings.knk.paper.mapper.ItemBlueprintBukkitMapper;
import net.knightsandkings.knk.paper.menu.MenuItemStackRow;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One row of {@code items.catalog} (menu follow-up 2026-09-26): an item blueprint shown as a player
 * receives it - {@link ItemBlueprintBukkitMapper#fromBlueprint}, the base step of
 * {@code BlueprintItemAssembler}, which kit grants ({@code KitGrantPlacer}) and
 * {@code /knk itemblueprints give} use. The preview leaves off the default enchantments those add.
 * The ItemStack is built lazily on the main thread (render step 4) and kept for this row object.
 */
public final class CatalogItemRow implements MenuItemStackRow, MenuRowKey {

    private static final Logger LOGGER = Logger.getLogger(CatalogItemRow.class.getName());

    private final KnkItemBlueprint blueprint;
    private final String materialKey;
    private ItemStack built;
    private boolean attempted;

    CatalogItemRow(KnkItemBlueprint blueprint, String materialKey) {
        this.blueprint = blueprint;
        this.materialKey = materialKey;
    }

    @Override
    public ItemStack menuItemStack() {
        if (!attempted) {
            attempted = true;
            try {
                built = materialKey == null || materialKey.isBlank() ? null : ItemBlueprintBukkitMapper.fromBlueprint(blueprint, materialKey);
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, "items.catalog: blueprint " + blueprint.id() + " has no usable material (" + materialKey + ")", e);
                built = null;
            }
        }
        return built;
    }

    public int getBlueprintId() {
        return blueprint.id() != null ? blueprint.id() : 0;
    }

    /** Used only when the item can't be built (unknown material): the row template shows this name on PAPER. */
    public String getName() {
        String name = blueprint.defaultDisplayName() != null && !blueprint.defaultDisplayName().isBlank()
                ? blueprint.defaultDisplayName() : blueprint.name();
        return name != null ? name : "Blueprint #" + getBlueprintId();
    }

    @Override
    public Object menuRowKey() {
        return List.of(blueprint, String.valueOf(materialKey));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CatalogItemRow other && menuRowKey().equals(other.menuRowKey());
    }

    @Override
    public int hashCode() {
        return menuRowKey().hashCode();
    }

    @Override
    public String toString() {
        return "CatalogItemRow[" + getBlueprintId() + "]";
    }
}
