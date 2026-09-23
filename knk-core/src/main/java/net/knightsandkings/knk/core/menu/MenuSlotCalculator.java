package net.knightsandkings.knk.core.menu;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, Bukkit-free static-grid slot calculation (FR-2.2.2 / ARCHITECTURE_DESIGN.md
 * §3.1's {@code MenuUtil.calculateSlots}), per DESIGN_REVIEW.md §1: explicit cell
 * placement from declared position/size/alignment, not a general flex-solving
 * algorithm. Kept out of the Model layer's Bukkit-facing pieces entirely
 * (DESIGN_REVIEW.md "Portability" section) so it can be unit tested directly.
 */
public final class MenuSlotCalculator {

    /** Minecraft inventories are always 9 slots wide. */
    public static final int MENU_WIDTH = 9;

    private MenuSlotCalculator() {
    }

    /**
     * Computes the ordered (top-to-bottom, left-to-right) list of absolute
     * inventory slots a section of the given size occupies, anchored at
     * {@code displaySlot} and biased by alignment. {@code displaySlot} is
     * converted to (x, y) via {@code x = displaySlot % 9}, {@code y = displaySlot / 9}.
     * <p>
     * Any computed cell that would fall outside the menu's own bounds (negative
     * X, X &gt;= 9, or the resulting slot outside {@code [0, menuTotalSlots)})
     * is silently dropped rather than wrapping or throwing - callers that need
     * to know whether a section fully fit should compare the result size
     * against {@code width * height} (see {@link #fitsExactly}).
     *
     * @return an immutable list of distinct slots in deterministic fill order;
     *         never contains a slot outside {@code [0, menuTotalSlots)}
     */
    public static List<Integer> calculateSlots(
            int displaySlot,
            int menuTotalSlots,
            int width,
            int height,
            MenuAlignVertical alignVertical,
            MenuAlignHorizontal alignHorizontal
    ) {
        if (width < 1 || height < 1 || menuTotalSlots < 1) {
            return List.of();
        }

        int startX = Math.floorMod(displaySlot, MENU_WIDTH);
        int startY = Math.floorDiv(displaySlot, MENU_WIDTH);

        int baseX = switch (alignHorizontal) {
            case LEFT -> startX;
            case RIGHT -> startX - width + 1;
            case CENTER -> startX - (width / 2);
        };
        int baseY = switch (alignVertical) {
            case TOP -> startY;
            case BOTTOM -> startY - height + 1;
            case CENTER -> startY - (height / 2);
        };

        List<Integer> slots = new ArrayList<>(width * height);
        for (int row = 0; row < height; row++) {
            int y = baseY + row;
            if (y < 0) {
                continue;
            }

            for (int col = 0; col < width; col++) {
                int x = baseX + col;
                if (x < 0 || x >= MENU_WIDTH) {
                    continue;
                }

                int slot = y * MENU_WIDTH + x;
                if (slot < 0 || slot >= menuTotalSlots) {
                    continue;
                }

                slots.add(slot);
            }
        }

        return List.copyOf(slots);
    }

    /**
     * Whether every one of the {@code width * height} cells a section declares
     * actually landed inside the menu's bounds - i.e. nothing was clipped by
     * {@link #calculateSlots}. Used by {@link MenuLayoutValidator} to reject
     * out-of-bounds section configurations loudly (reconciliation gap #8)
     * instead of silently rendering a partial section.
     */
    public static boolean fitsExactly(
            int displaySlot,
            int menuTotalSlots,
            int width,
            int height,
            MenuAlignVertical alignVertical,
            MenuAlignHorizontal alignHorizontal
    ) {
        if (width < 1 || height < 1) {
            return false;
        }
        int computed = calculateSlots(displaySlot, menuTotalSlots, width, height, alignVertical, alignHorizontal).size();
        return computed == width * height;
    }
}
