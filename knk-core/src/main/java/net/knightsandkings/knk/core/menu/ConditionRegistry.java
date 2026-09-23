package net.knightsandkings.knk.core.menu;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code conditionTypeId} -&gt; {@code Predicate}-shaped handler
 * (IMPLEMENTATION_PLAN.md "Code-side registries"). Returns a
 * {@link ConditionOutcome} rather than a bare boolean so a handler can
 * optionally carry a player-facing denial message (DESIGN_REVIEW.md §2.2,
 * open question 4). Generic over the context type {@code C} for the same
 * reason as {@link ActionRegistry} - see its javadoc for the full rationale
 * behind the knk-core/knk-paper split.
 */
public final class ConditionRegistry<C> {

    @FunctionalInterface
    public interface ConditionHandler<C> {
        ConditionOutcome test(C context, Map<String, String> params);
    }

    private final Map<String, ConditionHandler<C>> handlers = new ConcurrentHashMap<>();

    public void register(String conditionTypeId, ConditionHandler<C> handler) {
        handlers.put(conditionTypeId, handler);
    }

    public boolean isRegistered(String conditionTypeId) {
        return handlers.containsKey(conditionTypeId);
    }

    public Set<String> registeredIds() {
        return Set.copyOf(handlers.keySet());
    }

    /**
     * @throws MenuActionException if {@code conditionTypeId} isn't
     *                              registered - same "fail loudly" policy as
     *                              {@link ActionRegistry#execute}.
     */
    public ConditionOutcome test(String conditionTypeId, C context, Map<String, String> params) {
        ConditionHandler<C> handler = handlers.get(conditionTypeId);
        if (handler == null) {
            throw new MenuActionException("No ConditionRegistry handler registered for conditionTypeId '" + conditionTypeId + "'");
        }
        return handler.test(context, params);
    }
}
