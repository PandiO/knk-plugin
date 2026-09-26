package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.paper.menu.MenuFeature;
import net.knightsandkings.knk.paper.menu.MenuFeatureRegistries;

/**
 * Content port CP1 ({@code docs/specs/inventory-menu/CONTENT_PORT_PLAN.md} §3): the hub
 * ({@code main}, opened by {@code /menu}). Every hub tile is built from engine pieces only - the
 * viewer's head uses the engine {@code player} root, each tile opens its menu with
 * {@code menu.open} and is shown by a {@code menu-available} Render condition - so this feature
 * registers nothing. It exists so the hub has the same "one {@link MenuFeature} per feature"
 * shape as the rest of the content port, and owns the hub's template key.
 */
public final class HubMenuFeature implements MenuFeature {

    /** Template key of the hub (knk-web-api {@code MenuTemplateSeed.Content.cs}). */
    public static final String HUB_KEY = "main";

    @Override
    public void registerMenuHandlers(MenuFeatureRegistries registries) {
        // Intentionally empty - see the class javadoc.
    }
}
