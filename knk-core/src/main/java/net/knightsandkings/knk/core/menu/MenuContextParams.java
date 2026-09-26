package net.knightsandkings.knk.core.menu;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * InventoryMenu Phase 9 (E1): the context parameters a menu was opened with -
 * e.g. {@code siege.information} opened for lobby 3 carries {@code lobbyId=3}.
 * Supplied by {@code menu.open}'s {@code ctx.*} params (prefix stripped) or by
 * a command through {@code MenuService.openMenu(player, key, ctx)}; stored on
 * every {@link MenuSession.NavigationEntry} so back navigation restores it, and
 * exposed to templates as the engine root {@code $ctx$} ({@code $ctx.lobbyId$}).
 * <p>
 * Values are plain strings on purpose: they travel through
 * {@code ActionBinding.ParamsJson} (a string map) anyway, and a feature that
 * wants a typed value resolves it in its own variable provider (which receives
 * this object). A missing key resolves to {@code null} here and to {@code ""}
 * in a {@code $ctx.x$} placeholder. Immutable; equality is by value.
 */
public final class MenuContextParams {

    /** Prefix that marks a {@code menu.open} param as a context parameter. */
    public static final String PARAM_PREFIX = "ctx.";

    public static final MenuContextParams EMPTY = new MenuContextParams(Map.of());

    private final Map<String, String> values;

    private MenuContextParams(Map<String, String> values) {
        this.values = values;
    }

    public static MenuContextParams of(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY;
        }
        Map<String, String> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return copy.isEmpty() ? EMPTY : new MenuContextParams(Map.copyOf(copy));
    }

    /**
     * Collects every {@code ctx.}-prefixed entry of an action's params map (the
     * {@code menu.open} convention), with the prefix stripped. Entries with an
     * empty name after the prefix are ignored.
     */
    public static MenuContextParams fromPrefixedParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return EMPTY;
        }
        Map<String, String> collected = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.startsWith(PARAM_PREFIX) && key.length() > PARAM_PREFIX.length()) {
                collected.put(key.substring(PARAM_PREFIX.length()), entry.getValue());
            }
        }
        return of(collected);
    }

    /** The value for {@code name}, or null if absent. Also the hop {@code $ctx.name$} resolves through. */
    public String get(String name) {
        return values.get(name);
    }

    public String getOrDefault(String name, String defaultValue) {
        return values.getOrDefault(name, defaultValue);
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Map<String, String> asMap() {
        return values;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof MenuContextParams params && values.equals(params.values));
    }

    @Override
    public int hashCode() {
        return Objects.hash(values);
    }

    @Override
    public String toString() {
        return "MenuContextParams" + values;
    }
}
