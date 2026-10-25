package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.dataaccess.MenuTemplatesDataAccess;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplateSummary;
import net.knightsandkings.knk.core.menu.MenuAssemblyException;
import net.knightsandkings.knk.core.menu.MenuDefinitionValidator;
import net.knightsandkings.knk.core.menu.MenuTemplateAssembler;
import net.knightsandkings.knk.core.menu.RuntimeMenu;

import java.util.List;
import java.util.Map;
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

    /**
     * InventoryMenu Phase 9 (E2): locks every menu registry <em>first</em> - from
     * here on a registration throws, so nothing can be registered after the
     * menus were validated without it - then validates every menu against the
     * registries' declared variable types and content-source row types.
     */
    /** Schedules a retry: {@code task} runs off the main thread after {@code delayTicks} ({@link MenuService#runLaterAsync}). */
    @FunctionalInterface
    interface Scheduler {
        void later(long delayTicks, Runnable task);
    }

    public static void runAtStartup(MenuTemplatesDataAccess menuTemplatesDataAccess, MenuService menuService, Logger logger,
                                     MenuFeatureRegistries registries) {
        registries.lockAll();
        runAtStartup(menuTemplatesDataAccess, menuService, logger, menuService::runLaterAsync,
                registries.actions().registeredIds(), registries.conditions().registeredIds(),
                registries.contentSources().registeredIds(), registries.variables().declaredTypes(),
                registries.contentSources().rowTypes());
    }

    /** Seconds between retries while the API is down or templates can't be fetched: 10, 20, 40, then every minute. */
    static final int[] RETRY_DELAYS_SECONDS = {10, 20, 40, 60};

    static void runAtStartup(MenuTemplatesDataAccess menuTemplatesDataAccess, MenuService menuService, Logger logger,
                             Scheduler scheduler,
                             Set<String> registeredActionTypeIds, Set<String> registeredConditionTypeIds,
                             Set<String> registeredContentSourceIds, Map<String, Class<?>> declaredTypes,
                             Map<String, Class<?>> rowTypes) {
        // Menus not listed now (API hiccup, created later) can still be validated on demand, the same way.
        menuService.setLateValidator(key -> validateOne(key, menuTemplatesDataAccess, menuService, logger,
                registeredActionTypeIds, registeredConditionTypeIds, registeredContentSourceIds, declaredTypes, rowTypes));
        // The API came back (ApiConnectivityChangedEvent recovery): run the pass again at once for what is still missing.
        menuService.setRevalidation(() -> validateAll(menuTemplatesDataAccess, menuService, logger, scheduler,
                registeredActionTypeIds, registeredConditionTypeIds, registeredContentSourceIds, declaredTypes, rowTypes, 0));
        validateAll(menuTemplatesDataAccess, menuService, logger, scheduler, registeredActionTypeIds, registeredConditionTypeIds,
                registeredContentSourceIds, declaredTypes, rowTypes, 0);
    }

    /** What validating one key did. */
    enum Outcome { VALIDATED, BLOCKED, UNFETCHED }

    /**
     * Lists and validates every menu not validated or blocked yet. Playtest 2026-10-24: with the API down at plugin
     * start (dev order: server first, API a moment later) every menu stayed unavailable until a restart. The first
     * version retried only the list; on the dev server the list came back once the API was up while every per-key
     * fetch still failed (the connectivity monitor still reported the API down, so the data access refused the
     * calls), and the pass ended with nothing validated and no further retry. Now a pass retries (10 s, 20 s, 40 s,
     * then every minute) until every listed menu is validated or blocked - a template that couldn't be fetched
     * counts as unfinished - and the API-recovery event starts a pass at once. One pass runs at a time.
     */
    static void validateAll(MenuTemplatesDataAccess menuTemplatesDataAccess, MenuService menuService, Logger logger,
                            Scheduler scheduler,
                            Set<String> registeredActionTypeIds, Set<String> registeredConditionTypeIds,
                            Set<String> registeredContentSourceIds, Map<String, Class<?>> declaredTypes,
                            Map<String, Class<?>> rowTypes, int attempt) {
        if (!menuService.beginValidationPass()) {
            return; // another pass is running; it reschedules itself if anything is left
        }
        boolean retryScheduled = false;
        try {
            List<KnkMenuTemplateSummary> summaries;
            try {
                summaries = menuTemplatesDataAccess.listAllAsync().join();
            } catch (Exception e) {
                int delay = RETRY_DELAYS_SECONDS[Math.min(attempt, RETRY_DELAYS_SECONDS.length - 1)];
                if (attempt == 0) {
                    logger.log(Level.SEVERE, "InventoryMenu startup validation: failed to list menu templates (API down?);"
                            + " menus stay unavailable until a retry succeeds - next try in " + delay + " s", e);
                } else if (attempt < 3 || attempt % 10 == 0) {
                    logger.warning("InventoryMenu startup validation: menu templates still can't be listed (try "
                            + (attempt + 1) + "); next try in " + delay + " s");
                }
                retryScheduled = true;
                scheduleRetry(menuTemplatesDataAccess, menuService, logger, scheduler, registeredActionTypeIds,
                        registeredConditionTypeIds, registeredContentSourceIds, declaredTypes, rowTypes, attempt, delay);
                return;
            }

            int checked = 0;
            int blocked = 0;
            int unfetched = 0;
            int alreadyDone = 0;
            for (KnkMenuTemplateSummary summary : summaries) {
                String key = summary.key();
                if (key == null || key.isBlank()) {
                    continue;
                }
                if (menuService.isMenuAvailable(key) || menuService.blockedMenus().containsKey(key)) {
                    alreadyDone++;
                    continue;
                }
                checked++;
                switch (validateOne(key, menuTemplatesDataAccess, menuService, logger, registeredActionTypeIds,
                        registeredConditionTypeIds, registeredContentSourceIds, declaredTypes, rowTypes)) {
                    case BLOCKED -> blocked++;
                    case UNFETCHED -> unfetched++;
                    case VALIDATED -> { }
                }
            }
            if (unfetched > 0) {
                int delay = RETRY_DELAYS_SECONDS[Math.min(attempt, RETRY_DELAYS_SECONDS.length - 1)];
                if (attempt < 3 || attempt % 10 == 0) {
                    logger.warning("InventoryMenu startup validation: " + unfetched + " of " + checked
                            + " menu(s) couldn't be fetched (API down or not reachable yet); they stay unavailable - next try in "
                            + delay + " s");
                }
                retryScheduled = true;
                scheduleRetry(menuTemplatesDataAccess, menuService, logger, scheduler, registeredActionTypeIds,
                        registeredConditionTypeIds, registeredContentSourceIds, declaredTypes, rowTypes, attempt, delay);
                return;
            }
            logger.info("InventoryMenu startup validation: checked " + checked + " menu(s), " + blocked + " blocked"
                    + (alreadyDone > 0 ? ", " + alreadyDone + " already validated" : "")
                    + (attempt > 0 ? " (after " + attempt + " retr" + (attempt == 1 ? "y" : "ies") + ")" : "") + ".");
        } finally {
            menuService.endValidationPass(retryScheduled);
        }
    }

    private static void scheduleRetry(MenuTemplatesDataAccess menuTemplatesDataAccess, MenuService menuService, Logger logger,
                                      Scheduler scheduler,
                                      Set<String> registeredActionTypeIds, Set<String> registeredConditionTypeIds,
                                      Set<String> registeredContentSourceIds, Map<String, Class<?>> declaredTypes,
                                      Map<String, Class<?>> rowTypes, int attempt, int delaySeconds) {
        scheduler.later(delaySeconds * 20L, () -> {
            menuService.retryDue();
            validateAll(menuTemplatesDataAccess, menuService, logger, scheduler, registeredActionTypeIds,
                    registeredConditionTypeIds, registeredContentSourceIds, declaredTypes, rowTypes, attempt + 1);
        });
    }

    /** Validates one key: registered as validated or blocked in {@code menuService}; UNFETCHED when the template couldn't be read. */
    static Outcome validateOne(String key, MenuTemplatesDataAccess menuTemplatesDataAccess,
                               MenuService menuService, Logger logger,
                               Set<String> registeredActionTypeIds, Set<String> registeredConditionTypeIds,
                               Set<String> registeredContentSourceIds, Map<String, Class<?>> declaredTypes,
                               Map<String, Class<?>> rowTypes) {
        try {
            KnkMenuTemplate template;
            try {
                template = menuTemplatesDataAccess.getByKeyAsync(key).join().value().orElse(null);
            } catch (Exception e) {
                template = null;
            }
            if (template == null) {
                logger.warning("InventoryMenu startup validation: menu '" + key
                        + "' was listed but couldn't be fetched; it stays unavailable until a retry fetches it.");
                return Outcome.UNFETCHED;
            }

            RuntimeMenu menu = MenuTemplateAssembler.assemble(template);
            MenuDefinitionValidator.validate(menu, declaredTypes, rowTypes);
            // IMPLEMENTATION_PLAN.md Phase 6 "Load-time validation": the other
            // half of "confirms every ActionBinding/ConditionBinding
            // references a registered ID" - unbuilt until now since the
            // registries themselves didn't exist before this phase.
            MenuDefinitionValidator.validateActionsAndConditions(menu, registeredActionTypeIds, registeredConditionTypeIds);
            // IMPLEMENTATION_PLAN.md Phase 8: same load-time-not-click-time
            // policy for contentSourceId references.
            MenuDefinitionValidator.validateContentSources(menu, registeredContentSourceIds);
            menuService.markValidated(key);
            return Outcome.VALIDATED;
        } catch (MenuAssemblyException e) {
            logger.severe("InventoryMenu startup validation: menu '" + key + "' is broken and will refuse to open: "
                    + e.getMessage());
            menuService.blockMenu(key, e.getMessage());
            return Outcome.BLOCKED;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "InventoryMenu startup validation: unexpected error validating menu '" + key + "'", e);
            menuService.blockMenu(key, e.getMessage());
            return Outcome.BLOCKED;
        }
    }
}
