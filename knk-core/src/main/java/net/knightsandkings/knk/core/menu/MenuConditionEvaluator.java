package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkConditionBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * InventoryMenu Phase 9 (E5): the one place condition lists are evaluated, by
 * phase, so the render pass and the click handler can never disagree about
 * what a condition list means.
 * <ul>
 *   <li>Every condition of the requested {@link MenuConditionPhase} must allow
 *       (AND); the first denial wins and short-circuits the rest.</li>
 *   <li>Conditions of the other phase are skipped - a {@code Click} condition
 *       never hides an item, a {@code Render} condition never messages the
 *       player (callers drop {@link ConditionOutcome#denialMessage()} for
 *       Render).</li>
 *   <li>Each condition's {@code ParamsJson} values are interpolated against
 *       {@code variableScope} first (E3), so {@code {"lobbyId": "$ctx.lobbyId$"}}
 *       reaches the handler as {@code {"lobbyId": "3"}}; on a row template the
 *       scope carries {@code $row$}, so this is evaluated per row.</li>
 * </ul>
 * Handler exceptions propagate - the render path catches them (a broken
 * condition hides its item and is logged), the click path reports them like
 * any other {@link MenuActionException}.
 */
public final class MenuConditionEvaluator {

    private MenuConditionEvaluator() {
    }

    public static <C> ConditionOutcome evaluate(List<KnkConditionBinding> conditions, MenuConditionPhase phase,
                                                ConditionRegistry<C> registry, C context,
                                                Map<String, Object> variableScope) {
        if (conditions == null || conditions.isEmpty()) {
            return ConditionOutcome.allow();
        }
        for (KnkConditionBinding condition : conditions) {
            if (phaseOf(condition) != phase) {
                continue;
            }
            Map<String, String> params = MenuParams.resolve(condition.paramsJson(), variableScope);
            ConditionOutcome outcome = registry.test(condition.conditionTypeId(), context, params);
            if (!outcome.allowed()) {
                return outcome;
            }
        }
        return ConditionOutcome.allow();
    }

    /**
     * Render-phase filtering of an item's actions (E5): returns the actions whose
     * own Render-phase conditions all allow, in order. Click-phase action
     * conditions are untouched (they still run at click time).
     */
    public static <C> List<KnkActionBinding> actionsAllowedAtRender(List<KnkActionBinding> actions,
                                                                   ConditionRegistry<C> registry, C context,
                                                                   Map<String, Object> variableScope) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        List<KnkActionBinding> allowed = new ArrayList<>(actions.size());
        for (KnkActionBinding action : actions) {
            if (evaluate(action.conditions(), MenuConditionPhase.RENDER, registry, context, variableScope).allowed()) {
                allowed.add(action);
            }
        }
        return allowed;
    }

    public static boolean hasPhase(List<KnkConditionBinding> conditions, MenuConditionPhase phase) {
        return conditions != null && conditions.stream().anyMatch(condition -> phaseOf(condition) == phase);
    }

    public static MenuConditionPhase phaseOf(KnkConditionBinding condition) {
        return MenuConditionPhase.parse(condition.phase(), "condition (id " + condition.id() + ")");
    }
}
