package net.knightsandkings.knk.core.menu;

/**
 * Thrown when a persisted MenuTemplate tree (Phase 1) can't be assembled into
 * the Phase 2 runtime tree - an unparseable enum-shaped field, or a layout
 * that fails {@link MenuLayoutValidator}. Per DESIGN_REVIEW.md §1's decided
 * failure policy: fails one menu, loud and clear, not the whole plugin - the
 * caller (menu-open flow) is expected to catch this, log it, and refuse to
 * open that one broken menu rather than let the error surface as silent
 * garbage or bring anything else down.
 */
public class MenuAssemblyException extends RuntimeException {

    public MenuAssemblyException(String message) {
        super(message);
    }

    public MenuAssemblyException(String message, Throwable cause) {
        super(message, cause);
    }
}
