package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rendered-instance side of {@code KnkMenuSectionTemplate} (FR-2.1.2).
 * <p>
 * {@link #resolveSlots} is the base-class implementation of overflow/
 * pagination that reconciliation gap #9 requires: v2's bug was a no-op
 * {@code Menu.nextPage}/{@code prevPage} stub on the base class that only
 * some subclasses bothered to override. Here there simply is no subclass to
 * forget - every section, whatever its {@code kind}, is this one concrete
 * class, so a working base-class implementation is a working implementation
 * for every screen, by construction.
 */
public record RuntimeMenuSection(
        Integer id,
        String name,
        MenuSectionKind kind,
        int sortOrder,
        int displaySlot,
        int width,
        int height,
        MenuPositionMode positionMode,
        MenuAlignVertical alignVertical,
        MenuAlignHorizontal alignHorizontal,
        MenuOverflowMode overflow,
        MenuListMode listMode,
        MenuRenderPriority priority,
        String visibilityPermission,
        List<RuntimeMenuItem> items,
        List<KnkVariableBinding> variableBindings
) {

    /**
     * Computes which item occupies which absolute Menu slot for the requested
     * page, clamped to a valid page. Items with a non-null
     * {@link RuntimeMenuItem#slotOverride()} are always placed at that exact
     * slot on every page (they opt out of pagination entirely); every other
     * item fills the section's remaining calculated slots in {@code sortOrder}
     * order, one page's worth at a time.
     * <p>
     * {@link MenuOverflowMode#HIDE} truncates to a single page; SCROLL and
     * WRAP both paginate (see {@link MenuOverflowMode}'s javadoc for why the
     * two aren't distinguished at this level).
     */
    public SectionSlotAssignment resolveSlots(int menuTotalSlots, int requestedPage) {
        List<Integer> available = MenuSlotCalculator.calculateSlots(
                displaySlot, menuTotalSlots, width, height, alignVertical, alignHorizontal
        );

        List<RuntimeMenuItem> pinned = new ArrayList<>();
        List<RuntimeMenuItem> auto = new ArrayList<>();
        for (RuntimeMenuItem item : items) {
            if (item.slotOverride() != null) {
                pinned.add(item);
            } else {
                auto.add(item);
            }
        }

        Map<Integer, RuntimeMenuItem> slotAssignments = new LinkedHashMap<>();
        Set<Integer> consumedByPinned = new HashSet<>();
        for (RuntimeMenuItem item : pinned) {
            int slot = item.slotOverride();
            if (slot < 0 || slot >= menuTotalSlots) {
                // Out-of-bounds slotOverride values are rejected loudly at
                // assembly time by MenuLayoutValidator; defensively skip here
                // rather than let a bad slot corrupt the Inventory.
                continue;
            }
            slotAssignments.put(slot, item);
            consumedByPinned.add(slot);
        }

        List<Integer> pool = new ArrayList<>(available.size());
        for (Integer slot : available) {
            if (!consumedByPinned.contains(slot)) {
                pool.add(slot);
            }
        }

        int capacity = pool.size();
        int totalPages;
        int page;
        if (auto.isEmpty() || capacity == 0) {
            totalPages = 0;
            page = 0;
        } else if (overflow == MenuOverflowMode.HIDE) {
            totalPages = 1;
            page = 0;
        } else {
            totalPages = (int) Math.ceil(auto.size() / (double) capacity);
            page = Math.max(0, Math.min(requestedPage, totalPages - 1));
        }

        if (totalPages > 0) {
            int startIndex = page * capacity;
            int endIndex = Math.min(startIndex + capacity, auto.size());
            for (int i = startIndex; i < endIndex; i++) {
                slotAssignments.put(pool.get(i - startIndex), auto.get(i));
            }
        }

        boolean hasNext = totalPages > 0 && page < totalPages - 1;
        boolean hasPrev = totalPages > 0 && page > 0;

        return new SectionSlotAssignment(Map.copyOf(slotAssignments), page, totalPages, hasNext, hasPrev);
    }
}
