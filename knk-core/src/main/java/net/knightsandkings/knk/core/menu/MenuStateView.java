package net.knightsandkings.knk.core.menu;

/**
 * Content port CP6 (engine gap G1): the {@code state} engine root. Every hop after {@code state}
 * is joined back into one key - {@code $state.pm.coinStep$} reads key {@code "pm.coinStep"} -
 * and resolves to the stored value or {@code ""} when unset (the same rule as {@code $ctx$}, E1).
 * A live view over the session, so a render after {@code menu.state.set} sees the new value.
 */
public final class MenuStateView {

    private static final MenuStateView EMPTY = new MenuStateView(null);

    private final MenuSession session;

    private MenuStateView(MenuSession session) {
        this.session = session;
    }

    public static MenuStateView of(MenuSession session) {
        return session != null ? new MenuStateView(session) : EMPTY;
    }

    /** The value under {@code key}, or {@code ""} when unset. */
    public String get(String key) {
        String value = session != null ? session.getState(key) : null;
        return value != null ? value : "";
    }

    @Override
    public String toString() {
        return "MenuStateView" + (session != null ? session.stateSnapshot() : "{}");
    }
}
