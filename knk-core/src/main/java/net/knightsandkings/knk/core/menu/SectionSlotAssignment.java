package net.knightsandkings.knk.core.menu;

import java.util.Map;

/**
 * The result of {@link RuntimeMenuSection#resolveSlots}: which item occupies
 * which absolute Menu slot for one specific page, plus the paging metadata a
 * caller needs to render "page 2 of 3"-style navigation and to know whether
 * next/previous is currently valid.
 */
public record SectionSlotAssignment(
        Map<Integer, RuntimeMenuItem> itemsBySlot,
        int page,
        int totalPages,
        boolean hasNextPage,
        boolean hasPreviousPage
) {
}
