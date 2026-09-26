package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.material.KnkMinecraftMaterialRef;
import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;
import net.knightsandkings.knk.core.menu.ConditionRegistry;
import net.knightsandkings.knk.core.menu.MenuContentQuery;
import net.knightsandkings.knk.core.menu.MenuContentSourceRegistry;
import net.knightsandkings.knk.core.menu.MenuContentSourceRegistry.MenuContentPage;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.MenuParams;
import net.knightsandkings.knk.core.menu.MenuRenderPriority;
import net.knightsandkings.knk.core.menu.MenuSectionRenderer;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.menu.MenuSlotCalculator;
import net.knightsandkings.knk.core.menu.MenuVariableProviderRegistry;
import net.knightsandkings.knk.core.menu.MenuVariableScope;
import net.knightsandkings.knk.core.menu.MenuRowCompactor;
import net.knightsandkings.knk.core.menu.MenuStateView;
import net.knightsandkings.knk.core.menu.MenuView;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
import net.knightsandkings.knk.core.menu.SectionSlotAssignment;
import net.knightsandkings.knk.core.menu.SectionView;
import net.knightsandkings.knk.core.menu.VariableResolver;
import net.knightsandkings.knk.paper.mapper.MaterialNamespaceResolver;
import net.knightsandkings.knk.paper.utils.DisplayTextFormatter;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Computes and applies a {@link RuntimeMenu}'s Inventory contents for one
 * player's current {@link MenuSession} state (current page per section).
 * <p>
 * <b>Threading (InventoryMenu Phase 9 §9.0, replacing Phase 2's
 * "compute everything off-thread").</b> A render is a future chain:
 * <ol>
 *   <li>{@link #resolveTemplateMaterialKeys} - material-ref lookups for the
 *       template's own items (data-access futures; the caller may skip this
 *       and pass the keys of a previous render, which is what auto-refresh
 *       does);</li>
 *   <li>{@link #render} - <b>must be called on the main thread</b>: builds the
 *       variable scope (feature providers run here), interpolates content-source
 *       params and <em>calls</em> every content source;</li>
 *   <li>waits for any content fetch that is still in flight (plus material
 *       refs of Java-mapped fetched items) off the main thread;</li>
 *   <li>composes the result back on the main thread: bindings, Render
 *       conditions, {@code ItemStack}s.</li>
 * </ol>
 * The returned future completes on the main thread, so the caller can apply
 * it directly ({@link #applyToInventory}). When every content source answers
 * with an already-completed future (in-memory sources such as Siege's), steps
 * 2-4 run synchronously inside the calling tick.
 */
public final class MenuRenderer {

    private static final Logger LOGGER = Logger.getLogger(MenuRenderer.class.getName());
    private static final Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final MinecraftMaterialRefsDataAccess materialRefsDataAccess;
    private final MenuContentSourceRegistry<MenuContentSourceContext> contentSourceRegistry;
    private final ConditionRegistry<MenuActionContext> conditionRegistry;
    private final MenuVariableProviderRegistry<Player> variableRegistry;
    private final Executor mainThread;

    /**
     * @param mainThread runs a task on the server main thread - inline when
     *                   already on it (see {@code MenuService#mainThreadExecutor})
     */
    public MenuRenderer(
            MinecraftMaterialRefsDataAccess materialRefsDataAccess,
            MenuContentSourceRegistry<MenuContentSourceContext> contentSourceRegistry,
            ConditionRegistry<MenuActionContext> conditionRegistry,
            MenuVariableProviderRegistry<Player> variableRegistry,
            Executor mainThread
    ) {
        this.materialRefsDataAccess = materialRefsDataAccess;
        this.contentSourceRegistry = contentSourceRegistry;
        this.conditionRegistry = conditionRegistry;
        this.variableRegistry = variableRegistry;
        this.mainThread = mainThread;
    }

    /**
     * Step 1: namespace keys for every material ref the template itself
     * references (item {@code materialRefId}s + background). Safe on any
     * thread; the future completes on a data-access thread.
     */
    public CompletableFuture<Map<Integer, String>> resolveTemplateMaterialKeys(RuntimeMenu menu) {
        Set<Integer> materialRefIds = new HashSet<>();
        if (menu.backgroundMaterialRefId() != null) {
            materialRefIds.add(menu.backgroundMaterialRefId());
        }
        for (RuntimeMenuSection section : menu.sections()) {
            for (RuntimeMenuItem item : section.items()) {
                if (item.materialRefId() != null) {
                    materialRefIds.add(item.materialRefId());
                }
            }
        }
        return resolveMaterialKeys(materialRefIds, new java.util.concurrent.ConcurrentHashMap<>());
    }

    /**
     * Steps 2-4. <b>Call on the main thread.</b> {@code materialKeys} is the
     * result of {@link #resolveTemplateMaterialKeys} (or a previous render's
     * keys); it is copied, never mutated. The returned future completes on the
     * main thread.
     */
    public CompletableFuture<MenuRenderResult> render(RuntimeMenu menu, MenuSession session, Player player,
                                                      Map<Integer, String> materialKeys, MenuService menuService) {
        Map<Integer, String> keys = new java.util.concurrent.ConcurrentHashMap<>(materialKeys);
        MenuContextParams menuContext = session.currentContext();
        long currentTick = currentTick();
        MenuVariableScope rootScope = variableRegistry.scope(player, menuContext,
                Map.of(MenuVariableProviderRegistry.ROOT_MENU, MenuView.of(menu.key(), menu.title(), session),
                        MenuVariableProviderRegistry.ROOT_STATE, MenuStateView.of(session)));
        Predicate<String> permissionChecker = player::hasPermission;

        // Step 2 (main thread): start every content-source fetch.
        Map<Integer, CompletableFuture<ContentFetch>> fetches = new LinkedHashMap<>();
        for (RuntimeMenuSection section : menu.sections()) {
            if (section.hasContentSource() && section.isVisibleTo(permissionChecker)) {
                fetches.put(section.id(), startContentFetch(section, menu, session, player, menuContext, rootScope, keys));
            }
        }

        // Step 3: wait (off-thread only if something is actually in flight).
        CompletableFuture<Void> allFetched = CompletableFuture.allOf(fetches.values().toArray(new CompletableFuture[0]));

        // Step 4 (main thread): compose.
        return allFetched.thenApplyAsync(ignored -> {
            Map<Integer, ContentFetch> fetched = new HashMap<>();
            fetches.forEach((sectionId, future) -> fetched.put(sectionId, future.join()));
            return compose(menu, session, player, menuContext, rootScope, keys, fetched, currentTick, menuService);
        }, mainThread);
    }

    /** One content-source section's fetched page (already wrapped to a valid page) plus its slot pool. */
    private record ContentFetch(RuntimeMenuSection.SlotPool pool, MenuContentPage content, int page, int totalPages) {
    }

    /**
     * IMPLEMENTATION_PLAN.md Phase 8 content-source path, now split so the
     * source is <em>called</em> on the main thread (Phase 9 §9.0). The session's
     * active {@link MenuContentQuery} is threaded into the {@link PagedQuery}
     * ({@code searchTerm}/{@code filters}); the section's
     * {@code ContentSourceParamsJson} values are interpolated against the render
     * scope (E3) and handed to row sources. {@code MenuSession} pages are
     * 0-based, {@link PagedQuery#pageNumber()} is 1-based (web-api/EF
     * convention) - the {@code +1} below is that boundary. A page request past
     * either end (from {@code MenuService.changePage}'s unclamped stepping) is
     * wrapped once the first fetch reveals the real total, then re-fetched -
     * the re-fetch is issued back on the main thread too.
     */
    private CompletableFuture<ContentFetch> startContentFetch(RuntimeMenuSection section, RuntimeMenu menu,
                                                             MenuSession session, Player player,
                                                             MenuContextParams menuContext, MenuVariableScope rootScope,
                                                             Map<Integer, String> keys) {
        RuntimeMenuSection.SlotPool pool = section.computeSlotPool(menu.totalSlots());
        int pageSize = pool.availablePool().size();
        if (pageSize == 0) {
            return CompletableFuture.completedFuture(new ContentFetch(pool, null, 0, 0));
        }

        MenuContentQuery contentQuery = section.searchable() ? session.getContentQuery(section.id()) : MenuContentQuery.EMPTY;
        MenuContentSourceContext context = new MenuContentSourceContext(player, session, menuContext);
        int requestedPage = session.getPage(section.id());
        MenuVariableScope paramsScope = rootScope.with(MenuVariableProviderRegistry.ROOT_SECTION,
                new SectionView(section.name(), Math.max(requestedPage, 0), 0));
        Map<String, String> params = MenuParams.interpolate(section.contentSourceParams(), paramsScope);

        int firstFetchPage = Math.max(requestedPage, 0);
        return fetchContentPage(section, context, params, firstFetchPage, pageSize, contentQuery)
                .thenComposeAsync(first -> {
                    int totalPages = (int) Math.ceil(first.page().totalCount() / (double) pageSize);
                    int wrapped = totalPages > 0 ? Math.floorMod(requestedPage, totalPages) : 0;
                    if (wrapped == firstFetchPage) {
                        return CompletableFuture.completedFuture(new ContentFetch(pool, first, wrapped, totalPages));
                    }
                    session.setPage(section.id(), wrapped);
                    return fetchContentPage(section, context, params, wrapped, pageSize, contentQuery)
                            .thenApply(second -> new ContentFetch(pool, second, wrapped, totalPages));
                }, mainThread)
                .thenCompose(fetch -> {
                    if (fetch.content() == null || fetch.content().rows()) {
                        return CompletableFuture.completedFuture(fetch);
                    }
                    // Java-mapped items (e.g. catalog.itemblueprints) carry
                    // materialRefIds the template didn't know about.
                    Set<Integer> missing = new HashSet<>();
                    for (Object element : fetch.content().page().items()) {
                        if (element instanceof RuntimeMenuItem item && item.materialRefId() != null
                                && !keys.containsKey(item.materialRefId())) {
                            missing.add(item.materialRefId());
                        }
                    }
                    return resolveMaterialKeys(missing, keys).thenApply(ignored -> fetch);
                });
    }

    /** @param page0 0-based (MenuSession convention) - converted to the 1-based PagedQuery/web-api convention here. */
    private CompletableFuture<MenuContentPage> fetchContentPage(RuntimeMenuSection section, MenuContentSourceContext context,
                                                                Map<String, String> params, int page0, int pageSize,
                                                                MenuContentQuery contentQuery) {
        PagedQuery query = new PagedQuery(page0 + 1, pageSize, contentQuery.searchText(), null, false, contentQuery.filterValues());
        CompletableFuture<MenuContentPage> future;
        try {
            future = contentSourceRegistry.fetch(section.contentSourceId(), context, params, query);
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        return future.exceptionally(e -> {
            LOGGER.log(Level.WARNING, "MenuContentSource '" + section.contentSourceId() + "' for section '"
                    + section.name() + "' failed to fetch page " + page0 + " - rendering it empty this pass", e);
            return new MenuContentPage(new Page<>(List.of(), 0, page0 + 1, pageSize), false);
        });
    }

    /** Step 4 - main thread. */
    private MenuRenderResult compose(RuntimeMenu menu, MenuSession session, Player player, MenuContextParams menuContext,
                                     MenuVariableScope rootScope, Map<Integer, String> keys,
                                     Map<Integer, ContentFetch> fetched, long currentTick, MenuService menuService) {
        Predicate<String> permissionChecker = player::hasPermission;

        Map<Integer, ItemStack> itemStacksBySlot = new HashMap<>();
        Map<Integer, RuntimeMenuItem> itemsBySlot = new HashMap<>();
        Map<Integer, RuntimeMenuSection> sectionsBySlot = new HashMap<>();
        Map<Integer, List<String>> controlHintLoreBySlot = new HashMap<>();
        Map<Integer, Object> rowsBySlot = new HashMap<>();
        Map<Integer, SectionView> sectionViews = new HashMap<>();

        List<RuntimeMenuSection> sectionsByRenderOrder = menu.sections().stream()
                .sorted(Comparator.comparingInt(section -> priorityRank(section.priority())))
                .toList();

        for (RuntimeMenuSection section : sectionsByRenderOrder) {
            // IMPLEMENTATION_PLAN.md Phase 4 - a section the player lacks
            // visibilityPermission for is skipped entirely (DESIGN_REVIEW.md §2.4).
            if (!section.isVisibleTo(permissionChecker)) {
                continue;
            }

            boolean queryActive = section.searchable() && !session.getContentQuery(section.id()).isEmpty();

            SectionSlotAssignment assignment;
            List<?> rows = List.of();
            List<Integer> rowSlots = List.of();
            if (section.hasContentSource()) {
                ContentFetch fetch = fetched.get(section.id());
                assignment = toAssignment(fetch);
                if (fetch != null && fetch.content() != null && fetch.content().rows()) {
                    rows = fetch.content().page().items();
                    rowSlots = fetch.pool().availablePool();
                }
            } else {
                // IMPLEMENTATION_PLAN.md Phase 5: a searchable section's active
                // query narrows its auto content BEFORE resolveSlots paginates it.
                Predicate<RuntimeMenuItem> contentFilter = queryActive
                        ? buildContentFilter(session.getContentQuery(section.id()), session, rootScope, currentTick)
                        : item -> true;
                assignment = section.resolveSlots(menu.totalSlots(), session.getPage(section.id()), contentFilter);
            }

            SectionView sectionView = SectionView.of(section, assignment);
            if (section.id() != null) {
                sectionViews.put(section.id(), sectionView);
            }
            MenuVariableScope sectionScope = rootScope.with(MenuVariableProviderRegistry.ROOT_SECTION, sectionView);

            MenuSectionRenderer.Pass<MenuActionContext> pass = new MenuSectionRenderer.Pass<>(
                    session, currentTick, permissionChecker, conditionRegistry,
                    (item, scope) -> new MenuActionContext(player, session, scope, menuService, menu, section, item,
                            scope.get(MenuVariableProviderRegistry.ROOT_ROW)));

            // Menu follow-up 2026-09-26: pager arrows only show when there is another page.
            List<MenuSectionRenderer.RenderedSlot> rendered = new ArrayList<>(
                    MenuSectionRenderer.renderItems(withoutIdlePagers(assignment), sectionScope, pass));
            if (!rows.isEmpty()) {
                RuntimeMenuItem rowTemplate = section.rowTemplate().orElse(null);
                if (rowTemplate != null) {
                    rendered.addAll(MenuSectionRenderer.renderRows(section, rowTemplate, rowSlots, rows, sectionScope, pass));
                } else {
                    warnOnce("no-row-template|" + section.id(), "Section '" + section.name() + "' in menu '" + menu.key()
                            + "' got rows from content source '" + section.contentSourceId()
                            + "' but has no row template - rows not rendered (startup validation should have blocked this menu)");
                }
            }

            for (MenuSectionRenderer.RenderedSlot slot : rendered) {
                RuntimeMenuItem item = slot.item();
                String refKey = item.materialRefId() != null ? keys.get(item.materialRefId()) : null;
                ItemStack itemStack = MenuItemBukkitMapper.toItemStack(item, slot.presentation(), refKey, permissionChecker);
                if (slot.row() instanceof MenuItemStackRow provided) {
                    itemStack = withProvidedStack(provided, itemStack);
                }
                itemStacksBySlot.put(slot.slot(), itemStack);
                itemsBySlot.put(slot.slot(), item);
                sectionsBySlot.put(slot.slot(), section);
                if (slot.row() != null) {
                    rowsBySlot.put(slot.slot(), slot.row());
                }
                List<String> hints = resolveControlHints(item);
                if (!hints.isEmpty()) {
                    controlHintLoreBySlot.put(slot.slot(), hints);
                }
                appendPresetStateLore(item, itemStack, assignment, section, session, currentTick);
            }

            boolean noAutoContent = rows.isEmpty() && matchedNoAutoContent(assignment);
            if (queryActive && noAutoContent) {
                placeEmptyResultsMarker(section, menu, itemStacksBySlot);
            }
        }

        // Menu follow-up 2026-09-26: a DYNAMIC menu drops rows left empty (down to MinHeight).
        List<RuntimeMenuSection> visibleSections = menu.sections().stream()
                .filter(section -> section.isVisibleTo(permissionChecker))
                .toList();
        MenuRowCompactor.Layout layout = MenuRowCompactor.compact(menu, itemStacksBySlot.keySet(), visibleSections);
        if (layout.changed()) {
            itemStacksBySlot = remapped(layout, itemStacksBySlot);
            itemsBySlot = remapped(layout, itemsBySlot);
            sectionsBySlot = remapped(layout, sectionsBySlot);
            controlHintLoreBySlot = remapped(layout, controlHintLoreBySlot);
            rowsBySlot = remapped(layout, rowsBySlot);
        }

        fillBackground(menu, keys, itemStacksBySlot, layout.totalSlots());
        session.clearDirty();

        return new MenuRenderResult(Map.copyOf(itemStacksBySlot), Map.copyOf(itemsBySlot), Map.copyOf(sectionsBySlot),
                Map.copyOf(controlHintLoreBySlot), Map.copyOf(rowsBySlot), Map.copyOf(sectionViews), Map.copyOf(keys),
                menuContext, layout.totalSlots());
    }

    /** Menu follow-up 2026-09-26: see {@link MenuItemStackRow}. */
    static ItemStack withProvidedStack(MenuItemStackRow row, ItemStack fromTemplate) {
        ItemStack provided;
        try {
            provided = row.menuItemStack();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Row " + row + " failed to build its ItemStack - using the row template", e);
            return fromTemplate;
        }
        if (provided == null || provided.getType().isAir()) {
            return fromTemplate;
        }
        ItemStack result = provided.clone();
        ItemMeta templateMeta = fromTemplate.getItemMeta();
        List<String> extra = templateMeta != null && templateMeta.hasLore() ? templateMeta.getLore() : null;
        if (extra != null && !extra.isEmpty()) {
            appendLoreLines(result, extra);
        }
        return result;
    }

    private static <V> Map<Integer, V> remapped(MenuRowCompactor.Layout layout, Map<Integer, V> source) {
        Map<Integer, V> target = new HashMap<>();
        layout.remap(source, target::put);
        return target;
    }

    /**
     * Menu follow-up 2026-09-26: a pinned item whose every action is {@code menu.page.next}/
     * {@code menu.page.prev} is left out while its section has at most one page - the whole list
     * fits, so there is nothing to page through.
     */
    static Map<Integer, RuntimeMenuItem> withoutIdlePagers(SectionSlotAssignment assignment) {
        if (assignment.totalPages() > 1) {
            return assignment.itemsBySlot();
        }
        Map<Integer, RuntimeMenuItem> kept = new HashMap<>();
        assignment.itemsBySlot().forEach((slot, item) -> {
            if (!isPagerOnly(item)) {
                kept.put(slot, item);
            }
        });
        return kept;
    }

    private static boolean isPagerOnly(RuntimeMenuItem item) {
        if (item.slotOverride() == null || item.actions().isEmpty()) {
            return false;
        }
        for (KnkActionBinding action : item.actions()) {
            if (!MenuActionHandlers.PAGE_NEXT.equals(action.actionTypeId())
                    && !MenuActionHandlers.PAGE_PREV.equals(action.actionTypeId())) {
                return false;
            }
        }
        return true;
    }

    /** Pinned items + (for Java-mapped item sources) the fetched items, as one slot assignment. */
    private static SectionSlotAssignment toAssignment(ContentFetch fetch) {
        if (fetch == null) {
            return new SectionSlotAssignment(Map.of(), 0, 0, false, false);
        }
        Map<Integer, RuntimeMenuItem> slotAssignments = new HashMap<>(fetch.pool().pinnedBySlot());
        if (fetch.content() != null && !fetch.content().rows()) {
            List<?> fetchedItems = fetch.content().page().items();
            List<Integer> availablePool = fetch.pool().availablePool();
            for (int i = 0; i < fetchedItems.size() && i < availablePool.size(); i++) {
                if (fetchedItems.get(i) instanceof RuntimeMenuItem item) {
                    slotAssignments.put(availablePool.get(i), item);
                }
            }
        }
        int totalPages = fetch.totalPages();
        boolean hasNext = totalPages > 0 && fetch.page() < totalPages - 1;
        boolean hasPrev = totalPages > 0 && fetch.page() > 0;
        return new SectionSlotAssignment(Map.copyOf(slotAssignments), fetch.page(), totalPages, hasNext, hasPrev);
    }

    /**
     * Post-Phase-8 QOL follow-up: the "what does this button do" hint lines
     * for one item, shown only while the viewing player holds shift (see
     * {@link MenuControlHintListener}). Static per {@code actionTypeId}.
     */
    private static List<String> resolveControlHints(RuntimeMenuItem item) {
        List<String> hints = new ArrayList<>();
        for (KnkActionBinding action : item.actions()) {
            String actionTypeId = action.actionTypeId();
            if (MenuActionHandlers.CLOSE.equals(actionTypeId)) {
                hints.add(ChatColor.DARK_GRAY + "Click: close menu");
            } else if (MenuActionHandlers.OPEN.equals(actionTypeId)) {
                hints.add(ChatColor.DARK_GRAY + "Click: open menu");
            } else if (MenuActionHandlers.BACK.equals(actionTypeId)) {
                hints.add(ChatColor.DARK_GRAY + "Click: back");
            } else if (MenuActionHandlers.PAGE_NEXT.equals(actionTypeId)) {
                hints.add(ChatColor.DARK_GRAY + "Click: next page");
                hints.add(ChatColor.DARK_GRAY + "Shift-click: first page");
            } else if (MenuActionHandlers.PAGE_PREV.equals(actionTypeId)) {
                hints.add(ChatColor.DARK_GRAY + "Click: previous page");
                hints.add(ChatColor.DARK_GRAY + "Shift-click: first page");
            } else if (MenuActionHandlers.SEARCH_PROMPT.equals(actionTypeId)) {
                hints.add(ChatColor.DARK_GRAY + "Click: search");
                hints.add(ChatColor.DARK_GRAY + "Shift-click: clear search");
            } else if (MenuActionHandlers.FILTER_PROMPT.equals(actionTypeId)) {
                hints.add(ChatColor.DARK_GRAY + "Click: set filter value");
            } else if (MenuActionHandlers.FILTER_CYCLE.equals(actionTypeId)) {
                hints.add(ChatColor.DARK_GRAY + "Click: cycle filter value");
            } else if (MenuActionHandlers.FILTER_CLEAR.equals(actionTypeId)) {
                hints.add(ChatColor.DARK_GRAY + "Click: clear filter");
            } else if (MenuActionHandlers.CONFIRM_REQUEST.equals(actionTypeId)) {
                hints.add(ChatColor.DARK_GRAY + "Click: request confirmation");
            } else if (MenuActionHandlers.CONFIRM_ACCEPT.equals(actionTypeId)) {
                hints.add(ChatColor.DARK_GRAY + "Click: confirm");
            } else if (MenuActionHandlers.CONFIRM_CANCEL.equals(actionTypeId)) {
                hints.add(ChatColor.DARK_GRAY + "Click: cancel");
            } else if (MenuActionHandlers.DOUBLECLICK_CONFIRM.equals(actionTypeId)) {
                hints.add(ChatColor.DARK_GRAY + "Double-click: confirm");
            }
        }
        return hints;
    }

    /**
     * Post-Phase-8 QOL follow-up: live render-state lore on preset control
     * buttons - "Page X/Y" on pagination arrows, the active value on a
     * filter-cycle button, the active phrase on a search button, and a
     * "click again to confirm" line on an armed double-click button. Matched
     * by action id. Phase 9 (E9): the automatic "Page X/Y" line is skipped
     * when the item's own bindings already read {@code $section.…$} (a
     * template that renders its own pager text must not get a second line).
     */
    private void appendPresetStateLore(RuntimeMenuItem item, ItemStack itemStack, SectionSlotAssignment assignment,
                                       RuntimeMenuSection section, MenuSession session, long currentTick) {
        MenuContentQuery contentQuery = session.getContentQuery(section.id());
        for (KnkActionBinding action : item.actions()) {
            String actionTypeId = action.actionTypeId();
            if (MenuActionHandlers.PAGE_NEXT.equals(actionTypeId) || MenuActionHandlers.PAGE_PREV.equals(actionTypeId)) {
                if (!referencesSectionRoot(item.variableBindings())) {
                    int totalPages = Math.max(assignment.totalPages(), 1);
                    appendLoreLine(itemStack, ChatColor.GRAY + "Page " + (assignment.page() + 1) + "/" + totalPages);
                }
            } else if (MenuActionHandlers.FILTER_CYCLE.equals(actionTypeId)) {
                String facetKey = MenuParams.parse(action.paramsJson()).get("facetKey");
                String currentValue = facetKey != null ? contentQuery.filterValues().get(facetKey) : null;
                appendLoreLine(itemStack, ChatColor.GRAY + "Current: "
                        + (currentValue != null ? currentValue : ChatColor.DARK_GRAY + "(none)" + ChatColor.GRAY));
            } else if (MenuActionHandlers.SEARCH_PROMPT.equals(actionTypeId)) {
                String searchText = contentQuery.searchText();
                appendLoreLine(itemStack, ChatColor.GRAY + "Search: "
                        + (searchText != null && !searchText.isBlank() ? searchText : ChatColor.DARK_GRAY + "(none)" + ChatColor.GRAY));
            } else if (MenuActionHandlers.DOUBLECLICK_CONFIRM.equals(actionTypeId) && item.id() != null) {
                int windowTicks = parsePositiveIntOrDefault(
                        MenuParams.parse(action.paramsJson()).get("windowTicks"), DEFAULT_DOUBLECLICK_WINDOW_TICKS);
                if (session.isDoubleClickArmed(item.id(), currentTick, windowTicks)) {
                    appendLoreLine(itemStack, ChatColor.YELLOW + "Click again to confirm!");
                }
            }
        }
    }

    private static boolean referencesSectionRoot(List<KnkVariableBinding> bindings) {
        return bindings.stream().anyMatch(binding -> binding.expression() != null
                && binding.expression().contains("$" + MenuVariableProviderRegistry.ROOT_SECTION + "."));
    }

    private static void appendLoreLine(ItemStack itemStack, String line) {
        appendLoreLines(itemStack, List.of(line));
    }

    private static void appendLoreLines(ItemStack itemStack, List<String> lines) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return;
        }
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.addAll(lines);
        meta.setLore(lore);
        itemStack.setItemMeta(meta);
    }

    private static final int DEFAULT_DOUBLECLICK_WINDOW_TICKS = 60;

    private static int parsePositiveIntOrDefault(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * IMPLEMENTATION_PLAN.md Phase 5: builds the matching predicate from a
     * session's raw {@link MenuContentQuery} - search text matches
     * (case-insensitively, substring) against the item's resolved "Name"
     * binding, and every filter facet must match the resolved binding for
     * that {@code targetProperty} exactly (case-insensitively).
     */
    private static Predicate<RuntimeMenuItem> buildContentFilter(MenuContentQuery query, MenuSession session,
                                                                   Map<String, Object> variableContext, long currentTick) {
        String needle = query.searchText() != null ? query.searchText().trim().toLowerCase(Locale.ROOT) : null;
        boolean hasSearch = needle != null && !needle.isEmpty();
        Map<String, String> filters = query.filterValues();

        return item -> {
            if (hasSearch) {
                String name = VariableResolver.resolveName(item.variableBindings(), session, variableContext, currentTick);
                if (name == null || !name.toLowerCase(Locale.ROOT).contains(needle)) {
                    return false;
                }
            }
            for (Map.Entry<String, String> facet : filters.entrySet()) {
                String resolved = VariableResolver.resolveByTargetProperty(
                                item.variableBindings(), facet.getKey(), session, variableContext, currentTick)
                        .orElse(null);
                if (resolved == null || !resolved.equalsIgnoreCase(facet.getValue())) {
                    return false;
                }
            }
            return true;
        };
    }

    /** Whether a searchable section's auto-placed (non-pinned) content came back empty after filtering. */
    private static boolean matchedNoAutoContent(SectionSlotAssignment assignment) {
        return assignment.itemsBySlot().values().stream().noneMatch(item -> item.slotOverride() == null);
    }

    /**
     * IMPLEMENTATION_PLAN.md Phase 5 / DESIGN_REVIEW.md §2.1: an explicit
     * empty-results state, placed into the first of the section's own layout
     * slots not already occupied; silently skipped if none is free.
     */
    private static void placeEmptyResultsMarker(RuntimeMenuSection section, RuntimeMenu menu,
                                                  Map<Integer, ItemStack> itemStacksBySlot) {
        List<Integer> sectionSlots = MenuSlotCalculator.calculateSlots(
                section.displaySlot(), menu.totalSlots(), section.width(), section.height(),
                section.alignVertical(), section.alignHorizontal());

        Integer targetSlot = sectionSlots.stream()
                .filter(slot -> !itemStacksBySlot.containsKey(slot))
                .findFirst()
                .orElse(null);
        if (targetSlot == null) {
            return;
        }

        ItemStack marker = new ItemStack(Material.BARRIER);
        ItemMeta meta = marker.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(DisplayTextFormatter.translateToLegacy(ChatColor.RED + "No results found"));
            meta.setLore(List.of(DisplayTextFormatter.translateToLegacy(ChatColor.GRAY + "Try a different search or filter")));
            marker.setItemMeta(meta);
        }
        itemStacksBySlot.put(targetSlot, marker);
    }

    /**
     * Approximate game ticks (1 tick = 50ms), used as a monotonic clock for
     * TTL-policy variables and the auto-refresh schedule -
     * {@code System.currentTimeMillis()}-based rather than
     * {@code Bukkit.getCurrentTick()} so this doesn't depend on a specific
     * Paper API version being present.
     */
    static long currentTick() {
        return System.currentTimeMillis() / 50L;
    }

    /**
     * Must only be called from the main thread. InventoryMenu Phase 9 (E4):
     * writes only slots whose {@code ItemStack} actually changed and clears
     * slots that became empty, instead of {@code clear()} + rewrite - a
     * once-a-second repaint must not flicker the tooltip the player is
     * reading. {@code revealControls}: a render that happens while the player
     * is already sneaking shows control hints immediately.
     */
    public void applyToInventory(Inventory inventory, MenuRenderResult result, boolean revealControls) {
        int size = inventory.getSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack desired = result.itemStacksBySlot().get(slot);
            if (desired != null && revealControls) {
                List<String> hints = result.controlHintLoreBySlot().get(slot);
                if (hints != null && !hints.isEmpty()) {
                    desired = desired.clone();
                    appendLoreLines(desired, hints);
                }
            }
            ItemStack current = inventory.getItem(slot);
            if (!Objects.equals(isEmpty(current) ? null : current, desired)) {
                inventory.setItem(slot, desired);
            }
        }
    }

    private static boolean isEmpty(ItemStack itemStack) {
        return itemStack == null || itemStack.getType() == Material.AIR;
    }

    /** Menu follow-up 2026-09-26: the background every menu gets unless its template names another one. */
    static final Material DEFAULT_BACKGROUND = Material.GRAY_STAINED_GLASS_PANE;

    /**
     * Fills every slot nothing was rendered into (menu follow-up 2026-09-26: every menu has a
     * background). Material: the template's {@code BackgroundMaterialRefId}, else its
     * {@code BackgroundMaterial} name, else {@link #DEFAULT_BACKGROUND}. The filler has no name, no
     * lore and a hidden tooltip; it is not in {@code itemsBySlot}, so clicks on it do nothing.
     */
    private void fillBackground(RuntimeMenu menu, Map<Integer, String> keys, Map<Integer, ItemStack> itemStacksBySlot,
                                int totalSlots) {
        Material background = null;
        if (menu.backgroundMaterialRefId() != null) {
            background = MaterialNamespaceResolver.resolve(keys.get(menu.backgroundMaterialRefId()));
        }
        if (background == null && menu.backgroundMaterial() != null && !menu.backgroundMaterial().isBlank()) {
            background = MaterialNamespaceResolver.resolve(menu.backgroundMaterial());
            if (background == null) {
                warnOnce("background|" + menu.key() + "|" + menu.backgroundMaterial(), "Menu '" + menu.key()
                        + "' has unknown BackgroundMaterial '" + menu.backgroundMaterial() + "' - using the default");
            }
        }
        if (background == null || background.isAir()) {
            background = DEFAULT_BACKGROUND;
        }

        ItemStack filler = backgroundFiller(background);
        for (int slot = 0; slot < totalSlots; slot++) {
            if (!itemStacksBySlot.containsKey(slot)) {
                itemStacksBySlot.put(slot, filler.clone());
            }
        }
    }

    static ItemStack backgroundFiller(Material material) {
        ItemStack filler = new ItemStack(material);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.empty());
            meta.setHideTooltip(true);
            filler.setItemMeta(meta);
        }
        return filler;
    }

    private CompletableFuture<Map<Integer, String>> resolveMaterialKeys(Set<Integer> materialRefIds,
                                                                        Map<Integer, String> target) {
        if (materialRefIds.isEmpty()) {
            return CompletableFuture.completedFuture(target);
        }
        List<CompletableFuture<Void>> lookups = materialRefIds.stream()
                .map(id -> materialRefsDataAccess.getByIdAsync(id, null)
                        .thenAccept(result -> result.value().ifPresent(ref -> putIfKeyed(target, id, ref)))
                        .exceptionally(ignored -> null))
                .toList();
        return CompletableFuture.allOf(lookups.toArray(new CompletableFuture[0])).thenApply(ignored -> target);
    }

    private static void putIfKeyed(Map<Integer, String> target, Integer id, KnkMinecraftMaterialRef ref) {
        if (ref.namespaceKey() != null && !ref.namespaceKey().isBlank()) {
            target.put(id, ref.namespaceKey());
        }
    }

    private static int priorityRank(MenuRenderPriority priority) {
        return switch (priority) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
        };
    }

    /** Logs a data problem once per key for the lifetime of the server (not once per render tick). */
    static void warnOnce(String key, String message) {
        if (WARNED.add(key)) {
            LOGGER.warning(message);
        }
    }
}
