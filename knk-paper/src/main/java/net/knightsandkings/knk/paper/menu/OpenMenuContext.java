package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
import net.knightsandkings.knk.core.menu.SectionView;
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
 * (a page turn, an auto-refresh) so the click-routing snapshot always matches
 * what's actually in the Inventory right now.
 * <p>
 * InventoryMenu Phase 9: also the unit {@code MenuService.refreshOpenMenus}
 * predicates select on ({@link #menuKey()}, {@link #menuContext()}), and it
 * keeps what an auto-refresh (E4) reuses instead of re-fetching: the assembled
 * {@link #menu()} and the material-ref lookups of the last render.
 */
public final class OpenMenuContext {

    private final Player player;
    private final Inventory inventory;
    private volatile RuntimeMenu menu;
    private volatile MenuRenderResult lastRender;

    public OpenMenuContext(Player player, Inventory inventory, RuntimeMenu menu, MenuRenderResult result) {
        this.player = player;
        this.inventory = inventory;
        this.menu = menu;
        this.lastRender = result;
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

    /** The open menu's template key - e.g. for {@code refreshOpenMenus(ctx -> ctx.menuKey().startsWith("siege."))}. */
    public String menuKey() {
        return menu.key();
    }

    /** The ctx params the open menu was rendered with (E1). */
    public MenuContextParams menuContext() {
        return lastRender.menuContext();
    }

    /** The rendered snapshot per slot (effective display mode, Render-filtered actions). */
    public Map<Integer, RuntimeMenuItem> itemsBySlot() {
        return lastRender.itemsBySlot();
    }

    /**
     * IMPLEMENTATION_PLAN.md Phase 7: which {@link RuntimeMenuSection} a
     * clicked slot belongs to - a section-scoped action (pagination, search/
     * filter trigger) needs this to know what it's acting on. See
     * {@link MenuActionContext}'s javadoc.
     */
    public Map<Integer, RuntimeMenuSection> sectionsBySlot() {
        return lastRender.sectionsBySlot();
    }

    /**
     * Post-Phase-8 QOL follow-up: the last-rendered "what does this button
     * do" hint lore per slot, kept here so {@link MenuControlHintListener} can
     * add/remove these lines on a live sneak toggle without a full re-render.
     */
    public Map<Integer, List<String>> controlHintLoreBySlot() {
        return lastRender.controlHintLoreBySlot();
    }

    /** InventoryMenu Phase 9 (E3): the row each row-template slot was rendered for. */
    public Map<Integer, Object> rowsBySlot() {
        return lastRender.rowsBySlot();
    }

    /** InventoryMenu Phase 9 (E9): the {@code $section$} view of a section as last rendered, or null. */
    public SectionView sectionView(Integer sectionId) {
        return sectionId != null ? lastRender.sectionViewsBySectionId().get(sectionId) : null;
    }

    /** Material-ref lookups of the last render - reused by an auto-refresh. */
    public Map<Integer, String> materialNamespaceKeys() {
        return lastRender.materialNamespaceKeys();
    }

    /** Called after re-rendering into the same, already-open Inventory (a page turn, a refresh). */
    public void update(RuntimeMenu menu, MenuRenderResult result) {
        this.menu = menu;
        this.lastRender = result;
    }
}
