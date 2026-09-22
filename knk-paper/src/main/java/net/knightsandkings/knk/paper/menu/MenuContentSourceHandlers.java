package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.menu.MenuContentSourceRegistry;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import net.knightsandkings.knk.paper.mapper.ItemBlueprintMenuMapper;

/**
 * Concrete {@code MenuContentSourceRegistry} handlers (IMPLEMENTATION_PLAN.md
 * Phase 8, mirroring {@code MenuActionHandlers}/{@code MenuConditionHandlers}'
 * shape). One real source ships with this phase: {@link #ITEM_BLUEPRINTS},
 * backed directly by the already-existing, already cache-first
 * {@link ItemBlueprintsDataAccess#searchAsync} - the real catalog/paged-search
 * gateway Items Phase 1 already built and this codebase already uses
 * elsewhere (e.g. {@code ItemBlueprintsDebugCommand}'s {@code /knk itemblueprints give}),
 * not a synthetic dataset invented for this phase. Every call issues a real
 * paged query (search text/page number threaded straight through from the
 * caller's {@code PagedQuery} - see {@code MenuRenderer}'s content-source
 * render path for where that's built) - never a full-list preload.
 */
public final class MenuContentSourceHandlers {

    public static final String ITEM_BLUEPRINTS = "catalog.itemblueprints";

    private MenuContentSourceHandlers() {
    }

    public static void registerDefaults(
            MenuContentSourceRegistry<MenuContentSourceContext> registry,
            ItemBlueprintsDataAccess itemBlueprintsDataAccess
    ) {
        registry.register(ITEM_BLUEPRINTS, (context, query) -> itemBlueprintsDataAccess.searchAsync(query)
                .thenApply(MenuContentSourceHandlers::toRuntimeMenuItemPage));
    }

    private static Page<RuntimeMenuItem> toRuntimeMenuItemPage(Page<net.knightsandkings.knk.core.domain.item.KnkItemBlueprint> page) {
        return new Page<>(
                page.items().stream().map(ItemBlueprintMenuMapper::toRuntimeMenuItem).toList(),
                page.totalCount(),
                page.pageNumber(),
                page.pageSize()
        );
    }
}
