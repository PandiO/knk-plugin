package net.knightsandkings.knk.core.menu;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code actionTypeId} -&gt; executable handler (IMPLEMENTATION_PLAN.md
 * "Code-side registries"). Generic over the execution context type
 * {@code C} so this class stays Bukkit-free: a real action like
 * {@code menu.close} fundamentally needs Player/Inventory access, but the
 * registry mechanism itself - the String-keyed map, lookup, and
 * "unregistered id" failure mode - doesn't. knk-paper instantiates
 * {@code ActionRegistry<MenuActionContext>} and registers concrete,
 * Bukkit-backed handlers at plugin enable, mirroring the split already
 * established between {@code MenuVariableContext.DECLARED_TYPES} (declared
 * shape, knk-paper) and the generic {@code Map<String,Object>} context
 * {@link VariableResolver} consumes (knk-core).
 */
public final class ActionRegistry<C> {

    @FunctionalInterface
    public interface ActionHandler<C> {
        void execute(C context, Map<String, String> params);
    }

    private final Map<String, ActionHandler<C>> handlers = new ConcurrentHashMap<>();

    public void register(String actionTypeId, ActionHandler<C> handler) {
        handlers.put(actionTypeId, handler);
    }

    public boolean isRegistered(String actionTypeId) {
        return handlers.containsKey(actionTypeId);
    }

    public Set<String> registeredIds() {
        return Set.copyOf(handlers.keySet());
    }

    /**
     * @throws MenuActionException if {@code actionTypeId} isn't registered -
     *                              IMPLEMENTATION_PLAN.md Phase 6's decided
     *                              "fail loudly, not silently no-op" policy.
     */
    public void execute(String actionTypeId, C context, Map<String, String> params) {
        ActionHandler<C> handler = handlers.get(actionTypeId);
        if (handler == null) {
            throw new MenuActionException("No ActionRegistry handler registered for actionTypeId '" + actionTypeId + "'");
        }
        handler.execute(context, params);
    }
}
