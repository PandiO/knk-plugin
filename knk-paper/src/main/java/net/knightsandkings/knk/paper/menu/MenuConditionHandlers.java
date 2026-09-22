package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.ConditionOutcome;
import net.knightsandkings.knk.core.menu.ConditionRegistry;
import net.knightsandkings.knk.core.menu.MenuActionException;

import java.util.Map;

/**
 * Concrete {@code ConditionRegistry} handlers (IMPLEMENTATION_PLAN.md Phase
 * 6, open question 1). DESIGN_REVIEW.md §2.2 suggests permission-node,
 * affordability, ownership, item-age as a starting library, but only
 * {@code permission-node} (and the trivial {@code always} baseline used by
 * the existing {@code example.placeholder} seed) are backed by real,
 * already-wired context today - {@link MenuVariableContext} exposes nothing
 * but {@code player} (no economy, item-ownership, or item-age concept is
 * wired into any live context yet). Affordability/ownership/item-age are
 * deliberately NOT implemented here rather than backed by invented fake
 * data - see ACTIVE_SESSIONS.md's Phase 6 entry for the full reasoning,
 * the same "flag as blocked on missing integration" call Phase 5 made for
 * Category/Grade/Tag facets.
 */
public final class MenuConditionHandlers {

    public static final String ALWAYS = "always";
    public static final String PERMISSION_NODE = "permission-node";

    private MenuConditionHandlers() {
    }

    public static void registerDefaults(ConditionRegistry<MenuActionContext> registry) {
        registry.register(ALWAYS, (context, params) -> ConditionOutcome.allow());
        registry.register(PERMISSION_NODE, MenuConditionHandlers::permissionNode);
    }

    /**
     * Player-facing feedback (IMPLEMENTATION_PLAN.md Phase 6, open question
     * 4): unlike Phase 4's {@code actionPermission} check (a structural
     * authorization boundary that stays silent so it never reveals exactly
     * which node is missing), a Condition is a declarative business-rule
     * gate a content author attaches on purpose - DESIGN_REVIEW.md §2.2's
     * own example ("you can't afford this") is explicitly meant to be
     * player-visible. So a failing condition here denies with a generic,
     * non-revealing message rather than silently no-op'ing: it says access
     * was denied, never which node would have granted it. Every condition
     * type this phase adds follows this same rule.
     */
    private static ConditionOutcome permissionNode(MenuActionContext context, Map<String, String> params) {
        String node = params.get("node");
        if (node == null || node.isBlank()) {
            throw new MenuActionException("permission-node condition is missing its required 'node' param");
        }
        return context.player().hasPermission(node)
                ? ConditionOutcome.allow()
                : ConditionOutcome.deny("You don't have permission to do that.");
    }
}
