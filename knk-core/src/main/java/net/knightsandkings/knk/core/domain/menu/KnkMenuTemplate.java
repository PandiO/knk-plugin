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
 * <p>
 * {@code autoRefreshTicks} (InventoryMenu Phase 9, E4): re-render open instances
 * every N ticks; null/0 = off.
 */
public record KnkMenuTemplate(
        Integer id,
        String key,
        String name,
        String description,
        Integer height,
        String growth,
        Integer backgroundMaterialRefId,
        List<KnkMenuSectionTemplate> sections,
        Integer autoRefreshTicks,
        // Menu follow-up 2026-09-26: Dynamic menus' minimum rows; background filler by material name.
        Integer minHeight,
        String backgroundMaterial
) {

    /** Phase 9 shape (no minHeight/backgroundMaterial). */
    public KnkMenuTemplate(Integer id, String key, String name, String description, Integer height, String growth,
                           Integer backgroundMaterialRefId, List<KnkMenuSectionTemplate> sections, Integer autoRefreshTicks) {
        this(id, key, name, description, height, growth, backgroundMaterialRefId, sections, autoRefreshTicks, null, null);
    }

    /**
     * Pre-Phase-9 shape (no {@code autoRefreshTicks}) - kept so existing
     * callers/tests don't all have to change for one optional field.
     */
    public KnkMenuTemplate(Integer id, String key, String name, String description, Integer height, String growth,
                           Integer backgroundMaterialRefId, List<KnkMenuSectionTemplate> sections) {
        this(id, key, name, description, height, growth, backgroundMaterialRefId, sections, null);
    }
}
