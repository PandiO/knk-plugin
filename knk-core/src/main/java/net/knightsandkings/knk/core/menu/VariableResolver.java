package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <p>
 * <b>InventoryMenu Phase 9</b> additions:
 * <ul>
 *   <li><b>Resolved shapes (E8).</b> A binding resolves to a <em>shape</em>:
 *       a {@code String}, a {@code List<String>} or {@code null}. When the
 *       expression is exactly one placeholder ({@code $row.getHintLines$}) the
 *       getter's raw value decides: {@code null} (or an empty {@code Optional})
 *       is {@code null} - a dropped lore line / an unset name; an
 *       {@code Iterable} or array is a list (one lore line per element, null
 *       elements dropped); anything else is {@code String.valueOf}. Any other
 *       expression (text around/between placeholders) is one string, a
 *       {@code null} piece renders as {@code ""} and an {@code Iterable} piece
 *       is joined with {@code ", "}.</li>
 *   <li><b>Row scope (E3).</b> Row-template bindings are cached per row
 *       position ({@link RowScope}) and invalidated when a different row lands
 *       there.</li>
 *   <li><b>Map-like hops (E1).</b> A hop on a {@link MenuContextParams} (the
 *       {@code $ctx$} root) or any {@code Map} is a key lookup, not a getter.</li>
 *   <li><b>{@link #interpolate}</b> - uncached text-form resolution for
 *       action/condition/content-source params values.</li>
 * </ul>
 */
public final class VariableResolver {

    private static final Logger LOGGER = Logger.getLogger(VariableResolver.class.getName());
    private static final int DEFAULT_TTL_TICKS = 20;
    private static final String LIST_JOINER = ", ";

    private VariableResolver() {
    }

    /**
     * InventoryMenu Phase 9 (E3): cache scope for one row rendered through a
     * row template. {@code position} (section id + index on the page) keys the
     * cache entry, so memory stays bounded by slots x bindings; {@code identity}
     * ({@link MenuRowKey#menuRowKey()} or the row object itself) is stored
     * with the entry, and a different identity at the same position is a miss.
     */
    public record RowScope(Object position, Object identity) {
        public static RowScope forRow(Integer sectionId, int indexOnPage, Object row) {
            Object identity = row instanceof MenuRowKey keyed ? keyed.menuRowKey() : row;
            return new RowScope(List.of(sectionId == null ? -1 : sectionId, indexOnPage), identity);
        }
    }

    /** The single "Name"-targeted binding's resolved text, or null if none is set (or it resolved to null, E8). */
    public static String resolveName(List<KnkVariableBinding> bindings, MenuSession session,
                                      Map<String, Object> contextValues, long currentTick) {
        return resolveName(bindings, session, contextValues, currentTick, null);
    }

    public static String resolveName(List<KnkVariableBinding> bindings, MenuSession session,
                                      Map<String, Object> contextValues, long currentTick, RowScope rowScope) {
        return bindingsFor(bindings, "Name")
                .findFirst()
                .map(binding -> resolveValue(binding, session, contextValues, currentTick, rowScope))
                .map(VariableResolver::toText)
                .orElse(null);
    }

    /**
     * Every "Lore"-targeted binding's resolved lines, in sortOrder order. Per
     * InventoryMenu Phase 9 (E8) a binding contributes zero lines (resolved to
     * null), one line, or several (resolved to a list); an empty string stays
     * one blank line.
     */
    public static List<String> resolveLore(List<KnkVariableBinding> bindings, MenuSession session,
                                            Map<String, Object> contextValues, long currentTick) {
        return resolveLore(bindings, session, contextValues, currentTick, null);
    }

    public static List<String> resolveLore(List<KnkVariableBinding> bindings, MenuSession session,
                                            Map<String, Object> contextValues, long currentTick, RowScope rowScope) {
        List<String> lines = new ArrayList<>();
        bindingsFor(bindings, "Lore").forEach(binding -> {
            Object shape = resolveValue(binding, session, contextValues, currentTick, rowScope);
            if (shape instanceof List<?> list) {
                list.forEach(line -> lines.add(String.valueOf(line)));
            } else if (shape != null) {
                lines.add((String) shape);
            }
        });
        return lines;
    }

    /**
     * IMPLEMENTATION_PLAN.md Phase 5 / DESIGN_REVIEW.md §2.3: the first
     * binding targeting an arbitrary property name's resolved text, for
     * FilterBar facet matching. {@code targetProperty} isn't restricted to
     * "Name"/"Lore" - it's the same free-text column real templates already
     * use for those two, so a content author can attach e.g. a "Category"-
     * targeted binding to an item with no schema change, and a filter facet
     * matches against it the same way search matches against "Name".
     * <p>
     * Phase 9 (E6) reuses this for the item-meta properties (Material, Amount,
     * ...). Empty when there is no such binding or it resolved to null.
     */
    public static Optional<String> resolveByTargetProperty(List<KnkVariableBinding> bindings, String targetProperty,
                                                             MenuSession session, Map<String, Object> contextValues,
                                                             long currentTick) {
        return resolveByTargetProperty(bindings, targetProperty, session, contextValues, currentTick, null);
    }

    public static Optional<String> resolveByTargetProperty(List<KnkVariableBinding> bindings, String targetProperty,
                                                             MenuSession session, Map<String, Object> contextValues,
                                                             long currentTick, RowScope rowScope) {
        return bindingsFor(bindings, targetProperty)
                .findFirst()
                .map(binding -> resolveValue(binding, session, contextValues, currentTick, rowScope))
                .map(VariableResolver::toText);
    }

    /** Whether any binding targets {@code targetProperty} (case-insensitive). */
    public static boolean hasBinding(List<KnkVariableBinding> bindings, String targetProperty) {
        return bindingsFor(bindings, targetProperty).findAny().isPresent();
    }

    /**
     * Resolves one binding's expression to text (a list joined with ", ", null
     * as ""), consulting its {@code RefreshPolicy} against the session's cache
     * before doing any reflection work at all.
     */
    public static String resolve(KnkVariableBinding binding, MenuSession session,
                                  Map<String, Object> contextValues, long currentTick) {
        String text = toText(resolveValue(binding, session, contextValues, currentTick, null));
        return text != null ? text : "";
    }

    /**
     * Resolves one binding to its shape (String, List&lt;String&gt; or null - see
     * the class javadoc), consulting its {@code RefreshPolicy} against the
     * session's cache (scoped per row when {@code rowScope} is set) before
     * doing any reflection work at all.
     */
    public static Object resolveValue(KnkVariableBinding binding, MenuSession session,
                                       Map<String, Object> contextValues, long currentTick, RowScope rowScope) {
        MenuVariableRefreshPolicy policy = MenuEnumParsing.parse(
                MenuVariableRefreshPolicy.class, binding.refreshPolicy(), MenuVariableRefreshPolicy.ON_DIRTY,
                "refreshPolicy", "variable binding (id " + binding.id() + ")");

        Integer bindingId = binding.id();
        Object scope = rowScope != null ? rowScope.position() : null;
        Object identity = rowScope != null ? rowScope.identity() : null;
        if (bindingId != null) {
            Optional<MenuSession.CachedVariable> cached = session.getCachedVariable(bindingId, scope);
            if (cached.isPresent()
                    && Objects.equals(cached.get().rowIdentity(), identity)
                    && isFresh(policy, cached.get(), session, currentTick, binding.ttlTicks())) {
                return cached.get().value();
            }
        }

        Object resolved = evaluate(binding.expression(), contextValues, bindingId);

        if (bindingId != null) {
            session.cacheVariable(bindingId, scope, new MenuSession.CachedVariable(resolved, currentTick, identity));
        }
        return resolved;
    }

    /**
     * InventoryMenu Phase 9 (E3): text-form, uncached resolution of every
     * {@code $…$} placeholder in {@code text} - for the values of
     * {@code ActionBinding}/{@code ConditionBinding}/content-source params,
     * which are resolved per click/render and never cached. A null/list piece
     * follows the mixed-text rule ({@code ""} / joined with ", ").
     */
    public static String interpolate(String text, Map<String, Object> contextValues) {
        if (text == null || text.indexOf('$') < 0) {
            return text;
        }
        return toText(evaluate(text, contextValues, null));
    }

    private static boolean isFresh(MenuVariableRefreshPolicy policy, MenuSession.CachedVariable cached,
                                    MenuSession session, long currentTick, Integer ttlTicks) {
        return switch (policy) {
            case STATIC -> true;
            case ON_DIRTY -> !session.isDirty();
            case TTL -> (currentTick - cached.resolvedAtTick()) < (ttlTicks != null ? ttlTicks : DEFAULT_TTL_TICKS);
        };
    }

    /** Evaluates an expression to its shape - see the class javadoc for the E8 rules. */
    static Object evaluate(String expression, Map<String, Object> contextValues, Integer bindingId) {
        if (expression == null) {
            return "";
        }

        Matcher matcher = MenuVariablePlaceholders.PATTERN.matcher(expression);
        if (matcher.matches()) {
            return toShape(resolveChainRaw(matcher.group(1), contextValues, bindingId));
        }

        matcher.reset();
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            result.append(expression, lastEnd, matcher.start());
            result.append(toPieceText(resolveChainRaw(matcher.group(1), contextValues, bindingId)));
            lastEnd = matcher.end();
        }
        result.append(expression, lastEnd, expression.length());
        return result.toString();
    }

    /** Text form of a shape: null stays null, a list is joined with ", ". */
    static String toText(Object shape) {
        if (shape == null) {
            return null;
        }
        if (shape instanceof List<?> list) {
            return String.join(LIST_JOINER, list.stream().map(String::valueOf).toList());
        }
        return String.valueOf(shape);
    }

    private static Object toShape(Object raw) {
        if (raw instanceof Optional<?> optional) {
            raw = optional.orElse(null);
        }
        if (raw == null) {
            return null;
        }
        List<Object> elements = asElements(raw);
        if (elements != null) {
            List<String> lines = new ArrayList<>(elements.size());
            for (Object element : elements) {
                if (element != null) {
                    lines.add(String.valueOf(element));
                }
            }
            return Collections.unmodifiableList(lines);
        }
        return String.valueOf(raw);
    }

    private static String toPieceText(Object raw) {
        Object shape = toShape(raw);
        String text = toText(shape);
        return text != null ? text : "";
    }

    private static List<Object> asElements(Object raw) {
        if (raw instanceof Iterable<?> iterable) {
            List<Object> elements = new ArrayList<>();
            iterable.forEach(elements::add);
            return elements;
        }
        if (raw.getClass().isArray()) {
            int length = Array.getLength(raw);
            List<Object> elements = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                elements.add(Array.get(raw, i));
            }
            return elements;
        }
        return null;
    }

    private static Object resolveChainRaw(String path, Map<String, Object> contextValues, Integer bindingId) {
        String[] hops = path.split("\\.");
        String root = hops[0];
        Object current = contextValues != null ? contextValues.get(root) : null;
        if (current == null) {
            if (contextValues == null || !contextValues.containsKey(root)) {
                LOGGER.warning("Variable binding (id " + bindingId + "): unresolvable root variable '" + root
                        + "' in chain '$" + path + "$' - was it supplied in the live render context?");
            }
            return null;
        }

        for (int i = 1; i < hops.length && current != null; i++) {
            current = step(current, hops[i], bindingId, path);
        }
        return current;
    }

    private static Object step(Object target, String hop, Integer bindingId, String fullPath) {
        if (target instanceof MenuContextParams params) {
            return params.get(hop);
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(hop);
        }
        return invokeGetter(target, hop, bindingId, fullPath);
    }

    private static Object invokeGetter(Object target, String getterName, Integer bindingId, String fullPath) {
        Method method;
        try {
            method = target.getClass().getMethod(getterName);
        } catch (NoSuchMethodException e) {
            LOGGER.log(Level.WARNING, "Variable binding (id " + bindingId + "): getter chain '$" + fullPath
                    + "$' failed at '" + getterName + "' on " + target.getClass().getName()
                    + " - this should have been caught by MenuDefinitionValidator at plugin enable", e);
            return null;
        }
        try {
            return method.invoke(target);
        } catch (IllegalAccessException e) {
            // Phase 9 (E2/E3): feature-registered view/row classes may be
            // non-public (e.g. a private nested record) - their public getters
            // are still what the author meant, so retry with access enabled.
            if (method.trySetAccessible()) {
                try {
                    return method.invoke(target);
                } catch (ReflectiveOperationException retry) {
                    return logGetterFailure(retry, bindingId, fullPath, getterName, target);
                }
            }
            return logGetterFailure(e, bindingId, fullPath, getterName, target);
        } catch (ReflectiveOperationException e) {
            return logGetterFailure(e, bindingId, fullPath, getterName, target);
        }
    }

    private static Object logGetterFailure(Exception e, Integer bindingId, String fullPath, String getterName, Object target) {
        LOGGER.log(Level.WARNING, "Variable binding (id " + bindingId + "): getter chain '$" + fullPath
                + "$' failed at '" + getterName + "' on " + target.getClass().getName(), e);
        return null;
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
