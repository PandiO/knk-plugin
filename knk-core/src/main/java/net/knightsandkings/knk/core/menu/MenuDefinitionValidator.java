package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkConditionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Load-time validation (IMPLEMENTATION_PLAN.md Phase 3, DESIGN_REVIEW.md §1):
 * walks every {@code VariableBinding} in an assembled {@link RuntimeMenu} and
 * resolves each getter-chain hop against the <em>declared type</em> of its
 * root context variable (e.g. {@code $player.getName$} looks up
 * {@code getName} on the caller-supplied {@code Player.class}, not on a live
 * instance) - this is what lets it run once at plugin enable, before any
 * player is even online, instead of only surfacing a broken chain the first
 * time some player happens to open the menu. That's reconciliation bug #7's
 * actual failure mode: not that the substitution bug existed, but that
 * nothing caught it for years.
 * <p>
 * Deliberately separate from {@link MenuTemplateAssembler#assemble} /
 * {@link MenuLayoutValidator} - those run on every menu open and page turn
 * and must stay cheap; this one is reflection-heavy and is meant to be
 * invoked once per registered menu at startup, not on the render path.
 * <p>
 * Kept Bukkit-free like the rest of this package: the caller (knk-paper, at
 * plugin enable) supplies {@code declaredContextTypes} - this class never
 * needs to know the values are Bukkit types, only {@link Class} objects.
 * Generic/erased types (a getter returning {@code List<T>}) validate against
 * the raw return type only - an accepted simplification per DESIGN_REVIEW.md
 * §1, not a full type checker.
 */
public final class MenuDefinitionValidator {

    private MenuDefinitionValidator() {
    }

    /**
     * @throws MenuAssemblyException aggregating every unresolved getter-chain
     *                                hop found anywhere in the menu, per
     *                                DESIGN_REVIEW.md §1's per-menu (not
     *                                per-server) failure policy - the caller
     *                                is expected to catch this per menu and
     *                                keep validating the rest of the registry
     *                                rather than let one broken menu abort
     *                                the whole startup walk.
     */
    public static void validate(RuntimeMenu menu, Map<String, Class<?>> declaredContextTypes) {
        List<String> errors = new ArrayList<>();

        for (RuntimeMenuSection section : menu.sections()) {
            String sectionContext = "section '" + section.name() + "' (id " + section.id() + ") in menu '" + menu.key() + "'";
            validateBindings(section.variableBindings(), declaredContextTypes, sectionContext, errors);

            for (RuntimeMenuItem item : section.items()) {
                String itemContext = "item (id " + item.id() + ") in section '" + section.name() + "' in menu '" + menu.key() + "'";
                validateBindings(item.variableBindings(), declaredContextTypes, itemContext, errors);
            }
        }

        if (!errors.isEmpty()) {
            throw new MenuAssemblyException(
                    "Menu '" + menu.key() + "' failed variable-binding validation:\n - " + String.join("\n - ", errors));
        }
    }

    private static void validateBindings(List<KnkVariableBinding> bindings, Map<String, Class<?>> declaredContextTypes,
                                          String context, List<String> errors) {
        if (bindings == null) {
            return;
        }
        for (KnkVariableBinding binding : bindings) {
            String expression = binding.expression();
            if (expression == null) {
                continue;
            }
            Matcher matcher = MenuVariablePlaceholders.PATTERN.matcher(expression);
            while (matcher.find()) {
                validateChain(matcher.group(1), declaredContextTypes, binding, context, errors);
            }
        }
    }

    private static void validateChain(String path, Map<String, Class<?>> declaredContextTypes,
                                       KnkVariableBinding binding, String context, List<String> errors) {
        String[] hops = path.split("\\.");
        Class<?> currentType = declaredContextTypes.get(hops[0]);
        if (currentType == null) {
            errors.add("binding (id " + binding.id() + ") in " + context + ": chain '$" + path
                    + "$' references unknown root variable '" + hops[0] + "' (declared: " + declaredContextTypes.keySet() + ")");
            return;
        }

        for (int i = 1; i < hops.length; i++) {
            Method method = findMethod(currentType, hops[i]);
            if (method == null) {
                errors.add("binding (id " + binding.id() + ") in " + context + ": chain '$" + path
                        + "$' - hop '" + hops[i] + "' doesn't resolve on " + currentType.getName());
                return;
            }
            currentType = method.getReturnType();
        }
    }

    private static Method findMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * IMPLEMENTATION_PLAN.md "Load-time validation": confirms every
     * ActionBinding/ConditionBinding in the menu references a registered
     * {@link ActionRegistry}/{@link ConditionRegistry} id - the other half
     * of the "confirms every ActionBinding/ConditionBinding references a
     * registered ID" line, which Phases 1-5 left unbuilt since the
     * registries themselves didn't exist yet. Deliberately separate from
     * {@link #validate} (variable-binding validation): kept Bukkit-free by
     * taking plain {@code Set<String>} snapshots of the registries'
     * registered ids rather than the registries themselves, so this class
     * never needs to know the registries' generic context type.
     *
     * @throws MenuAssemblyException aggregating every unregistered id found
     *                                anywhere in the menu, per the same
     *                                per-menu (not per-server) failure
     *                                policy as {@link #validate}.
     */
    public static void validateActionsAndConditions(RuntimeMenu menu, Set<String> registeredActionTypeIds,
                                                      Set<String> registeredConditionTypeIds) {
        List<String> errors = new ArrayList<>();

        for (RuntimeMenuSection section : menu.sections()) {
            for (RuntimeMenuItem item : section.items()) {
                String itemContext = "item (id " + item.id() + ") in section '" + section.name()
                        + "' in menu '" + menu.key() + "'";

                checkConditions(item.conditions(), registeredConditionTypeIds, itemContext, errors);

                for (KnkActionBinding action : item.actions()) {
                    if (action.actionTypeId() == null || !registeredActionTypeIds.contains(action.actionTypeId())) {
                        errors.add("action (id " + action.id() + ", actionTypeId '" + action.actionTypeId()
                                + "') in " + itemContext + " references an unregistered ActionRegistry id");
                    }
                    String actionContext = "action (id " + action.id() + ") in " + itemContext;
                    checkConditions(action.conditions(), registeredConditionTypeIds, actionContext, errors);
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new MenuAssemblyException(
                    "Menu '" + menu.key() + "' references unregistered action/condition ids:\n - " + String.join("\n - ", errors));
        }
    }

    private static void checkConditions(List<KnkConditionBinding> conditions, Set<String> registeredConditionTypeIds,
                                         String context, List<String> errors) {
        if (conditions == null) {
            return;
        }
        for (KnkConditionBinding condition : conditions) {
            if (condition.conditionTypeId() == null || !registeredConditionTypeIds.contains(condition.conditionTypeId())) {
                errors.add("condition (id " + condition.id() + ", conditionTypeId '" + condition.conditionTypeId()
                        + "') in " + context + " references an unregistered ConditionRegistry id");
            }
        }
    }

    /**
     * IMPLEMENTATION_PLAN.md Phase 8 "Load-time validation": confirms every
     * {@code contentSourceId}-bound section references a registered
     * {@link MenuContentSourceRegistry} id, the same load-time-not-click-time
     * failure policy {@link #validateActionsAndConditions} already applies to
     * {@link ActionRegistry}/{@link ConditionRegistry} ids. Deliberately
     * separate (and Bukkit-free, taking a plain {@code Set<String>} snapshot
     * rather than the registry itself) for the same reason as that method.
     *
     * @throws MenuAssemblyException aggregating every unregistered
     *                                {@code contentSourceId} found anywhere in
     *                                the menu, per the same per-menu (not
     *                                per-server) failure policy.
     */
    public static void validateContentSources(RuntimeMenu menu, Set<String> registeredContentSourceIds) {
        List<String> errors = new ArrayList<>();

        for (RuntimeMenuSection section : menu.sections()) {
            if (section.hasContentSource() && !registeredContentSourceIds.contains(section.contentSourceId())) {
                errors.add("section '" + section.name() + "' (id " + section.id() + ") in menu '" + menu.key()
                        + "' references an unregistered MenuContentSourceRegistry id '" + section.contentSourceId() + "'");
            }
        }

        if (!errors.isEmpty()) {
            throw new MenuAssemblyException(
                    "Menu '" + menu.key() + "' references unregistered content source ids:\n - " + String.join("\n - ", errors));
        }
    }
}
