package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.Map;

/**
 * The Bukkit-facing half of one player's currently-open menu: the live
 * {@link Inventory} and the last-rendered slot -&gt; item/section snapshot
 * click handling routes against. Paired with, but deliberately separate
 * from, the Bukkit-free {@link net.knightsandkings.knk.core.menu.MenuSession}
 * - see that class's javadoc for why the split exists.
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
    private volatile Map<Integer, RuntimeMenuSection> sectionsBySlot;
    private volatile Map<Integer, List<String>> controlHintLoreBySlot;

    public OpenMenuContext(Player player, Inventory inventory, RuntimeMenu menu,
                            Map<Integer, RuntimeMenuItem> itemsBySlot,
                            Map<Integer, RuntimeMenuSection> sectionsBySlot,
                            Map<Integer, List<String>> controlHintLoreBySlot) {
        this.player = player;
        this.inventory = inventory;
        this.menu = menu;
        this.itemsBySlot = itemsBySlot;
        this.sectionsBySlot = sectionsBySlot;
        this.controlHintLoreBySlot = controlHintLoreBySlot;
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

    /**
     * IMPLEMENTATION_PLAN.md Phase 7: which {@link RuntimeMenuSection} a
     * clicked slot belongs to - a section-scoped action (pagination, search/
     * filter trigger) needs this to know what it's acting on. See
     * {@link MenuActionContext}'s javadoc.
     */
    public Map<Integer, RuntimeMenuSection> sectionsBySlot() {
        return sectionsBySlot;
    }

    /**
     * Post-Phase-8 QOL follow-up: the last-rendered "what does this button
     * do" hint lore per slot ({@code MenuRenderer.resolveControlHints}),
     * kept here (not just inside the render pass that produced it) so
     * {@link MenuControlHintListener} can add/remove these lines on a live
     * sneak toggle without needing a full re-render.
     */
    public Map<Integer, List<String>> controlHintLoreBySlot() {
        return controlHintLoreBySlot;
    }

    /** Called after re-rendering into the same, already-open Inventory (e.g. a page turn). */
    public void update(RuntimeMenu menu, Map<Integer, RuntimeMenuItem> itemsBySlot,
                        Map<Integer, RuntimeMenuSection> sectionsBySlot,
                        Map<Integer, List<String>> controlHintLoreBySlot) {
        this.menu = menu;
        this.itemsBySlot = itemsBySlot;
        this.sectionsBySlot = sectionsBySlot;
        this.controlHintLoreBySlot = controlHintLoreBySlot;
    }
}
