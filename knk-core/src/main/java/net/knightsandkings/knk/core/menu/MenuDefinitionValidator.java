package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkConditionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
        validate(menu, declaredContextTypes, Map.of());
    }

    /**
     * InventoryMenu Phase 9: {@link #validate(RuntimeMenu, Map)} plus
     * <ul>
     *   <li>engine roots - {@code ctx} ({@link MenuContextParams}: any hop is a
     *       key, typed {@code String}), {@code menu} ({@link MenuView}) and
     *       {@code section} ({@link SectionView}) - are always declared (E1/E9);</li>
     *   <li>{@code $row$} on a row template is checked against the declared row
     *       type of the section's content source ({@code rowTypesBySourceId},
     *       from {@link MenuContentSourceRegistry#rowTypes()}), and is an error
     *       anywhere else; a row source without a row template, a row template
     *       without a row source, and two row templates in one section are
     *       errors (E3);</li>
     *   <li>placeholders inside the values of action/condition params and of the
     *       section's content-source params are validated like binding
     *       expressions (E3).</li>
     * </ul>
     */
    public static void validate(RuntimeMenu menu, Map<String, Class<?>> declaredContextTypes,
                                Map<String, Class<?>> rowTypesBySourceId) {
        List<String> errors = new ArrayList<>();
        Map<String, Class<?>> baseTypes = withEngineRoots(declaredContextTypes);
        Map<String, Class<?>> rowTypes = rowTypesBySourceId != null ? rowTypesBySourceId : Map.of();

        for (RuntimeMenuSection section : menu.sections()) {
            String sectionContext = "section '" + section.name() + "' (id " + section.id() + ") in menu '" + menu.key() + "'";
            validateBindings(section.variableBindings(), baseTypes, sectionContext, errors);
            section.contentSourceParams().forEach((key, value) -> validateText(value, baseTypes,
                    "content-source param '" + key + "' of " + sectionContext, errors));

            Class<?> rowType = section.hasContentSource() ? rowTypes.get(section.contentSourceId()) : null;
            long rowTemplateCount = section.items().stream().filter(RuntimeMenuItem::rowTemplate).count();
            if (rowTemplateCount > 1) {
                errors.add(sectionContext + " has " + rowTemplateCount + " row templates; at most one is allowed");
            }
            if (rowTemplateCount > 0 && !section.hasContentSource()) {
                errors.add(sectionContext + " has a row template but no content source to supply rows");
            } else if (rowTemplateCount > 0 && rowType == null) {
                errors.add(sectionContext + " has a row template but content source '" + section.contentSourceId()
                        + "' doesn't declare a row type (it isn't registered with registerRows)");
            } else if (rowTemplateCount == 0 && rowType != null) {
                errors.add(sectionContext + ": content source '" + section.contentSourceId() + "' yields "
                        + rowType.getSimpleName() + " rows, so the section needs a row template (IsRowTemplate)");
            }

            for (RuntimeMenuItem item : section.items()) {
                String itemContext = "item (id " + item.id() + ") in section '" + section.name() + "' in menu '" + menu.key() + "'";
                Map<String, Class<?>> itemTypes = baseTypes;
                if (item.rowTemplate() && rowType != null) {
                    itemTypes = new HashMap<>(baseTypes);
                    itemTypes.put(MenuVariableProviderRegistry.ROOT_ROW, rowType);
                }
                Map<String, Class<?>> types = itemTypes;
                validateBindings(item.variableBindings(), types, itemContext, errors);
                validateConditionParams(item.conditions(), types, itemContext, errors);
                for (KnkActionBinding action : item.actions()) {
                    String actionContext = "action (id " + action.id() + ", '" + action.actionTypeId() + "') in " + itemContext;
                    MenuParams.parse(action.paramsJson()).forEach((key, value) -> validateText(value, types,
                            "param '" + key + "' of " + actionContext, errors));
                    validateConditionParams(action.conditions(), types, actionContext, errors);
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new MenuAssemblyException(
                    "Menu '" + menu.key() + "' failed variable-binding validation:\n - " + String.join("\n - ", errors));
        }
    }

    private static Map<String, Class<?>> withEngineRoots(Map<String, Class<?>> declaredContextTypes) {
        Map<String, Class<?>> types = new HashMap<>();
        types.put(MenuVariableProviderRegistry.ROOT_CTX, MenuContextParams.class);
        types.put(MenuVariableProviderRegistry.ROOT_MENU, MenuView.class);
        types.put(MenuVariableProviderRegistry.ROOT_SECTION, SectionView.class);
        if (declaredContextTypes != null) {
            types.putAll(declaredContextTypes);
        }
        return types;
    }

    private static void validateConditionParams(List<KnkConditionBinding> conditions, Map<String, Class<?>> types,
                                                String context, List<String> errors) {
        if (conditions == null) {
            return;
        }
        for (KnkConditionBinding condition : conditions) {
            String conditionContext = "condition (id " + condition.id() + ", '" + condition.conditionTypeId() + "') in " + context;
            MenuParams.parse(condition.paramsJson()).forEach((key, value) -> validateText(value, types,
                    "param '" + key + "' of " + conditionContext, errors));
        }
    }

    private static void validateBindings(List<KnkVariableBinding> bindings, Map<String, Class<?>> declaredContextTypes,
                                          String context, List<String> errors) {
        if (bindings == null) {
            return;
        }
        for (KnkVariableBinding binding : bindings) {
            validateText(binding.expression(), declaredContextTypes, "binding (id " + binding.id() + ") in " + context, errors);
        }
    }

    private static void validateText(String text, Map<String, Class<?>> declaredContextTypes, String where,
                                     List<String> errors) {
        if (text == null) {
            return;
        }
        Matcher matcher = MenuVariablePlaceholders.PATTERN.matcher(text);
        while (matcher.find()) {
            validateChain(matcher.group(1), declaredContextTypes, where, errors);
        }
    }

    private static void validateChain(String path, Map<String, Class<?>> declaredContextTypes,
                                       String where, List<String> errors) {
        String[] hops = path.split("\\.");
        Class<?> currentType = declaredContextTypes.get(hops[0]);
        if (currentType == null) {
            if (MenuVariableProviderRegistry.ROOT_ROW.equals(hops[0])) {
                errors.add(where + ": chain '$" + path + "$' uses $row$, which only exists on a row template"
                        + " (IsRowTemplate) whose content source declares a row type");
            } else {
                errors.add(where + ": chain '$" + path + "$' references unknown root variable '" + hops[0]
                        + "' (declared: " + new TreeSet<>(declaredContextTypes.keySet()) + ")");
            }
            return;
        }

        for (int i = 1; i < hops.length; i++) {
            if (MenuContextParams.class.equals(currentType)) {
                // E1: $ctx.<name>$ is a key lookup, always a String.
                currentType = String.class;
                continue;
            }
            if (Map.class.isAssignableFrom(currentType)) {
                // A Map-typed hop is a key lookup at runtime; the value type is unknowable here.
                currentType = Object.class;
                continue;
            }
            Method method = findMethod(currentType, hops[i]);
            if (method == null) {
                errors.add(where + ": chain '$" + path + "$' - hop '" + hops[i] + "' doesn't resolve on " + currentType.getName());
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
