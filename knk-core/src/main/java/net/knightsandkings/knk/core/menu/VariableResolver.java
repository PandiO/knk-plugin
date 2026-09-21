package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/**
 * Real getter-chain variable resolution (IMPLEMENTATION_PLAN.md Phase 3),
 * replacing Phase 2's {@code MenuVariablePlaceholderText} stand-in at its two
 * call sites in knk-paper's {@code MenuItemBukkitMapper} - that class is
 * retired outright rather than having its body swapped in place, since real
 * resolution genuinely needs a {@link MenuSession} and live context values
 * (e.g. the actual {@code Player}) that the placeholder's signature never
 * carried.
 * <p>
 * Placeholder syntax is {@code $player.getName$}-style chained getters
 * embedded in otherwise-literal text (QUICK_REFERENCE.md "Variable
 * Resolution"), matched via {@link MenuVariablePlaceholders#PATTERN} - a
 * properly bounded pattern, which is reconciliation bug #7's actual fix (v2's
 * broken {@code $(.*?)$} let every placeholder through unsubstituted for
 * years without anyone noticing).
 * <p>
 * Caching implements DESIGN_REVIEW.md §1's decided three-tier policy exactly:
 * the policy is consulted <em>before</em> any reflection work runs, not
 * after. {@code STATIC} short-circuits to a cached value forever once
 * resolved once; {@code ON_DIRTY} short-circuits unless
 * {@link MenuSession#isDirty()}; {@code TTL} short-circuits until
 * {@code ttlTicks} have elapsed since the cached value was produced. Cache
 * entries live on the {@link MenuSession} (see
 * {@link MenuSession.CachedVariable}), keyed by the binding's stable
 * persisted id - not on the {@code KnkVariableBinding} instance, since that
 * is rebuilt fresh from the database on every menu open/page turn.
 */
public final class VariableResolver {

    private static final Logger LOGGER = Logger.getLogger(VariableResolver.class.getName());
    private static final int DEFAULT_TTL_TICKS = 20;

    private VariableResolver() {
    }

    /** The single "Name"-targeted binding's resolved text, or null if none is set. */
    public static String resolveName(List<KnkVariableBinding> bindings, MenuSession session,
                                      Map<String, Object> contextValues, long currentTick) {
        return bindingsFor(bindings, "Name")
                .map(binding -> resolve(binding, session, contextValues, currentTick))
                .findFirst()
                .orElse(null);
    }

    /** Every "Lore"-targeted binding's resolved text, in sortOrder order - one line per binding. */
    public static List<String> resolveLore(List<KnkVariableBinding> bindings, MenuSession session,
                                            Map<String, Object> contextValues, long currentTick) {
        return bindingsFor(bindings, "Lore")
                .map(binding -> resolve(binding, session, contextValues, currentTick))
                .toList();
    }

    /**
     * Resolves one binding's expression, consulting its {@code RefreshPolicy}
     * against the session's cache before doing any reflection work at all.
     */
    public static String resolve(KnkVariableBinding binding, MenuSession session,
                                  Map<String, Object> contextValues, long currentTick) {
        MenuVariableRefreshPolicy policy = MenuEnumParsing.parse(
                MenuVariableRefreshPolicy.class, binding.refreshPolicy(), MenuVariableRefreshPolicy.ON_DIRTY,
                "refreshPolicy", "variable binding (id " + binding.id() + ")");

        Integer bindingId = binding.id();
        if (bindingId != null) {
            Optional<MenuSession.CachedVariable> cached = session.getCachedVariable(bindingId);
            if (cached.isPresent() && isFresh(policy, cached.get(), session, currentTick, binding.ttlTicks())) {
                return cached.get().value();
            }
        }

        String resolved = resolveExpression(binding.expression(), contextValues, bindingId);

        if (bindingId != null) {
            session.cacheVariable(bindingId, new MenuSession.CachedVariable(resolved, currentTick));
        }
        return resolved;
    }

    private static boolean isFresh(MenuVariableRefreshPolicy policy, MenuSession.CachedVariable cached,
                                    MenuSession session, long currentTick, Integer ttlTicks) {
        return switch (policy) {
            case STATIC -> true;
            case ON_DIRTY -> !session.isDirty();
            case TTL -> (currentTick - cached.resolvedAtTick()) < (ttlTicks != null ? ttlTicks : DEFAULT_TTL_TICKS);
        };
    }

    private static String resolveExpression(String expression, Map<String, Object> contextValues, Integer bindingId) {
        if (expression == null) {
            return "";
        }

        Matcher matcher = MenuVariablePlaceholders.PATTERN.matcher(expression);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            result.append(expression, lastEnd, matcher.start());
            result.append(resolveChain(matcher.group(1), contextValues, bindingId));
            lastEnd = matcher.end();
        }
        result.append(expression, lastEnd, expression.length());
        return result.toString();
    }

    private static String resolveChain(String path, Map<String, Object> contextValues, Integer bindingId) {
        String[] hops = path.split("\\.");
        Object current = contextValues != null ? contextValues.get(hops[0]) : null;
        if (current == null) {
            LOGGER.warning("Variable binding (id " + bindingId + "): unresolvable root variable '" + hops[0]
                    + "' in chain '$" + path + "$' - was it supplied in the live render context?");
            return "";
        }

        for (int i = 1; i < hops.length && current != null; i++) {
            current = invokeGetter(current, hops[i], bindingId, path);
        }
        return current != null ? String.valueOf(current) : "";
    }

    private static Object invokeGetter(Object target, String getterName, Integer bindingId, String fullPath) {
        try {
            return target.getClass().getMethod(getterName).invoke(target);
        } catch (ReflectiveOperationException e) {
            LOGGER.log(Level.WARNING, "Variable binding (id " + bindingId + "): getter chain '$" + fullPath
                    + "$' failed at '" + getterName + "' on " + target.getClass().getName()
                    + " - this should have been caught by MenuDefinitionValidator at plugin enable", e);
            return null;
        }
    }

    private static Stream<KnkVariableBinding> bindingsFor(List<KnkVariableBinding> bindings, String targetProperty) {
        if (bindings == null) {
            return Stream.empty();
        }
        return bindings.stream()
                .filter(binding -> targetProperty.equalsIgnoreCase(binding.targetProperty()))
                .sorted(Comparator.comparingInt(binding -> binding.sortOrder() != null ? binding.sortOrder() : 0));
    }
}
