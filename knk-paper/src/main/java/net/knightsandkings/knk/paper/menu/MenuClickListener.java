package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkConditionBinding;
import net.knightsandkings.knk.core.menu.ActionRegistry;
import net.knightsandkings.knk.core.menu.ConditionOutcome;
import net.knightsandkings.knk.core.menu.ConditionRegistry;
import net.knightsandkings.knk.core.menu.MenuActionException;
import net.knightsandkings.knk.core.menu.MenuDisplayMode;
import net.knightsandkings.knk.core.menu.MenuParams;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.menu.MenuSessionRegistry;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Cancels every click inside a currently-open KnK menu Inventory (standard
 * custom-GUI hygiene - nothing should be able to pick up/rearrange/insert
 * items in a menu) and routes the click to the {@link RuntimeMenuItem} that
 * occupied the clicked slot in the last render.
 * <p>
 * IMPLEMENTATION_PLAN.md Phase 6 wires actual click-time execution here:
 * conditions are (re-)evaluated against a freshly-built live context - never
 * anything cached from the render pass - closing the staleness window
 * DESIGN_REVIEW.md §2.2 describes (state checked at render time can go stale
 * before the click happens), and only then does a passing action actually
 * run via {@link ActionRegistry}.
 * <p>
 * The {@code actionPermission} check here is IMPLEMENTATION_PLAN.md Phase 4's
 * click-time defense-in-depth half of DESIGN_REVIEW.md §2.4: render-time
 * hiding/disabling (see {@code MenuItemBukkitMapper}) is never trusted alone,
 * in case of a stale client view or any other client/timing edge case.
 */
