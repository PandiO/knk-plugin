package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * InventoryMenu Phase 9: the Bukkit-free half of rendering one section's items
 * - everything between "which item/row goes in which slot" and "build the
 * {@code ItemStack}". knk-paper's {@code MenuRenderer} computes the slot
 * assignment and the variable scopes, calls this, and maps each
 * {@link RenderedSlot#presentation()} onto an {@code ItemStack}.
 * <p>
 * Per item (and, for a row template, per row - E3):
 * <ol>
 *   <li>{@code visibilityPermission} (Phase 4) - hidden when denied;</li>
 *   <li>item-level {@link MenuConditionPhase#RENDER} conditions (E5) - hidden
 *       when any denies; a condition that throws hides the item and is logged
 *       (a broken condition must not break the whole render);</li>
 *   <li>action-level Render conditions (E5) - denied actions are dropped from
 *       the rendered snapshot;</li>
 *   <li>bindings resolved into a {@link MenuItemPresentation} (E6/E7/E8), row
 *       bindings cached per row position ({@link VariableResolver.RowScope});</li>
 *   <li>effective display mode {@code HIDDEN} - hidden.</li>
 * </ol>
 * A hidden row leaves its slot empty - rows are never compacted, so the page
 * still matches what the content source paged (IMPLEMENTATION_PLAN.md Phase 9 J7).
 */
public final class MenuSectionRenderer {

    private static final Logger LOGGER = Logger.getLogger(MenuSectionRenderer.class.getName());

    private MenuSectionRenderer() {
    }

    /**
     * One rendered slot. {@code item} is the rendered snapshot (effective display
     * mode, Render-filtered actions) that click routing must use; {@code row} is
     * the row object for a row-template slot (null otherwise); {@code scope} is
     * the variable scope the slot was rendered with.
     */
    public record RenderedSlot(int slot, RuntimeMenuItem item, Object row, MenuItemPresentation presentation,
                               Map<String, Object> scope) {
    }

    /** Everything the per-item steps need that doesn't vary per item. */
    public record Pass<C>(MenuSession session, long currentTick, Predicate<String> permissionChecker,
                          ConditionRegistry<C> conditionRegistry,
                          BiFunction<RuntimeMenuItem, Map<String, Object>, C> conditionContextFactory) {
    }

    /** Renders ordinary (pinned or in-memory auto-filled) items: {@code slot → item}. */
    public static <C> List<RenderedSlot> renderItems(Map<Integer, RuntimeMenuItem> itemsBySlot,
                                                     Map<String, Object> sectionScope, Pass<C> pass) {
        List<RenderedSlot> rendered = new ArrayList<>(itemsBySlot.size());
        for (Map.Entry<Integer, RuntimeMenuItem> entry : itemsBySlot.entrySet()) {
            RenderedSlot slot = renderOne(entry.getKey(), entry.getValue(), null, sectionScope, null, pass);
            if (slot != null) {
                rendered.add(slot);
            }
        }
        return rendered;
    }

    /**
     * Renders {@code rows} through {@code rowTemplate} into {@code slots} (the
     * section's free auto-fill slots, in order): row {@code i} goes to
     * {@code slots.get(i)}, with a child scope binding {@code $row$} to it.
     * Extra rows beyond the slot count are ignored.
     */
    public static <C> List<RenderedSlot> renderRows(RuntimeMenuSection section, RuntimeMenuItem rowTemplate,
                                                    List<Integer> slots, List<?> rows,
                                                    MenuVariableScope sectionScope, Pass<C> pass) {
        List<RenderedSlot> rendered = new ArrayList<>();
        for (int i = 0; i < rows.size() && i < slots.size(); i++) {
            Object row = rows.get(i);
            MenuVariableScope rowScope = sectionScope.with(MenuVariableProviderRegistry.ROOT_ROW, row);
            VariableResolver.RowScope cacheScope = VariableResolver.RowScope.forRow(section.id(), i, row);
            RenderedSlot slot = renderOne(slots.get(i), rowTemplate, row, rowScope, cacheScope, pass);
            if (slot != null) {
                rendered.add(slot);
            }
        }
        return rendered;
    }

    private static <C> RenderedSlot renderOne(int slot, RuntimeMenuItem item, Object row, Map<String, Object> scope,
                                              VariableResolver.RowScope cacheScope, Pass<C> pass) {
        if (item.displayMode() == MenuDisplayMode.HIDDEN && !VariableResolver.hasBinding(item.variableBindings(),
                MenuItemPresentation.DISPLAY_MODE)) {
            return null;
        }
        if (pass.permissionChecker() != null && !item.isVisibleTo(pass.permissionChecker())) {
            return null;
        }

        List<KnkActionBinding> actions = item.actions();
        boolean hasItemRenderConditions = MenuConditionEvaluator.hasPhase(item.conditions(), MenuConditionPhase.RENDER);
        boolean hasActionRenderConditions = actions.stream()
                .anyMatch(action -> MenuConditionEvaluator.hasPhase(action.conditions(), MenuConditionPhase.RENDER));
        if (hasItemRenderConditions || hasActionRenderConditions) {
            try {
                C context = pass.conditionContextFactory().apply(item, scope);
                if (hasItemRenderConditions && !MenuConditionEvaluator.evaluate(item.conditions(), MenuConditionPhase.RENDER,
                        pass.conditionRegistry(), context, scope).allowed()) {
                    return null;
                }
                if (hasActionRenderConditions) {
                    actions = MenuConditionEvaluator.actionsAllowedAtRender(actions, pass.conditionRegistry(), context, scope);
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Render condition on menu item (id " + item.id() + ") failed - hiding the item", e);
                return null;
            }
        }

        MenuItemPresentation presentation = MenuItemPresentation.resolve(item, pass.session(), scope, pass.currentTick(), cacheScope);
        if (presentation.displayMode() == MenuDisplayMode.HIDDEN) {
            return null;
        }
        return new RenderedSlot(slot, item.withRenderState(presentation.displayMode(), actions), row, presentation, scope);
    }
}
