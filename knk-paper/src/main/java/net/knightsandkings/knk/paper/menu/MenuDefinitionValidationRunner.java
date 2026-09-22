package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.dataaccess.MenuTemplatesDataAccess;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplateSummary;
import net.knightsandkings.knk.core.menu.MenuAssemblyException;
import net.knightsandkings.knk.core.menu.MenuDefinitionValidator;
import net.knightsandkings.knk.core.menu.MenuTemplateAssembler;
import net.knightsandkings.knk.core.menu.RuntimeMenu;

import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Load-time validation entry point (IMPLEMENTATION_PLAN.md Phase 3,
 * DESIGN_REVIEW.md §1): walks every registered {@code MenuTemplate} once at
 * plugin enable - not lazily on first render - assembling and validating
 * each one's variable bindings against {@link MenuVariableContext#DECLARED_TYPES}.
 * A menu that fails either step is logged loudly and blocked in
 * {@link MenuService} so every later open attempt refuses immediately
 * instead of re-discovering (and re-paying the reflection cost of) the same
 * failure on every player's click; a broken menu never takes the rest of the
 * server down, per DESIGN_REVIEW.md's decided per-menu failure policy. This
 * directly targets reconciliation bug #7's actual failure mode: not that the
 * substitution bug existed, but that nothing caught it for years.
 * <p>
 * Runs synchronously (blocking on the data-access futures) during
 * {@code onEnable}, since the whole point is for validation to finish before
 * the plugin - and therefore any player - can open a menu.
 */
public final class MenuDefinitionValidationRunner {

    private MenuDefinitionValidationRunner() {
    }

    public static void runAtStartup(MenuTemplatesDataAccess menuTemplatesDataAccess, MenuService menuService, Logger logger,
                                     Set<String> registeredActionTypeIds, Set<String> registeredConditionTypeIds) {
        List<KnkMenuTemplateSummary> summaries;
        try {
            summaries = menuTemplatesDataAccess.listAllAsync().join();
        } catch (Exception e) {
            logger.log(Level.SEVERE,
                    "InventoryMenu startup validation: failed to list menu templates, skipping validation entirely", e);
            return;
        }

        int checked = 0;
        int blocked = 0;
        for (KnkMenuTemplateSummary summary : summaries) {
            if (summary.key() == null || summary.key().isBlank()) {
                continue;
            }
            checked++;
            if (!validateOne(summary.key(), menuTemplatesDataAccess, menuService, logger,
                    registeredActionTypeIds, registeredConditionTypeIds)) {
                blocked++;
            }
        }
        logger.info("InventoryMenu startup validation: checked " + checked + " menu(s), " + blocked + " blocked.");
    }

    /** @return true if the menu registered cleanly (or couldn't be re-fetched - not this validator's job to flag that). */
    private static boolean validateOne(String key, MenuTemplatesDataAccess menuTemplatesDataAccess,
                                        MenuService menuService, Logger logger,
                                        Set<String> registeredActionTypeIds, Set<String> registeredConditionTypeIds) {
        try {
            KnkMenuTemplate template = menuTemplatesDataAccess.getByKeyAsync(key).join().value().orElse(null);
            if (template == null) {
                logger.warning("InventoryMenu startup validation: menu '" + key
                        + "' was listed but couldn't be re-fetched; skipping.");
                return true;
            }

            RuntimeMenu menu = MenuTemplateAssembler.assemble(template);
            MenuDefinitionValidator.validate(menu, MenuVariableContext.DECLARED_TYPES);
            // IMPLEMENTATION_PLAN.md Phase 6 "Load-time validation": the other
            // half of "confirms every ActionBinding/ConditionBinding
            // references a registered ID" - unbuilt until now since the
            // registries themselves didn't exist before this phase.
            MenuDefinitionValidator.validateActionsAndConditions(menu, registeredActionTypeIds, registeredConditionTypeIds);
            return true;
        } catch (MenuAssemblyException e) {
            logger.severe("InventoryMenu startup validation: menu '" + key + "' is broken and will refuse to open: "
                    + e.getMessage());
            menuService.blockMenu(key, e.getMessage());
            return false;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "InventoryMenu startup validation: unexpected error validating menu '" + key + "'", e);
            menuService.blockMenu(key, e.getMessage());
            return false;
        }
    }
}