public final class MenuClickListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(MenuClickListener.class.getName());

    private final OpenMenuContextRegistry openMenuContextRegistry;
    private final MenuSessionRegistry sessionRegistry;
    private final ActionRegistry<MenuActionContext> actionRegistry;
    private final ConditionRegistry<MenuActionContext> conditionRegistry;
    private final MenuService menuService;

    public MenuClickListener(
            OpenMenuContextRegistry openMenuContextRegistry,
            MenuSessionRegistry sessionRegistry,
            ActionRegistry<MenuActionContext> actionRegistry,
            ConditionRegistry<MenuActionContext> conditionRegistry,
            MenuService menuService
    ) {
        this.openMenuContextRegistry = openMenuContextRegistry;
        this.sessionRegistry = sessionRegistry;
        this.actionRegistry = actionRegistry;
        this.conditionRegistry = conditionRegistry;
        this.menuService = menuService;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Optional<OpenMenuContext> context = openMenuContextRegistry.get(player.getUniqueId());
        if (context.isEmpty()) {
            return;
        }

        Inventory menuInventory = context.get().inventory();
        if (!event.getView().getTopInventory().equals(menuInventory)) {
            // This player has our context registered but this click isn't in that
            // Inventory (e.g. a stale context from a menu that closed uncleanly) -
            // don't touch clicks that aren't actually ours.
            return;
        }

        // Never allow taking, rearranging, or shift-clicking items into a KnK menu.
        event.setCancelled(true);

        Inventory clickedInventory = event.getClickedInventory();
        if (clickedInventory == null || !clickedInventory.equals(menuInventory)) {
            return;
        }

        RuntimeMenuItem item = context.get().itemsBySlot().get(event.getSlot());
        if (item == null || item.displayMode() == MenuDisplayMode.DISABLED || item.displayMode() == MenuDisplayMode.HIDDEN) {
            return;
        }

        if (!item.isVisibleTo(player::hasPermission)) {
            // Defense in depth (DESIGN_REVIEW.md §2.4) - render-time exclusion
            // should already make this unreachable (the item wouldn't be in
            // itemsBySlot at all), but never trust that alone.
            return;
        }

        if (!item.isActionAllowedFor(player::hasPermission)) {
            LOGGER.fine(() -> "Player " + player.getName() + " lacks actionPermission '" + item.actionPermission()
                    + "' for menu item (id " + item.id() + "); click rejected");
            return;
        }

        Optional<MenuSession> session = sessionRegistry.get(player.getUniqueId());
        if (session.isEmpty()) {
            LOGGER.warning(() -> "Player " + player.getName() + " clicked menu item (id " + item.id()
                    + ") but has no MenuSession - ignoring click");
            return;
        }

        // IMPLEMENTATION_PLAN.md Phase 7: which section the clicked item lives
        // in - section-scoped actions (pagination, search/filter) need this;
        // see MenuActionContext's javadoc for why it comes from context here
        // rather than a paramsJson-carried section name.
        RuntimeMenuSection section = context.get().sectionsBySlot().get(event.getSlot());

        // IMPLEMENTATION_PLAN.md Phase 6 / DESIGN_REVIEW.md §2.2: built fresh,
        // right now - never reused from whatever render pass produced the
        // Inventory the player is looking at, which is the entire point of a
        // click-time (re-)check instead of trusting render-time state alone.
        MenuActionContext actionContext = new MenuActionContext(
                player, session.get(), MenuVariableContext.liveValues(player), menuService,
                context.get().menu(), section, item);

        // Post-Phase-8 QOL follow-up: shift-clicking a search button clears
        // the search instead of opening the anvil prompt - consolidates
        // "Search"/"Clear Search" into one button rather than two. Checked
        // before conditions/normal actions run at all, since clearing a
        // search should never be blockable by whatever conditions gate the
        // prompt action.
        try {
            if (event.isShiftClick() && hasAction(item, MenuActionHandlers.SEARCH_PROMPT)) {
                actionRegistry.execute(MenuActionHandlers.SEARCH_CLEAR, actionContext, Map.of());
                return;
            }
            executeClick(item, actionContext, player);
        } catch (MenuActionException e) {
            LOGGER.severe("Menu item (id " + item.id() + ") click failed for " + player.getName() + ": " + e.getMessage());
            player.sendMessage(ChatColor.RED + "Something went wrong with that.");
        }
    }

    /**
     * IMPLEMENTATION_PLAN.md Phase 6, open question 3 (item-level vs
     * action-level condition composition): a failing item-level condition
     * (the item's own {@code conditions} list, {@code ActionBindingId} null)
     * aborts every action on this item - no partial execution. Each
     * action's own conditions then gate just that one action independently,
     * per {@link KnkConditionBinding}'s own javadoc ("gates just that one
     * action") - one action can fire while a sibling action on the very same
     * item and the very same click doesn't.
     */
    private void executeClick(RuntimeMenuItem item, MenuActionContext context, Player player) {
        ConditionOutcome itemOutcome = evaluateConditions(item.conditions(), context);
        if (!itemOutcome.allowed()) {
            if (itemOutcome.denialMessage() != null) {
                player.sendMessage(ChatColor.YELLOW + itemOutcome.denialMessage());
            }
            return;
        }

        for (KnkActionBinding action : item.actions()) {
            ConditionOutcome actionOutcome = evaluateConditions(action.conditions(), context);
            if (!actionOutcome.allowed()) {
                if (actionOutcome.denialMessage() != null) {
                    player.sendMessage(ChatColor.YELLOW + actionOutcome.denialMessage());
                }
                continue;
            }
            actionRegistry.execute(action.actionTypeId(), context, MenuParams.parse(action.paramsJson()));
        }
    }

    /** Whether {@code item} has an action bound to the given {@code actionTypeId}, anywhere in its actions list. */
    private static boolean hasAction(RuntimeMenuItem item, String actionTypeId) {
        for (KnkActionBinding action : item.actions()) {
            if (actionTypeId.equals(action.actionTypeId())) {
                return true;
            }
        }
        return false;
    }

    /** Every condition in the list must pass (AND); the first denial found wins and short-circuits the rest. */
    private ConditionOutcome evaluateConditions(List<KnkConditionBinding> conditions, MenuActionContext context) {
        if (conditions == null || conditions.isEmpty()) {
            return ConditionOutcome.allow();
        }
        for (KnkConditionBinding condition : conditions) {
            ConditionOutcome outcome = conditionRegistry.test(
                    condition.conditionTypeId(), context, MenuParams.parse(condition.paramsJson()));
            if (!outcome.allowed()) {
                return outcome;
            }
        }
        return ConditionOutcome.allow();
    }
}
