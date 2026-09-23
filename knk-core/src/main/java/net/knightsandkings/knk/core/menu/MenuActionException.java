package net.knightsandkings.knk.core.menu;

/**
 * Thrown at click time when an ActionBinding/ConditionBinding references an
 * {@code actionTypeId}/{@code conditionTypeId} that isn't registered in the
 * {@link ActionRegistry}/{@link ConditionRegistry}, or when a registered
 * handler itself refuses to run (e.g. a required param is missing) -
 * IMPLEMENTATION_PLAN.md Phase 6's decided "fail loudly, not silently
 * no-op" policy for the click path, the same spirit as
 * {@link MenuAssemblyException}'s per-menu (not per-server) failure handling
 * on the load path.
 */
public final class MenuActionException extends RuntimeException {
    public MenuActionException(String message) {
        super(message);
    }
}
