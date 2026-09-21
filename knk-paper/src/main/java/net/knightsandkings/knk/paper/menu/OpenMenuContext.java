package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.Map;

/**
 * The Bukkit-facing half of one player's currently-open menu: the live
 * {@link Inventory} and the last-rendered slot -&gt; item snapshot click
 * handling routes against. Paired with, but deliberately separate from, the
 * Bukkit-free {@link net.knightsandkings.knk.core.menu.MenuSession} - see
 * that class's javadoc for why the split exists.
 * <p>
 * Mutable (not a record): {@link #update} is called on every re-render
 * (e.g. a page turn) so the click-routing snapshot always matches what's
 * actually in the Inventory right now.
 */
public final class OpenMenuContext {

    private final Player player;
    private final Inventory inventory;
    private volatile RuntimeMenu menu;
    private volatile Map<Integer, RuntimeMenuItem> itemsBySlot;

    public OpenMenuContext(Player player, Inventory inventory, RuntimeMenu menu, Map<Integer, RuntimeMenuItem> itemsBySlot) {
        this.player = player;
        this.inventory = inventory;
        this.menu = menu;
        this.itemsBySlot = itemsBySlot;
    }

    public Player player() {
        return player;
    }

    public Inventory inventory() {
        return inventory;
    }

    public RuntimeMenu menu() {
        return menu;
    }

    public Map<Integer, RuntimeMenuItem> itemsBySlot() {
        return itemsBySlot;
    }

    /** Called after re-rendering into the same, already-open Inventory (e.g. a page turn). */
    public void update(RuntimeMenu menu, Map<Integer, RuntimeMenuItem> itemsBySlot) {
        this.menu = menu;
        this.itemsBySlot = itemsBySlot;
    }
}
