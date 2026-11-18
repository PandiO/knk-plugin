package net.knightsandkings.knk.core.menu.opener;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where the menu item goes in a player's inventory (PandiO/knk-workspace#160), Bukkit-free. The caller classifies
 * every slot of {@code PlayerInventory#getContents()} (0-8 hotbar, 9-35 main inventory, 36-39 armour, 40 off-hand)
 * and applies the plan in this order: remove, refresh, move, place.
 * <ul>
 *   <li>Not wanted: every menu item is removed.</li>
 *   <li>Wanted: one stays (the one in the configured slot, else the first one found), every other copy is
 *       removed, and an outdated one (an older config's material, name or lore) is replaced in place. A
 *       {@code pinned} item (not movable) found in another slot is moved back to the configured slot.</li>
 *   <li>None there: it is placed in the configured slot. A player's own item in that slot moves to the first free
 *       slot of the main inventory (9-35), else of the hotbar; nothing of the player's is ever removed. With no free
 *       slot at all the item is not given ({@link Plan#noRoom()}).</li>
 * </ul>
 */
public final class MenuOpenerPlacement {

    private MenuOpenerPlacement() {
    }

    /** What a slot holds. */
    public enum Slot {
        EMPTY,
        /** Anything that is not the menu item. */
        OTHER,
        /** The menu item as the current settings make it. */
        OPENER,
        /** A menu item from older settings (other material, name or lore). */
        OPENER_OUTDATED;

        boolean isOpener() {
            return this == OPENER || this == OPENER_OUTDATED;
        }
    }

    /**
     * @param remove   slots to clear (extra or unwanted copies of the menu item)
     * @param refresh  slot whose outdated menu item is replaced by a current one, or -1
     * @param moveFrom slot of the player's item that makes room in the configured slot, or -1
     * @param moveTo   where that item goes, or -1
     * @param place    slot that gets a new menu item, or -1
     * @param noRoom   the item was wanted but the inventory has no free slot
     */
    public record Plan(List<Integer> remove, int refresh, int moveFrom, int moveTo, int place, boolean noRoom) {
        public Plan {
            remove = List.copyOf(remove);
        }

        public boolean changesNothing() {
            return remove.isEmpty() && refresh < 0 && moveFrom < 0 && place < 0;
        }
    }

    private static final int STORAGE_END = MenuOpenerSettings.MAX_SLOT;
    private static final int HOTBAR_END = 8;

    public static Plan plan(Slot[] slots, boolean wanted, int preferred, boolean pinned) {
        Objects.requireNonNull(slots, "slots");
        if (preferred < 0 || preferred > STORAGE_END || preferred >= slots.length) {
            throw new IllegalArgumentException("slot " + preferred + " is not a storage slot of this inventory");
        }
        List<Integer> openers = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && slots[i].isOpener()) {
                openers.add(i);
            }
        }
        List<Integer> remove = new ArrayList<>();
        if (!wanted) {
            return new Plan(openers, -1, -1, -1, -1, false);
        }
        int keep = openers.contains(preferred) ? preferred : openers.isEmpty() ? -1 : openers.get(0);
        if (keep >= 0 && pinned && keep != preferred) {
            keep = -1; // a pinned item belongs in the configured slot: take it away and place it there afresh
        }
        for (int i : openers) {
            if (i != keep) {
                remove.add(i);
            }
        }
        if (keep >= 0) {
            int refresh = slots[keep] == Slot.OPENER_OUTDATED ? keep : -1;
            return new Plan(remove, refresh, -1, -1, -1, false);
        }
        // the slots the removals free count as empty from here on
        Slot at = remove.contains(preferred) ? Slot.EMPTY : slotAt(slots, preferred);
        if (at == Slot.EMPTY) {
            return new Plan(remove, -1, -1, -1, preferred, false);
        }
        int free = firstFree(slots, remove, preferred);
        if (free < 0) {
            return new Plan(remove, -1, -1, -1, -1, true);
        }
        return new Plan(remove, -1, preferred, free, preferred, false);
    }

    private static Slot slotAt(Slot[] slots, int i) {
        return slots[i] == null ? Slot.EMPTY : slots[i];
    }

    /** The first free main-inventory slot (9-35), else hotbar slot (0-8), other than {@code except}; -1 for none. */
    private static int firstFree(Slot[] slots, List<Integer> freed, int except) {
        int end = Math.min(STORAGE_END, slots.length - 1);
        for (int i = HOTBAR_END + 1; i <= end; i++) {
            if (i != except && (slotAt(slots, i) == Slot.EMPTY || freed.contains(i))) {
                return i;
            }
        }
        for (int i = 0; i <= Math.min(HOTBAR_END, end); i++) {
            if (i != except && (slotAt(slots, i) == Slot.EMPTY || freed.contains(i))) {
                return i;
            }
        }
        return -1;
    }
}
