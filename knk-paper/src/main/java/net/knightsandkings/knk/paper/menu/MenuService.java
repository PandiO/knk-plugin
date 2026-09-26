package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.dataaccess.MenuTemplatesDataAccess;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplate;
import net.knightsandkings.knk.core.menu.MenuAssemblyException;
import net.knightsandkings.knk.core.menu.MenuContentQuery;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.MenuRefreshSchedule;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.menu.MenuSessionRegistry;
import net.knightsandkings.knk.core.menu.MenuTemplateAssembler;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
import net.knightsandkings.knk.paper.utils.DisplayTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Paper-side orchestrator tying together the Phase 1 data access, the Phase 2
 * assembly/layout/pagination engine, and the live Bukkit Inventory - the
 * entry point commands and the click listener actually call.
 * <p>
 * <b>Threading.</b> Template fetch + assembly + template material-ref lookups
 * run inside {@code runTaskAsynchronously} (IMPLEMENTATION_PLAN.md Phase 2's
 * async-rendering requirement, the fix for bug #6); everything that touches
 * live game state - variable providers, content sources, conditions, the
 * {@code Inventory} - runs on the main thread (InventoryMenu Phase 9 §9.0; see
 * {@link MenuRenderer}).
 * <p>
 * <b>InventoryMenu Phase 9</b> additions:
 * <ul>
 *   <li>{@link #openMenu(Player, String, MenuContextParams)} (E1) - ctx params;
 *       pushes onto the nav stack when opened from inside a KnK menu, starts a
 *       fresh stack (Back = "Exit") otherwise;</li>
 *   <li>{@link #goBack} (E9) - backs {@code menu.back};</li>
 *   <li>{@link #refreshOpenMenus} (E4) - event-driven refresh, and
 *       {@link #processDueRefreshes} - the per-tick auto-refresh step, both
 *       batched through one {@link MenuRefreshSchedule} and re-rendering from
 *       the open menu's cached template (no re-fetch).</li>
 * </ul>
 */
public final class MenuService {

    private final Plugin plugin;
    private final Logger logger;
    private final MenuTemplatesDataAccess menuTemplatesDataAccess;
    private final MenuSessionRegistry sessionRegistry;
    private final OpenMenuContextRegistry openMenuContextRegistry;
    private final MenuRenderer renderer;
    private final AnvilCaptureManager anvilCaptureManager;
    private final MenuRefreshSchedule refreshSchedule;
    private final Executor mainThread;
    private final Map<String, String> blockedMenus = new ConcurrentHashMap<>();
    /** Keys that passed {@link MenuDefinitionValidationRunner} at startup (content port CP1, {@code menu-available}). */
    private final Set<String> validatedMenus = ConcurrentHashMap.newKeySet();

    public MenuService(
            Plugin plugin,
            MenuTemplatesDataAccess menuTemplatesDataAccess,
            MenuSessionRegistry sessionRegistry,
            OpenMenuContextRegistry openMenuContextRegistry,
            MenuRenderer renderer,
            AnvilCaptureManager anvilCaptureManager,
            MenuRefreshSchedule refreshSchedule
    ) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.menuTemplatesDataAccess = menuTemplatesDataAccess;
        this.sessionRegistry = sessionRegistry;
        this.openMenuContextRegistry = openMenuContextRegistry;
        this.renderer = renderer;
        this.anvilCaptureManager = anvilCaptureManager;
        this.refreshSchedule = refreshSchedule;
        this.mainThread = mainThreadExecutor(plugin);
    }

    /**
     * Runs a task on the server main thread - inline when the caller already is
     * on it (so a render whose content sources answer synchronously completes
     * within the same tick), otherwise via {@code runTask}.
     */
    public static Executor mainThreadExecutor(Plugin plugin) {
        return task -> {
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        };
    }

    public void openMenu(Player player, String templateKey) {
        openMenu(player, templateKey, MenuContextParams.EMPTY);
    }

    /**
     * InventoryMenu Phase 9 (E1): opens {@code templateKey} with context
     * parameters - the entry point for commands ({@code /siege info 3}) and for
     * {@code menu.open}. If the player is currently looking at a KnK menu the
     * current entry is pushed onto the back stack; otherwise (a command, a
     * respawn hook) navigation starts fresh, so the Back button reads "Exit".
     */
    public void openMenu(Player player, String templateKey, MenuContextParams context) {
        MenuContextParams ctx = context != null ? context : MenuContextParams.EMPTY;
        boolean fromOpenMenu = isViewingKnkMenu(player);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            RuntimeMenu menu = loadAndAssemble(player, templateKey);
            if (menu == null) {
                return;
            }
            Map<Integer, String> materialKeys = renderer.resolveTemplateMaterialKeys(menu).join();

            mainThread.execute(() -> {
                MenuSession session = sessionRegistry.open(player.getUniqueId());
                if (fromOpenMenu) {
                    session.navigateTo(menu.key(), ctx, menu.title());
                } else {
                    session.openAsRoot(menu.key(), ctx, menu.title());
                }
                renderAndShow(player, menu, session, materialKeys);
            });
        });
    }

    /**
     * InventoryMenu Phase 9 (E9): {@code menu.back}. Re-opens the previous nav
     * entry (key and ctx, no push); with nothing to go back to, closes the menu
     * and resets navigation.
     */
    public void goBack(Player player) {
        Optional<MenuSession> sessionOpt = sessionRegistry.get(player.getUniqueId());
        Optional<MenuSession.NavigationEntry> previous = sessionOpt.flatMap(MenuSession::goBackEntry);
        if (previous.isEmpty()) {
            sessionOpt.ifPresent(MenuSession::resetNavigation);
            player.closeInventory();
            return;
        }

        MenuSession session = sessionOpt.get();
        String key = previous.get().key();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            RuntimeMenu menu = loadAndAssemble(player, key);
            if (menu == null) {
                return;
            }
            Map<Integer, String> materialKeys = renderer.resolveTemplateMaterialKeys(menu).join();
            mainThread.execute(() -> renderAndShow(player, menu, session, materialKeys));
        });
    }

    /**
     * InventoryMenu Phase 9 (E4): event-driven refresh - marks every matching
     * open menu's session dirty (so {@code OnDirty} bindings re-resolve) and
     * schedules it for a re-render on the next tick, batched with any
     * auto-refresh due that tick. Call from any thread; e.g. Siege on
     * join/leave/vote/phase change:
     * {@code refreshOpenMenus(ctx -> ctx.menuKey().startsWith("siege."))}.
     *
     * @return how many open menus matched
     */
    public int refreshOpenMenus(Predicate<OpenMenuContext> filter) {
        int matched = 0;
        for (OpenMenuContext context : openMenuContextRegistry.all()) {
            if (!filter.test(context)) {
                continue;
            }
            UUID playerId = context.player().getUniqueId();
            sessionRegistry.get(playerId).ifPresent(MenuSession::markDirty);
            refreshSchedule.markStale(playerId);
            matched++;
        }
        return matched;
    }

    /** Convenience for {@link #refreshOpenMenus} limited to one player. */
    public void refreshOpenMenu(Player player) {
        refreshOpenMenus(context -> context.player().getUniqueId().equals(player.getUniqueId()));
    }

    /**
     * InventoryMenu Phase 9 (E4): one tick of live repaint - re-renders every
     * open menu whose {@code AutoRefreshTicks} elapsed or that was marked stale,
     * all in this tick. Called by {@link MenuAutoRefreshTask} on the main thread.
     */
    public void processDueRefreshes() {
        long now = MenuRenderer.currentTick();
        for (UUID playerId : refreshSchedule.due(now)) {
            Optional<OpenMenuContext> context = openMenuContextRegistry.get(playerId);
            Optional<MenuSession> session = sessionRegistry.get(playerId);
            if (context.isEmpty() || session.isEmpty()
                    || !context.get().player().isOnline()
                    || !context.get().player().getOpenInventory().getTopInventory().equals(context.get().inventory())) {
                refreshSchedule.untrack(playerId);
                continue;
            }
            refresh(context.get(), session.get());
        }
    }

    /** Re-renders an open menu in place from its cached template and material keys (no re-fetch). */
    private void refresh(OpenMenuContext context, MenuSession session) {
        Player player = context.player();
        UUID playerId = player.getUniqueId();
        RuntimeMenu menu = context.menu();
        safeRender(menu, session, player, context.materialNamespaceKeys())
                .whenCompleteAsync((result, error) -> {
                    refreshSchedule.completed(playerId, MenuRenderer.currentTick());
                    if (error != null) {
                        logger.log(Level.WARNING, "Refreshing menu '" + menu.key() + "' for " + player.getName() + " failed", error);
                        return;
                    }
                    Optional<OpenMenuContext> current = openMenuContextRegistry.get(playerId);
                    if (current.isPresent() && current.get() == context
                            && player.getOpenInventory().getTopInventory().equals(context.inventory())) {
                        if (context.inventory().getSize() != result.totalSlots()) {
                            // Menu follow-up 2026-09-26: a DYNAMIC menu changed height - reopen at the new size.
                            show(player, menu, result);
                            return;
                        }
                        renderer.applyToInventory(context.inventory(), result, player.isSneaking());
                        context.update(menu, result);
                    }
                }, mainThread);
    }

    /**
     * Advances {@code sectionName}'s page. Re-renders into the already-open
     * Inventory when the player still has the menu open; otherwise re-fetches
     * and reopens it fresh with the new page. The fallback isn't an edge case
     * - it's the common case for the {@code /knk menu page} command: vanilla
     * Minecraft doesn't let a player type a chat command while a custom
     * Inventory screen has focus, so by the time it runs the
     * {@link OpenMenuContext} is gone; the {@code MenuSession} (current menu
     * key + ctx, per-section page) survives that close, which is what makes
     * the fallback possible.
     */
    public void nextPage(Player player, String sectionName) {
        changePage(player, sectionName, true);
    }

    public void previousPage(Player player, String sectionName) {
        changePage(player, sectionName, false);
    }

    /**
     * Post-Phase-8 QOL follow-up: shift-click-on-pagination-button shortcut -
     * jumps straight to page 0 regardless of section kind.
     */
    public void firstPage(Player player, String sectionName) {
        mutateSectionPage(player, sectionName, (menu, section, session) -> session.firstPage(section.id()));
    }

    private void changePage(Player player, String sectionName, boolean forward) {
        mutateSectionPage(player, sectionName, (menu, section, session) -> {
            int sectionId = section.id();
            if (section.hasContentSource()) {
                // Advance/retreat unclamped - MenuRenderer wraps it once the real
                // paged fetch reveals the true page count (Phase 8 QOL follow-up).
                session.stepPage(sectionId, forward ? 1 : -1);
            } else {
                int currentTotalPages = section
                        .resolveSlots(menu.totalSlots(), session.getPage(sectionId))
                        .totalPages();
                if (forward) {
                    session.nextPage(sectionId, currentTotalPages);
                } else {
                    session.previousPage(sectionId, currentTotalPages);
                }
            }
        });
    }

    /**
     * Shared plumbing for every section-page mutation: re-fetches the current
     * menu (see {@link #nextPage}), resolves the named section, applies
     * {@code mutation} on the main thread, then re-renders.
     */
    private void mutateSectionPage(Player player, String sectionName, SectionPageMutation mutation) {
        Optional<MenuSession> sessionOpt = sessionRegistry.get(player.getUniqueId());
        Optional<String> currentMenuKey = sessionOpt.flatMap(MenuSession::currentMenuKey);
        if (sessionOpt.isEmpty() || currentMenuKey.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "You don't have a menu open.");
            return;
        }
        MenuSession session = sessionOpt.get();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            RuntimeMenu menu = loadAndAssemble(player, currentMenuKey.get());
            if (menu == null) {
                return;
            }

            Optional<RuntimeMenuSection> section = menu.findSection(sectionName);
            if (section.isEmpty()) {
                String available = menu.sections().stream().map(RuntimeMenuSection::name)
                        .collect(Collectors.joining(", "));
                mainThread.execute(() -> player.sendMessage(
                        ChatColor.YELLOW + "No section named '" + sectionName + "' in this menu. Available: " + available));
                return;
            }

            Map<Integer, String> materialKeys = renderer.resolveTemplateMaterialKeys(menu).join();
            mainThread.execute(() -> {
                mutation.apply(menu, section.get(), session);
                renderAndShow(player, menu, session, materialKeys);
            });
        });
    }

    @FunctionalInterface
    private interface SectionPageMutation {
        void apply(RuntimeMenu menu, RuntimeMenuSection section, MenuSession session);
    }

    /**
     * IMPLEMENTATION_PLAN.md Phase 7 / DESIGN_REVIEW.md §2.1 §2.5: prompts via
     * the in-house {@link AnvilCaptureManager}. Opening the anvil closes the
     * menu Inventory, so - like {@link #nextPage} - this re-fetches and reopens
     * the menu once the query is captured.
     */
    public void promptSearch(Player player, String sectionName) {
        anvilCaptureManager.startTextCapture(
                player,
                ChatColor.YELLOW + "Type a search query for '" + sectionName
                        + "', then click the left paper to cancel or the right paper to confirm:",
                query -> search(player, sectionName, query),
                () -> player.sendMessage(ChatColor.YELLOW + "Search cancelled.")
        );
    }

    /** IMPLEMENTATION_PLAN.md Phase 7: same as {@link #promptSearch}, for one FilterBar facet. */
    public void promptFilter(Player player, String sectionName, String facetKey) {
        anvilCaptureManager.startTextCapture(
                player,
                ChatColor.YELLOW + "Type a value for filter '" + facetKey + "' on '" + sectionName
                        + "', then click the left paper to cancel or the right paper to confirm:",
                value -> filter(player, sectionName, facetKey, value),
                () -> player.sendMessage(ChatColor.YELLOW + "Filter cancelled.")
        );
    }

    public void search(Player player, String sectionName, String queryText) {
        applyContentQueryUpdate(player, sectionName, existing -> existing.withSearchText(queryText));
    }

    public void clearSearch(Player player, String sectionName) {
        applyContentQueryUpdate(player, sectionName, existing -> existing.withSearchText(null));
    }

    public void filter(Player player, String sectionName, String facetKey, String value) {
        applyContentQueryUpdate(player, sectionName, existing -> existing.withFilterValue(facetKey, value));
    }

    public void clearFilter(Player player, String sectionName, String facetKey) {
        applyContentQueryUpdate(player, sectionName, existing -> existing.withFilterValue(facetKey, null));
    }

    /**
     * Shared plumbing for every search/filter mutation: re-fetches the current
     * menu, resolves {@code sectionName}, rejects non-searchable sections,
     * updates the session's {@link MenuContentQuery} for that section, resets
     * its page to 0, and re-renders.
     */
    private void applyContentQueryUpdate(Player player, String sectionName, UnaryOperator<MenuContentQuery> update) {
        mutateSectionPage(player, sectionName, (menu, section, session) -> {
            if (!section.searchable()) {
                player.sendMessage(ChatColor.YELLOW + "Section '" + sectionName + "' doesn't support search/filter.");
                return;
            }
            MenuContentQuery updated = update.apply(session.getContentQuery(section.id()));
            session.setContentQuery(section.id(), updated);
            session.setPage(section.id(), 0);
        });
    }

    /** Main thread. Renders and shows (in place when the same menu is already open, else a new Inventory). */
    private void renderAndShow(Player player, RuntimeMenu menu, MenuSession session, Map<Integer, String> materialKeys) {
        safeRender(menu, session, player, materialKeys)
                .whenCompleteAsync((result, error) -> {
                    if (error != null) {
                        failToOpen(player, menu.key(), error.getMessage(), error instanceof Exception e ? e : null);
                        return;
                    }
                    show(player, menu, result);
                }, mainThread);
    }

    /** {@link MenuRenderer#render}, with a synchronous failure (e.g. a throwing content source) turned into a failed future. */
    private java.util.concurrent.CompletableFuture<MenuRenderResult> safeRender(RuntimeMenu menu, MenuSession session,
                                                                               Player player, Map<Integer, String> materialKeys) {
        try {
            return renderer.render(menu, session, player, materialKeys, this);
        } catch (RuntimeException e) {
            return java.util.concurrent.CompletableFuture.failedFuture(e);
        }
    }

    /** Main thread. */
    private void show(Player player, RuntimeMenu menu, MenuRenderResult result) {
        if (!player.isOnline()) {
            return;
        }
        // A render that lands while the player is already sneaking shows
        // control hints immediately (post-Phase-8 QOL follow-up).
        boolean revealControls = player.isSneaking();

        Optional<OpenMenuContext> existing = openMenuContextRegistry.get(player.getUniqueId());
        // Phase 9 fix: refresh in place only when it is the *same* menu. Before,
        // any open KnK Inventory was reused - so menu.open from menu A to menu B
        // rendered B into A's Inventory (A's title and size).
        if (existing.isPresent()
                && existing.get().menuKey().equals(menu.key())
                && existing.get().inventory().getSize() == result.totalSlots()
                && player.getOpenInventory().getTopInventory().equals(existing.get().inventory())) {
            renderer.applyToInventory(existing.get().inventory(), result, revealControls);
            existing.get().update(menu, result);
            refreshSchedule.track(player.getUniqueId(), menu.autoRefreshTicks(), MenuRenderer.currentTick());
            return;
        }

        Inventory inventory = Bukkit.createInventory(null, result.totalSlots(), DisplayTextFormatter.toComponent(menu.title()));
        renderer.applyToInventory(inventory, result, revealControls);
        player.openInventory(inventory);
        openMenuContextRegistry.register(player.getUniqueId(), new OpenMenuContext(player, inventory, menu, result));
        refreshSchedule.track(player.getUniqueId(), menu.autoRefreshTicks(), MenuRenderer.currentTick());
    }

    /** Whether the player is looking at a KnK menu right now (main thread: checks the actual top inventory). */
    private boolean isViewingKnkMenu(Player player) {
        Optional<OpenMenuContext> context = openMenuContextRegistry.get(player.getUniqueId());
        if (context.isEmpty()) {
            return false;
        }
        return !Bukkit.isPrimaryThread()
                || player.getOpenInventory().getTopInventory().equals(context.get().inventory());
    }

    /** Must be called off the main thread. Returns null (having already messaged the player) on failure. */
    private RuntimeMenu loadAndAssemble(Player player, String templateKey) {
        String blockedReason = blockedMenus.get(templateKey);
        if (blockedReason != null) {
            failToOpen(player, templateKey, "blocked at startup validation: " + blockedReason, null);
            return null;
        }

        try {
            KnkMenuTemplate template = menuTemplatesDataAccess.getByKeyAsync(templateKey).join().value()
                    .orElseThrow(() -> new MenuAssemblyException("Menu template '" + templateKey + "' was not found"));
            return MenuTemplateAssembler.assemble(template);
        } catch (MenuAssemblyException e) {
            failToOpen(player, templateKey, e.getMessage(), null);
            return null;
        } catch (Exception e) {
            failToOpen(player, templateKey, e.getMessage(), e);
            return null;
        }
    }

    private void failToOpen(Player player, String templateKey, String reason, Throwable cause) {
        if (cause != null) {
            logger.log(Level.WARNING, "Unexpected error loading menu '" + templateKey + "' for " + player.getName(), cause);
        } else {
            logger.warning("Refusing to open menu '" + templateKey + "' for " + player.getName() + ": " + reason);
        }
        mainThread.execute(() -> player.sendMessage(ChatColor.RED + "That menu is unavailable right now."));
    }

    /** Called from {@code PlayerQuitEvent} - closes gap #4 (v1's static per-player maps never cleared). */
    public void closeSession(UUID playerId) {
        openMenuContextRegistry.close(playerId);
        sessionRegistry.close(playerId);
        refreshSchedule.untrack(playerId);
    }

    /** Called when a KnK menu Inventory is closed: stop auto-refreshing it (the session itself survives). */
    public void onMenuInventoryClosed(UUID playerId) {
        refreshSchedule.untrack(playerId);
    }

    /**
     * Marks a menu key as broken (IMPLEMENTATION_PLAN.md Phase 3,
     * {@link MenuDefinitionValidationRunner}, run once at plugin enable) so
     * every subsequent open attempt refuses immediately with a clear reason.
     */
    public void blockMenu(String templateKey, String reason) {
        blockedMenus.put(templateKey, reason != null ? reason : "failed startup validation");
    }

    /**
     * Content port CP1: records that {@code templateKey} was fetched, assembled and passed every
     * startup validation step. Only {@link MenuDefinitionValidationRunner} calls this.
     */
    public void markValidated(String templateKey) {
        validatedMenus.add(templateKey);
    }

    /**
     * Content port CP1 ({@code menu-available} condition): true when a template with this key
     * was registered at startup and passed validation (and has not been blocked since). A
     * template created through the CRUD API after startup is not available until the next
     * restart validates it - the same rule every other menu follows.
     */
    public boolean isMenuAvailable(String templateKey) {
        return templateKey != null && validatedMenus.contains(templateKey) && !blockedMenus.containsKey(templateKey);
    }

    /** Currently-blocked menu keys and why, for admin visibility (e.g. {@code /knk menu broken}). */
    public Map<String, String> blockedMenus() {
        return Map.copyOf(blockedMenus);
    }
}
