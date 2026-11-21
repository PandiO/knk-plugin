package net.knightsandkings.knk.core.production.shop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a player picked in the sell menu (PandiO/knk-workspace#687, shops DESIGN § 8 "put items in, see the buy-back price
 * per item and the total, confirm"): up to {@link #CAPACITY} picks (the legacy sell menu's drop zone, slots 19 to 25),
 * each a stack of the player's inventory. The sale sends one line per blueprint (picks of the same blueprint added up)
 * and one per unique item, so a blueprint is never on two lines.
 */
public final class SellBasket {

    /** The legacy drop zone holds seven stacks. */
    public static final int CAPACITY = 7;

    /** One pick: the inventory slot it came from, what it resolved to and how many. */
    public record Pick(int inventorySlot, Integer blueprintId, Long instanceId, long amount, String name) {
        public boolean unique() {
            return instanceId != null;
        }
    }

    private final List<Pick> picks = new ArrayList<>();

    public List<Pick> picks() {
        return List.copyOf(picks);
    }

    public int size() {
        return picks.size();
    }

    public boolean isEmpty() {
        return picks.isEmpty();
    }

    public boolean full() {
        return picks.size() >= CAPACITY;
    }

    public boolean holdsSlot(int inventorySlot) {
        for (Pick pick : picks) {
            if (pick.inventorySlot() == inventorySlot) {
                return true;
            }
        }
        return false;
    }

    /** Adds a pick; false when the basket is full or the slot is already in it. */
    public boolean add(Pick pick) {
        if (pick == null || full() || holdsSlot(pick.inventorySlot()) || pick.amount() <= 0) {
            return false;
        }
        if (pick.blueprintId() == null && pick.instanceId() == null) {
            return false;
        }
        picks.add(pick);
        return true;
    }

    /** Removes the pick at {@code index} (its place in the drop zone); null when there is none. */
    public Pick remove(int index) {
        return index >= 0 && index < picks.size() ? picks.remove(index) : null;
    }

    public void clear() {
        picks.clear();
    }

    /** The sale's lines: one per blueprint (amounts added up, first pick's order) and one per unique item. */
    public List<ShopSellLine> lines() {
        Map<String, ShopSellLine> lines = new LinkedHashMap<>();
        for (Pick pick : picks) {
            if (pick.unique()) {
                lines.put("i" + pick.instanceId(), ShopSellLine.instance(pick.instanceId()));
            } else {
                String key = "b" + pick.blueprintId();
                ShopSellLine before = lines.get(key);
                lines.put(key, ShopSellLine.blueprint(pick.blueprintId(), (before == null ? 0 : before.amount()) + pick.amount()));
            }
        }
        return List.copyOf(lines.values());
    }

    /** The line number ({@code lineNo}, from 0) the pick at {@code index} is part of; -1 when there is none. */
    public int lineOf(int index) {
        if (index < 0 || index >= picks.size()) {
            return -1;
        }
        Pick wanted = picks.get(index);
        String wantedKey = wanted.unique() ? "i" + wanted.instanceId() : "b" + wanted.blueprintId();
        List<String> keys = new ArrayList<>();
        for (Pick pick : picks) {
            String key = pick.unique() ? "i" + pick.instanceId() : "b" + pick.blueprintId();
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys.indexOf(wantedKey);
    }
}
