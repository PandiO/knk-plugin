package net.knightsandkings.knk.core.domain.menu;

import java.util.List;

/**
 * InventoryMenu template (docs/specs/inventory-menu/IMPLEMENTATION_PLAN.md
 * Phase 1): the root of the Menu -> MenuSection -> MenuItem composite tree,
 * as persisted by knk-web-api and consumed here through
 * {@link net.knightsandkings.knk.core.dataaccess.MenuTemplatesDataAccess}.
 * <p>
 * Enum-shaped fields (growth, kind, displayMode, etc.) are kept as plain
 * {@code String} rather than Java enums, matching {@code KnkItemBlueprint}'s
 * precedent - parsing into whatever internal representation the Phase 2
 * rendering engine needs is that engine's concern, not this data-access layer's.
 */
public record KnkMenuTemplate(
        Integer id,
        String key,
        String name,
        String description,
        Integer height,
        String growth,
        Integer backgroundMaterialRefId,
        List<KnkMenuSectionTemplate> sections
) {}
