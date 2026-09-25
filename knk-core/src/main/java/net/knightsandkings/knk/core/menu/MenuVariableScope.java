package net.knightsandkings.knk.core.menu;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * InventoryMenu Phase 9 (E2/E3/E9): the variable context of one render or
 * click pass, as the {@code Map<String, Object>} {@link VariableResolver}
 * consumes. Layered and lazy:
 * <ul>
 *   <li>the <b>root scope</b> holds the engine roots ({@code ctx}, {@code menu})
 *       eagerly and the feature-registered roots lazily - a provider is only
 *       invoked the first time some binding in the pass reads its root, and its
 *       result (even null) is memoised for the rest of the pass;</li>
 *   <li><b>child scopes</b> ({@link #with}) add per-section ({@code section})
 *       or per-row ({@code row}) values on top without copying - and without
 *       forcing - the parent.</li>
 * </ul>
 * Not thread-safe; a scope belongs to one pass on one thread (the main thread,
 * per IMPLEMENTATION_PLAN.md Phase 9 §9.0). {@link #entrySet()} materialises
 * every lazy root and is meant for debugging only.
 */
public final class MenuVariableScope extends AbstractMap<String, Object> {

    private final MenuVariableScope parent;
    private final Map<String, Object> locals;
    private final Set<String> lazyRoots;
    private final Function<String, Object> lazyResolver;
    private final Map<String, Object> memo;

    private MenuVariableScope(MenuVariableScope parent, Map<String, Object> locals, Set<String> lazyRoots,
                              Function<String, Object> lazyResolver) {
        this.parent = parent;
        this.locals = locals;
        this.lazyRoots = lazyRoots;
        this.lazyResolver = lazyResolver;
        this.memo = lazyResolver != null ? new HashMap<>() : null;
    }

    /** A root scope: {@code eager} values plus {@code lazyRoots} resolved on first read via {@code lazyResolver}. */
    public static MenuVariableScope root(Map<String, Object> eager, Set<String> lazyRoots,
                                         Function<String, Object> lazyResolver) {
        return new MenuVariableScope(null, new LinkedHashMap<>(eager != null ? eager : Map.of()),
                lazyRoots != null ? Set.copyOf(lazyRoots) : Set.of(), lazyResolver);
    }

    /** A plain, fully eager scope - handy for tests and for callers with no providers. */
    public static MenuVariableScope of(Map<String, Object> values) {
        return root(values, Set.of(), null);
    }

    /** A child scope adding {@code key → value} on top of this one. */
    public MenuVariableScope with(String key, Object value) {
        Map<String, Object> child = new LinkedHashMap<>();
        child.put(key, value);
        return new MenuVariableScope(this, child, Set.of(), null);
    }

    /** A child scope adding every entry of {@code values} on top of this one. */
    public MenuVariableScope with(Map<String, Object> values) {
        return new MenuVariableScope(this, new LinkedHashMap<>(values), Set.of(), null);
    }

    @Override
    public Object get(Object key) {
        if (locals.containsKey(key)) {
            return locals.get(key);
        }
        if (lazyResolver != null && key instanceof String root && lazyRoots.contains(root)) {
            if (!memo.containsKey(root)) {
                memo.put(root, lazyResolver.apply(root));
            }
            return memo.get(root);
        }
        return parent != null ? parent.get(key) : null;
    }

    @Override
    public boolean containsKey(Object key) {
        return locals.containsKey(key)
                || (key instanceof String root && lazyRoots.contains(root))
                || (parent != null && parent.containsKey(key));
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        Set<String> keys = new LinkedHashSet<>();
        collectKeys(keys);
        Map<String, Object> materialised = new LinkedHashMap<>();
        for (String key : keys) {
            materialised.put(key, get(key));
        }
        return materialised.entrySet();
    }

    private void collectKeys(Set<String> keys) {
        if (parent != null) {
            parent.collectKeys(keys);
        }
        keys.addAll(lazyRoots);
        keys.addAll(locals.keySet());
    }
}
