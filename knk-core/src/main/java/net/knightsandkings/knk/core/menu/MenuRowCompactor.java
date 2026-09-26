package net.knightsandkings.knk.core.menu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;

/**
 * Menu follow-up 2026-09-26 ("if there is little content the total menu size should change to
 * less rows"): the height of a {@link MenuGrowth#DYNAMIC} menu is decided per render. After every
 * section has been rendered, each row with nothing in it is removed - the bottom-most empty rows
 * first - until the menu is down to {@link RuntimeMenu#minHeight()}; rows below a removed row move
 * up. A section can keep its first {@link RuntimeMenuSection#minHeight()} rows even when they are
 * empty (a spacer, or room for an empty-state marker). {@link MenuGrowth#STATIC} menus never change
 * size. Bukkit-free: works on slot numbers only; knk-paper remaps its per-slot maps with
 * {@link Layout#remap} and sizes the Inventory to {@link Layout#totalSlots()}.
 */
public final class MenuRowCompactor {

    private MenuRowCompactor() {
    }

    /** The effective layout of one render: {@code newRowByOldRow[r]} is -1 for a removed row. */
    public record Layout(int height, int[] newRowByOldRow) {

        public int totalSlots() {
            return height * MenuSlotCalculator.MENU_WIDTH;
        }

        public boolean changed() {
            for (int row = 0; row < newRowByOldRow.length; row++) {
                if (newRowByOldRow[row] != row) {
                    return true;
                }
            }
            return false;
        }

        /** The slot {@code oldSlot} moves to, or -1 when its row was removed. */
        public int mapSlot(int oldSlot) {
            int row = oldSlot / MenuSlotCalculator.MENU_WIDTH;
            if (row < 0 || row >= newRowByOldRow.length || newRowByOldRow[row] < 0) {
                return -1;
            }
            return newRowByOldRow[row] * MenuSlotCalculator.MENU_WIDTH + oldSlot % MenuSlotCalculator.MENU_WIDTH;
        }

        /** Copies {@code source} into {@code target} with every key moved by {@link #mapSlot}; removed slots are dropped. */
        public <V> void remap(Map<Integer, V> source, BiConsumer<Integer, V> target) {
            source.forEach((slot, value) -> {
                int moved = mapSlot(slot);
                if (moved >= 0) {
                    target.accept(moved, value);
                }
            });
        }
    }

    /** No change: the menu's full height. */
    public static Layout identity(RuntimeMenu menu) {
        int[] rows = new int[menu.height()];
        for (int row = 0; row < rows.length; row++) {
            rows[row] = row;
        }
        return new Layout(menu.height(), rows);
    }

    /**
     * @param occupiedSlots   every slot something was rendered into (before any background fill)
     * @param visibleSections the sections that were rendered - a section hidden by permission
     *                        protects no rows
     */
    public static Layout compact(RuntimeMenu menu, Set<Integer> occupiedSlots, Collection<RuntimeMenuSection> visibleSections) {
        if (menu.growth() != MenuGrowth.DYNAMIC) {
            return identity(menu);
        }
        int height = menu.height();
        boolean[] keep = new boolean[height];
        for (int slot : occupiedSlots) {
            int row = slot / MenuSlotCalculator.MENU_WIDTH;
            if (row >= 0 && row < height) {
                keep[row] = true;
            }
        }
        for (RuntimeMenuSection section : visibleSections) {
            if (section.minHeight() <= 0) {
                continue;
            }
            TreeSet<Integer> sectionRows = new TreeSet<>();
            for (int slot : MenuSlotCalculator.calculateSlots(section.displaySlot(), menu.totalSlots(), section.width(),
                    section.height(), section.alignVertical(), section.alignHorizontal())) {
                sectionRows.add(slot / MenuSlotCalculator.MENU_WIDTH);
            }
            int kept = 0;
            for (int row : sectionRows) {
                if (kept++ >= section.minHeight()) {
                    break;
                }
                keep[row] = true;
            }
        }

        List<Integer> removable = new ArrayList<>();
        for (int row = height - 1; row >= 0; row--) {
            if (!keep[row]) {
                removable.add(row);
            }
        }
        int removeCount = Math.min(removable.size(), height - menu.minHeight());
        boolean[] removed = new boolean[height];
        for (int i = 0; i < removeCount; i++) {
            removed[removable.get(i)] = true;
        }

        int[] newRowByOldRow = new int[height];
        int next = 0;
        for (int row = 0; row < height; row++) {
            newRowByOldRow[row] = removed[row] ? -1 : next++;
        }
        return new Layout(next, newRowByOldRow);
    }
}
