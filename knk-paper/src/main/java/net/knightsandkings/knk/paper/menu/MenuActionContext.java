package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
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
 * <p>
 * {@code menu}/{@code section} (IMPLEMENTATION_PLAN.md Phase 7): the
 * {@link RuntimeMenu} the player currently has open and the
 * {@link RuntimeMenuSection} the clicked item lives in. Added for this phase
 * because every new preset action (pagination next/prev, search prompt,
 * filter prompt/cycle/clear) is inherently section-scoped - a "next page"
 * click has to know which section to page. The alternative considered was
 * having a content author hand-type the target section's name into the
 * action's own {@code paramsJson} on every button instance; rejected because
 * that defeats the point of a reusable "preset" component (a search/filter/
 * pagination button should work by construction, not by a content author
 * getting a string right in every instance) and would silently break the
 * moment a section gets renamed. Both fields come from the same
 * {@link OpenMenuContext} snapshot {@link MenuClickListener} already uses to
 * resolve the clicked {@code RuntimeMenuItem} - see
 * {@code MenuRenderResult#sectionsBySlot} for where the slot -&gt; section
 * mapping is actually built. {@code section} is effectively never null for a
 * real click (every rendered item belongs to exactly one section), but
 * handlers that need it still check defensively rather than assume it, since
 * nothing prevents a future non-click caller from constructing a context
 * without one.
 */
public record MenuActionContext(
        Player player,
        MenuSession session,
        Map<String, Object> variableContext,
        MenuService menuService,
        RuntimeMenu menu,
        RuntimeMenuSection section
) {
}
