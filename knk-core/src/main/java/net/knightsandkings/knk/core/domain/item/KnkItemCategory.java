package net.knightsandkings.knk.core.domain.item;

/** An item category ({@code GET /api/Categories}); used by the item catalogue's category filter (menu follow-up 2026-09-26). */
public record KnkItemCategory(int id, String name, Integer parentCategoryId) {
}
