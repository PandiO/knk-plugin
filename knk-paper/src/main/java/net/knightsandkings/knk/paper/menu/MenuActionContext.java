package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.MenuSession;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * The concrete, Bukkit-backed execution context {@code ActionRegistry}/
 * {@code ConditionRegistry} handlers run against (IMPLEMENTATION_PLAN.md
 * Phase 6) - the paper-side half of the same split {@link MenuVariableContext}
 * already establishes for variable resolution: knk-core's registries are
 * generic over this type, and knk-paper is the only place that has to know
 * what a live Player-backed context actually looks like.
 * <p>
 * {@code variableContext} must be freshly built from
 * {@link MenuVariableContext#liveValues} at click time, never reused from
 * the render pass that produced the menu the player is looking at -
 * DESIGN_REVIEW.md §2.2's whole point is closing the staleness window
 * between render and click, so a cached render-time context here would
 * silently defeat this phase's own purpose.
 */
public record MenuActionContext(
        Player player,
        MenuSession session,
        Map<String, Object> variableContext,
        MenuService menuService
) {
}
