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
import net.knightsandkings.knk.core.menu.MenuTransitionTracker;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
import net.knightsandkings.knk.paper.utils.DisplayTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /** Menus a late validation is running for right now (see {@link #requestValidation}). */
    private final Set<String> validatingMenus = ConcurrentHashMap.newKeySet();
    /** Set by {@code MenuDefinitionValidationRunner}: validates one menu key the way the startup pass does (blocking). */
    private volatile java.util.function.Consumer<String> lateValidator;
    /** Diagnostic telemetry / funnel hooks (KNG-34 link 6); empty unless a feature registers one. */
    private final MenuObservers observers = new MenuObservers();
    /** KNG-125: toggles BLINK items each tick ({@link #processBlinks}). */
    private final MenuItemBlinker blinker = new MenuItemBlinker();
    /** KNG-231: page loads in flight, one per player. */
    private final MenuPageLoads pageLoads = new MenuPageLoads();
    /** KNG-231: a page load still running after this many ticks also says so in the action bar. */
    static final long LOADING_ACTION_BAR_DELAY_TICKS = 10L;
    /**
     * Alpha cross-play finding 2026-10-24: Bedrock (Floodgate) players switch menus with a delay -
     * see {@link #configureBedrockTransitions}. Off (no player is Bedrock, 0 ticks) until configured.
     */
    private final MenuTransitionTracker transitions = new MenuTransitionTracker();
    private volatile Predicate<UUID> bedrockPlayers = uuid -> false;
    private volatile int bedrockTransitionDelayTicks;

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
     * Alpha cross-play finding 2026-10-24 ({@code menus.bedrock-transition-delay-ticks}): when a
     * player {@code isBedrock} says is a Bedrock client moves from one inventory to a KnK menu,
     * the current inventory is closed first and the menu opens {@code delayTicks} later, so
     * Geyser's close of the previous container form cannot close the new menu. A client close
     * in that window, or up to {@code delayTicks} after the open, is the stale close of the
     * previous form ({@link MenuTransitionTracker}). {@code delayTicks <= 0} turns it off; Java
     * players are never delayed.
     */
    public void configureBedrockTransitions(Predicate<UUID> isBedrock, int delayTicks) {
        this.bedrockPlayers = isBedrock != null ? isBedrock : uuid -> false;
        this.bedrockTransitionDelayTicks = Math.max(0, delayTicks);
    }

    /** Registers a {@link MenuObserver} (main thread, at enable). */
    public void addObserver(MenuObserver observer) {
        observers.add(observer);
    }

    /** The registered observers, for {@link MenuClickListener}. */
    public MenuObservers observers() {
        return observers;
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
                String parentKey = fromOpenMenu ? session.currentMenuKey().orElse(null) : null;
                if (fromOpenMenu) {
                    session.navigateTo(menu.key(), ctx, menu.title());
                } else {
                    session.openAsRoot(menu.key(), ctx, menu.title());
                }
                renderAndShow(player, menu, session, materialKeys);
                observers.opened(player, menu.key(), parentKey);
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
        String fromKey = sessionOpt.flatMap(MenuSession::currentMenuKey).orElse(null);
        Optional<MenuSession.NavigationEntry> previous = sessionOpt.flatMap(MenuSession::goBackEntry);
        if (previous.isEmpty()) {
            sessionOpt.ifPresent(MenuSession::resetNavigation);
            player.closeInventory();
            return;
        }

        MenuSession session = sessionOpt.get();
        String key = previous.get().key();
        observers.back(player, fromKey, key);
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

    /**
     * KNG-125: one tick of the item blinker - swaps every open menu's {@code BLINK} slots between
     * their item and the yellow pane when their interval says so. Called by
     * {@link MenuAutoRefreshTask} on the main thread, after {@link #processDueRefreshes}. Menus with
     * no blinking slot cost one map lookup; a closed menu has no context and is not visited.
     */
    public void processBlinks() {
        long now = MenuRenderer.currentTick();
        for (OpenMenuContext context : openMenuContextRegistry.all()) {
            if (context.blinkTicksBySlot().isEmpty()) {
                continue;
            }
            Player player = context.player();
            if (!player.isOnline() || !player.getOpenInventory().getTopInventory().equals(context.inventory())) {
                continue;
            }
            blinker.tick(context, now, player.isSneaking());
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
                        // KNG-125: a slot in its pane phase gets the pane back in this same tick.
                        blinker.tick(context, MenuRenderer.currentTick(), player.isSneaking());
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
     * {@code mutation} on the main thread, then re-renders. When that menu is
     * the one on screen, {@link #changePageInPlace} does it without the
     * re-fetch (KNG-231).
     */
    private void mutateSectionPage(Player player, String sectionName, SectionPageMutation mutation) {
        Optional<MenuSession> sessionOpt = sessionRegistry.get(player.getUniqueId());
        Optional<String> currentMenuKey = sessionOpt.flatMap(MenuSession::currentMenuKey);
        if (sessionOpt.isEmpty() || currentMenuKey.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "You don't have a menu open.");
            return;
        }
        MenuSession session = sessionOpt.get();

        Optional<OpenMenuContext> open = openMenuShowing(player, currentMenuKey.get());
        if (open.isPresent()) {
            changePageInPlace(player, open.get(), session, sectionName, mutation);
            return;
        }

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

    /** Main thread: the player's open KnK menu when it is {@code menuKey} and still on screen. */
    private Optional<OpenMenuContext> openMenuShowing(Player player, String menuKey) {
        if (!Bukkit.isPrimaryThread()) {
            return Optional.empty();
        }
        return openMenuContextRegistry.get(player.getUniqueId())
                .filter(context -> context.menuKey().equals(menuKey))
                .filter(context -> player.getOpenInventory().getTopInventory().equals(context.inventory()));
    }

    /**
     * KNG-231: a page change (or search/filter change) in the menu the player is looking at, on the
     * main thread. Re-renders from the open menu's assembled template and material keys, so only the
     * content is fetched (the template used to be re-fetched and re-assembled on every click). While
     * the content loads, the section's pager buttons show a clock "Loading page N…", and after
     * {@link #LOADING_ACTION_BAR_DELAY_TICKS} the action bar says the same; further page clicks are
     * ignored until it is done. A failed fetch puts the previous page back and tells the player.
     * In-memory content renders within the click, with no loading item at all.
     */
    private void changePageInPlace(Player player, OpenMenuContext context, MenuSession session,
                                   String sectionName, SectionPageMutation mutation) {
        RuntimeMenu menu = context.menu();
        Optional<RuntimeMenuSection> found = menu.findSection(sectionName);
        if (found.isEmpty()) {
            String available = menu.sections().stream().map(RuntimeMenuSection::name)
                    .collect(Collectors.joining(", "));
            player.sendMessage(ChatColor.YELLOW + "No section named '" + sectionName + "' in this menu. Available: " + available);
            return;
        }
        RuntimeMenuSection section = found.get();
        UUID playerId = player.getUniqueId();
        OptionalLong started = pageLoads.tryBegin(playerId, System.currentTimeMillis());
        if (started.isEmpty()) {
            return;
        }
        long load = started.getAsLong();
        int pageBefore = session.getPage(section.id());
        MenuContentQuery queryBefore = session.getContentQuery(section.id());
        long startedNanos = System.nanoTime();

        CompletableFuture<MenuRenderResult> render;
        try {
            mutation.apply(menu, section, session);
            render = renderer.render(menu, session, player, context.materialNamespaceKeys(), this, true);
        } catch (RuntimeException e) {
            render = CompletableFuture.failedFuture(e);
        }

        AtomicBoolean actionBarShown = new AtomicBoolean();
        if (!render.isDone()) {
            int page = MenuPageLoads.displayPage(session.getPage(section.id()), context.sectionView(section.id()));
            showLoading(context, section, page);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (pageLoads.isCurrent(playerId, load) && player.isOnline()) {
                    player.sendActionBar(DisplayTextFormatter.toComponent(MenuPageLoads.loadingText(page)));
                    actionBarShown.set(true);
                }
            }, LOADING_ACTION_BAR_DELAY_TICKS);
        }

        render.whenCompleteAsync((result, error) -> {
            boolean wasCurrent = pageLoads.isCurrent(playerId, load);
            pageLoads.finish(playerId, load);
            if (actionBarShown.get() && player.isOnline()) {
                player.sendActionBar(net.kyori.adventure.text.Component.empty());
            }
            Optional<OpenMenuContext> current = openMenuContextRegistry.get(playerId);
            boolean stillShowing = wasCurrent && player.isOnline() && current.isPresent() && current.get() == context
                    && player.getOpenInventory().getTopInventory().equals(context.inventory());
            if (error != null) {
                session.setPage(section.id(), pageBefore);
                session.setContentQuery(section.id(), queryBefore);
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                if (!(cause instanceof MenuRenderer.ContentFetchFailedException)) {
                    logger.log(Level.WARNING, "Changing page of menu '" + menu.key() + "' for " + player.getName() + " failed", cause);
                }
                if (stillShowing) {
                    MenuRenderResult previous = context.lastRender();
                    renderer.applyToInventory(context.inventory(), previous, player.isSneaking());
                    context.update(context.menu(), previous);
                    player.sendMessage(ChatColor.RED + "Couldn't load that page right now. Try again in a moment.");
                }
                return;
            }
            logger.fine(() -> "Menu '" + menu.key() + "' page change for " + player.getName() + " took "
                    + (System.nanoTime() - startedNanos) / 1_000_000L + " ms");
            if (stillShowing) {
                show(player, menu, result);
            }
        }, mainThread);
    }

    /** KNG-231: puts the "Loading page N…" clock on {@code section}'s pager buttons in the open Inventory. */
    private static void showLoading(OpenMenuContext context, RuntimeMenuSection section, int page) {
        Set<Integer> slots = MenuPageLoads.pagerSlots(context.itemsBySlot(), context.sectionsBySlot(), section);
        if (slots.isEmpty()) {
            return;
        }
        ItemStack clock = new ItemStack(Material.CLOCK);
        ItemMeta meta = clock.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(DisplayTextFormatter.translateToLegacy(MenuPageLoads.loadingText(page)));
            clock.setItemMeta(meta);
        }
        for (int slot : slots) {
            context.inventory().setItem(slot, clock);
        }
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
            blinker.tick(existing.get(), MenuRenderer.currentTick(), revealControls);
            refreshSchedule.track(player.getUniqueId(), menu.autoRefreshTicks(), MenuRenderer.currentTick());
            return;
        }

        int delayTicks = bedrockTransitionDelayTicks;
        if (delayTicks > 0 && hasContainerOpen(player) && bedrockPlayers.test(player.getUniqueId())) {
            // Alpha cross-play finding 2026-10-24: close now (the close handler treats it like any
            // other close of the previous menu), open the next menu a few ticks later.
            UUID playerId = player.getUniqueId();
            player.closeInventory();
            long token = transitions.begin(playerId);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && transitions.opened(playerId, token, MenuRenderer.currentTick(), delayTicks)) {
                    openNewInventory(player, menu, result);
                }
            }, delayTicks);
            return;
        }
        openNewInventory(player, menu, result);
    }

    /** Main thread. Opens {@code result} in a fresh Inventory and makes it the player's open menu. */
    private void openNewInventory(Player player, RuntimeMenu menu, MenuRenderResult result) {
        Inventory inventory = Bukkit.createInventory(null, result.totalSlots(), DisplayTextFormatter.toComponent(menu.title()));
        renderer.applyToInventory(inventory, result, player.isSneaking());
        player.openInventory(inventory);
        openMenuContextRegistry.register(player.getUniqueId(), new OpenMenuContext(player, inventory, menu, result));
        refreshSchedule.track(player.getUniqueId(), menu.autoRefreshTicks(), MenuRenderer.currentTick());
    }

    /** Whether the player has a container screen open (a menu, an anvil prompt...), not just their own inventory. */
    private static boolean hasContainerOpen(Player player) {
        InventoryType type = player.getOpenInventory().getType();
        return type != InventoryType.CRAFTING && type != InventoryType.CREATIVE;
    }

    /**
     * Alpha cross-play finding 2026-10-24: a close the client sent ({@code Reason.PLAYER}) for
     * {@code playerId}; {@link MenuLifecycleListener} ignores it or reopens the menu when it is
     * the stale close of a Bedrock menu switch. Always {@code NORMAL} for Java players.
     */
    public MenuTransitionTracker.CloseVerdict onClientClose(UUID playerId) {
        return transitions.onClientClose(playerId, MenuRenderer.currentTick());
    }

    /**
     * Alpha cross-play finding 2026-10-24: the stale close of the previous Bedrock form closed
     * {@code context}'s menu right after it opened - show the same Inventory again next tick,
     * unless the player has moved on to something else by then.
     */
    public void reopenAfterStaleClose(Player player, OpenMenuContext context) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Optional<OpenMenuContext> current = openMenuContextRegistry.get(playerId);
            if (!player.isOnline() || current.isEmpty() || current.get() != context || hasContainerOpen(player)) {
                return;
            }
            player.openInventory(context.inventory());
            refreshSchedule.track(playerId, context.menu().autoRefreshTicks(), MenuRenderer.currentTick());
        });
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
        pageLoads.cancel(playerId);
        transitions.cancel(playerId);
        openMenuContextRegistry.close(playerId);
        sessionRegistry.close(playerId);
        refreshSchedule.untrack(playerId);
    }

    /**
     * Called when a KnK menu Inventory is closed: stop auto-refreshing it (the session itself survives).
     * The caller then drops the {@link OpenMenuContext}, which also stops its blinking items (KNG-125).
     */
    public void onMenuInventoryClosed(UUID playerId) {
        if (!observers.isEmpty()) {
            observers.closed(playerId, openMenuContextRegistry.get(playerId).map(c -> c.menu().key()).orElse(null));
        }
        refreshSchedule.untrack(playerId);
        pageLoads.cancel(playerId);
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

    /**
     * Why {@link #isMenuAvailable} says no, for a caller's log line: the startup validation's reason when the
     * menu is blocked, else that the menu was never validated (the API was unreachable when the plugin
     * started, that template was created since, or its fetch failed at startup). Empty when it is available.
     */
    public Optional<String> unavailableReason(String templateKey) {
        if (isMenuAvailable(templateKey)) return Optional.empty();
        String blocked = templateKey == null ? null : blockedMenus.get(templateKey);
        if (blocked != null) return Optional.of("blocked at startup validation: " + blocked);
        return Optional.of("not validated at startup (the API was unreachable then, or the template didn't exist yet)");
    }

    /** True while a validation pass runs (or has a retry pending), so recovery and timer never run two at once. */
    private final AtomicBoolean validationPassActive = new AtomicBoolean(false);
    /** Set by {@code MenuDefinitionValidationRunner}: the whole validation pass, for {@link #revalidateAfterRecovery}. */
    private volatile Runnable revalidation;

    /** The runner hands over its full pass so the API-recovery event can start it. */
    public void setRevalidation(Runnable revalidation) {
        this.revalidation = revalidation;
    }

    /** Claims the single validation slot; false when a pass is running or a retry of one is pending. */
    public boolean beginValidationPass() {
        return validationPassActive.compareAndSet(false, true);
    }

    /** @param retryPending the pass scheduled a retry, which keeps the slot until it runs */
    public void endValidationPass(boolean retryPending) {
        if (!retryPending) {
            validationPassActive.set(false);
        }
    }

    /** A scheduled retry is about to run: it takes the slot over from the pass that scheduled it. */
    public void retryDue() {
        validationPassActive.set(false);
    }

    /**
     * Playtest 2026-10-24: the API came back ({@code ApiConnectivityChangedEvent} recovery). Runs the validation pass
     * off the main thread for every menu still unvalidated, unless a pass or its retry is already pending (that one
     * will see the API up).
     */
    public void revalidateAfterRecovery() {
        Runnable pass = revalidation;
        if (pass == null || validationPassActive.get()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, pass);
    }

    /** Runs {@code task} off the main thread after {@code delayTicks} (the startup validation's retries while the API is down). */
    public void runLaterAsync(long delayTicks, Runnable task) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, Math.max(1L, delayTicks));
    }

    /** {@code MenuDefinitionValidationRunner} hands over its per-key validation so menus it never saw can be validated later. */
    public void setLateValidator(java.util.function.Consumer<String> lateValidator) {
        this.lateValidator = lateValidator;
    }

    /**
     * Siege playtest 2026-10-24: a menu the startup validation never saw stays unavailable for the whole
     * uptime, and the only trace is a chat fallback. This runs the same validation for that one key off the
     * main thread; the menu becomes available (or blocked, with its reason) once it finishes, so the next
     * open works without a restart. Blocked menus are not retried.
     *
     * @return true when a validation was started by this call
     */
    public boolean requestValidation(String templateKey) {
        java.util.function.Consumer<String> validator = lateValidator;
        if (templateKey == null || validator == null || isMenuAvailable(templateKey) || blockedMenus.containsKey(templateKey)) {
            return false;
        }
        if (!validatingMenus.add(templateKey)) {
            return false;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                validator.accept(templateKey);
                if (isMenuAvailable(templateKey)) {
                    logger.info("Menu '" + templateKey + "' validated late and is available now.");
                }
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Late validation of menu '" + templateKey + "' failed", e);
            } finally {
                validatingMenus.remove(templateKey);
            }
        });
        return true;
    }

    /** Currently-blocked menu keys and why, for admin visibility (e.g. {@code /knk menu broken}). */
    public Map<String, String> blockedMenus() {
        return Map.copyOf(blockedMenus);
    }
}
