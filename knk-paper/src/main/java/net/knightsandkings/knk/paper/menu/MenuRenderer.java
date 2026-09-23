package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.material.KnkMinecraftMaterialRef;
import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;
import net.knightsandkings.knk.core.menu.MenuContentQuery;
import net.knightsandkings.knk.core.menu.MenuContentSourceRegistry;
import net.knightsandkings.knk.core.menu.MenuParams;
import net.knightsandkings.knk.core.menu.MenuRenderPriority;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.menu.MenuSlotCalculator;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
import net.knightsandkings.knk.core.menu.SectionSlotAssignment;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Computes and applies a {@link RuntimeMenu}'s Inventory contents for one
 * player's current {@link MenuSession} state (current page per section).
 * <p>
 * {@link #computeState} does the "expensive work" (material-ref resolution,
 * per-section layout/pagination, ItemStack construction) - IMPLEMENTATION_PLAN.md
 * Phase 2's async-rendering requirement (fixes bug #6). It's written to block
 * on its own async data-access calls with {@code join()}, which is only safe
 * because every caller in this codebase runs it from inside
 * {@code Bukkit.getScheduler().runTaskAsynchronously} - never call it from the
 * main thread. {@link #applyToInventory} is the cheap, synchronous remainder
 * that actually mutates the {@code Inventory}, and must only ever be called
 * from the main thread (via {@code Bukkit.getScheduler().runTask}).
 */
public final class MenuRenderer {

    private static final Logger LOGGER = Logger.getLogger(MenuRenderer.class.getName());

    private final MinecraftMaterialRefsDataAccess materialRefsDataAccess;
    private final MenuContentSourceRegistry<MenuContentSourceContext> contentSourceRegistry;

    public MenuRenderer(
            MinecraftMaterialRefsDataAccess materialRefsDataAccess,
            MenuContentSourceRegistry<MenuContentSourceContext> contentSourceRegistry
    ) {
        this.materialRefsDataAccess = materialRefsDataAccess;
        this.contentSourceRegistry = contentSourceRegistry;
    }

    /**
     * Blocks (on already-off-main-thread async data-access calls) while
     * resolving material refs and building the desired slot contents. Do not
     * call this from the main thread.
     * <p>
     * {@code session.clearDirty()} is called once, after every item in this
     * pass has had a chance to observe {@code ON_DIRTY} variables as stale
     * (IMPLEMENTATION_PLAN.md Phase 3, DESIGN_REVIEW.md §1) - a single dirty
     * flag must stay true for every binding in the pass that triggered it,
     * not be consumed by whichever binding happens to resolve first.
     */
    public MenuRenderResult computeState(RuntimeMenu menu, MenuSession session, Player player) {
        Map<Integer, String> namespaceKeysByMaterialRefId = resolveMaterialNamespaceKeys(menu);
        Map<String, Object> variableContext = MenuVariableContext.liveValues(player);
        Predicate<String> permissionChecker = player::hasPermission;
        long currentTick = currentTick();

        Map<Integer, ItemStack> itemStacksBySlot = new HashMap<>();
        Map<Integer, RuntimeMenuItem> itemsBySlot = new HashMap<>();
        Map<Integer, RuntimeMenuSection> sectionsBySlot = new HashMap<>();
        Map<Integer, List<String>> controlHintLoreBySlot = new HashMap<>();

        List<RuntimeMenuSection> sectionsByRenderOrder = menu.sections().stream()
                .sorted(Comparator.comparingInt(section -> priorityRank(section.priority())))
                .toList();

        for (RuntimeMenuSection section : sectionsByRenderOrder) {
            // IMPLEMENTATION_PLAN.md Phase 4 - a section the player lacks
            // visibilityPermission for is skipped entirely, hiding every item in
            // it rather than gating each item individually (DESIGN_REVIEW.md §2.4).
            if (!section.isVisibleTo(permissionChecker)) {
                continue;
            }

            boolean queryActive = section.searchable() && !session.getContentQuery(section.id()).isEmpty();

            SectionSlotAssignment assignment;
            if (section.hasContentSource()) {
                // IMPLEMENTATION_PLAN.md Phase 8: a real paged/cursor query
                // against the registered MenuContentSource replaces the
                // legacy in-memory items() pagination entirely for this
                // section - see #resolveContentSourceAssignment.
                assignment = resolveContentSourceAssignment(section, menu, session, player, namespaceKeysByMaterialRefId);
            } else {
                // IMPLEMENTATION_PLAN.md Phase 5 / DESIGN_REVIEW.md §2.1 §2.3: a
                // searchable section's active MenuContentQuery narrows its auto-
                // placed content BEFORE resolveSlots paginates it - the predicate
                // itself has to be built here, not in knk-core, since matching
                // requires resolved display text (VariableResolver + the live
                // Player context knk-core deliberately doesn't have).
                Predicate<RuntimeMenuItem> contentFilter = queryActive
                        ? buildContentFilter(session.getContentQuery(section.id()), session, variableContext, currentTick)
                        : item -> true;
                assignment = section.resolveSlots(menu.totalSlots(), session.getPage(section.id()), contentFilter);
            }

            applyAssignment(assignment, namespaceKeysByMaterialRefId, session, variableContext, currentTick,
                    permissionChecker, itemStacksBySlot, itemsBySlot, sectionsBySlot, controlHintLoreBySlot, section);

            // Post-Phase-8 QOL follow-up: pagination/filter/search/confirm
            // preset buttons show their own live state in their lore
            // (current page, active filter value, active search phrase,
            // double-click-armed countdown) - appended directly onto the
            // already-built ItemStack rather than through a new variable-
            // binding placeholder syntax, since none of this is per-template
            // author-supplied text, it's render-pass state.
            appendPresetStateLore(section, assignment, session, currentTick, itemStacksBySlot);

            if (queryActive && matchedNoAutoContent(assignment)) {
                placeEmptyResultsMarker(section, menu, assignment, itemStacksBySlot);
            }
        }

        fillBackground(menu, namespaceKeysByMaterialRefId, itemStacksBySlot);
        session.clearDirty();

        return new MenuRenderResult(Map.copyOf(itemStacksBySlot), Map.copyOf(itemsBySlot), Map.copyOf(sectionsBySlot),
                Map.copyOf(controlHintLoreBySlot));
    }

    /**
     * Shared per-slot placement step both the legacy in-memory-list path and
     * the Phase 8 content-source path funnel through, so a catalog-fetched
     * item renders via the exact same {@code MenuItemBukkitMapper.toItemStack}
     * call (material resolution, variable resolution, permission-aware
     * display mode) every hand-authored template item already does - no
     * second rendering code path.
     */
    private void applyAssignment(SectionSlotAssignment assignment, Map<Integer, String> namespaceKeysByMaterialRefId,
                                  MenuSession session, Map<String, Object> variableContext, long currentTick,
                                  Predicate<String> permissionChecker, Map<Integer, ItemStack> itemStacksBySlot,
                                  Map<Integer, RuntimeMenuItem> itemsBySlot, Map<Integer, RuntimeMenuSection> sectionsBySlot,
                                  Map<Integer, List<String>> controlHintLoreBySlot, RuntimeMenuSection section) {
        for (Map.Entry<Integer, RuntimeMenuItem> entry : assignment.itemsBySlot().entrySet()) {
            RuntimeMenuItem item = entry.getValue();
            String namespaceKey = item.materialRefId() != null
                    ? namespaceKeysByMaterialRefId.get(item.materialRefId())
                    : null;

            ItemStack itemStack = MenuItemBukkitMapper.toItemStack(
                    item, namespaceKey, session, variableContext, currentTick, permissionChecker);
            if (itemStack != null) {
                itemStacksBySlot.put(entry.getKey(), itemStack);
                itemsBySlot.put(entry.getKey(), item);
                sectionsBySlot.put(entry.getKey(), section);

                List<String> hints = resolveControlHints(item);
                if (!hints.isEmpty()) {
                    controlHintLoreBySlot.put(entry.getKey(), hints);
                }
            }
        }
    }

    /**
     * Post-Phase-8 QOL follow-up: the "what does this button do" hint lines
     * for one item, shown only while the viewing player holds shift (see
     * {@link MenuControlHintListener}) rather than as always-on lore clutter
     * - the developer's explicit ask ("make all function buttons reveal the
     * controls in the lore when hovering with shift"). Static per {@code
     * actionTypeId} - unlike {@link #appendPresetStateLore}'s lines, these
     * never depend on session/query state, so they're computed once here and
     * replayed live by the sneak-toggle listener without a full re-render.
     */
    private static List<String> resolveControlHints(RuntimeMenuItem item) {
        List<String> hints = new ArrayList<>();
        for (KnkActionBinding action : item.actions()) {
            String actionTypeId = action.actionTypeId();
            if (MenuActionHandlers.CLOSE.equals(actionTypeId)) {
                hints.add(ChatColor.DARK_GRAY + "Click: close menu");
            } else if (MenuActionHandlers.OPEN.equals(actionTypeId)) {
                hints.add(ChatColor.DARK_GRAY + "Click: open menu");
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
     * Post-Phase-8 QOL follow-up: appends live render-state lore lines onto
     * the already-built {@link ItemStack}s for this section's preset control
     * buttons - "Page X/Y" on pagination arrows, the active value on a
     * filter-cycle button, the active phrase on a search button, and a
     * "click again to confirm" countdown on an armed
     * {@code menu.confirm.doubleclick} button. Matched by
     * {@link KnkActionBinding#actionTypeId()} on the item's own actions list,
     * not by section kind or item name, so this works for any button wired
     * to these action ids regardless of which menu/section it lives in.
     */
    private void appendPresetStateLore(RuntimeMenuSection section, SectionSlotAssignment assignment,
                                        MenuSession session, long currentTick, Map<Integer, ItemStack> itemStacksBySlot) {
        MenuContentQuery contentQuery = session.getContentQuery(section.id());

        for (Map.Entry<Integer, RuntimeMenuItem> entry : assignment.itemsBySlot().entrySet()) {
            RuntimeMenuItem item = entry.getValue();
            ItemStack itemStack = itemStacksBySlot.get(entry.getKey());
            if (itemStack == null) {
                continue;
            }

            for (KnkActionBinding action : item.actions()) {
                String actionTypeId = action.actionTypeId();
                if (MenuActionHandlers.PAGE_NEXT.equals(actionTypeId) || MenuActionHandlers.PAGE_PREV.equals(actionTypeId)) {
                    int totalPages = Math.max(assignment.totalPages(), 1);
                    appendLoreLine(itemStack, ChatColor.GRAY + "Page " + (assignment.page() + 1) + "/" + totalPages);
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
    }

    private static void appendLoreLine(ItemStack itemStack, String line) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return;
        }
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(line);
        meta.setLore(lore);
        itemStack.setItemMeta(meta);
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
     * IMPLEMENTATION_PLAN.md Phase 8: resolves a {@code contentSourceId}-bound
     * section's current page via a real paged/cursor query against the
     * registered {@link net.knightsandkings.knk.core.menu.MenuContentSource}
     * (e.g. {@code catalog.itemblueprints}, backed by
     * {@code ItemBlueprintsDataAccess.searchAsync}) rather than pagination
     * over an in-memory {@code items()} list - the section's own
     * {@link RuntimeMenuSection#items()} is expected to hold only pinned
     * control buttons (see {@link RuntimeMenuSection#computeSlotPool}), which
     * are placed here exactly like the legacy path places them.
     * <p>
     * The session's active {@link MenuContentQuery} (open question 3 - see
     * IMPLEMENTATION_PLAN.md Phase 8's task text) is threaded into the
     * {@link PagedQuery} itself as {@code searchTerm}/{@code filters} - the
     * backing store narrows its full result set before paging, unlike the
     * legacy path's {@link #buildContentFilter}, which can only narrow
     * whatever the in-memory list already holds. {@code MenuSession} pages
     * are 0-based (see {@link MenuSession#getPage}); the web-api's
     * {@code PagedQuery.pageNumber}/EF Core pagination convention is 1-based
     * (confirmed against {@code ItemBlueprintRepository.SearchAsync}'s
     * {@code Skip((PageNumber - 1) * PageSize)}) - the {@code +1}/{@code -1}
     * conversions below are that boundary, not an off-by-one bug.
     * <p>
     * A page request that overshoots the real last page (from
     * {@code MenuService.changePage}'s unclamped advance) or goes negative
     * (from wrapping backward past page 0) comes back with zero items but a
     * correct {@code totalCount}; this re-fetches once at the wrapped page
     * ({@link RuntimeMenuSection#resolveSlots}'s in-memory pagination wraps
     * the same way - see its own javadoc), the same "safe, harmless, one
     * extra round-trip" cost accepted there, for the same reason: the true
     * page count - and so where wrapping actually lands - can only be known
     * after the first fetch reveals {@code totalCount}.
     */
    private SectionSlotAssignment resolveContentSourceAssignment(RuntimeMenuSection section, RuntimeMenu menu,
                                                                   MenuSession session, Player player,
                                                                   Map<Integer, String> namespaceKeysByMaterialRefId) {
        RuntimeMenuSection.SlotPool pool = section.computeSlotPool(menu.totalSlots());
        int pageSize = pool.availablePool().size();

        if (pageSize == 0) {
            return new SectionSlotAssignment(pool.pinnedBySlot(), 0, 0, false, false);
        }

        MenuContentQuery contentQuery = section.searchable() ? session.getContentQuery(section.id()) : MenuContentQuery.EMPTY;
        MenuContentSourceContext context = new MenuContentSourceContext(player, session);
        int requestedPage = session.getPage(section.id());

        // Post-Phase-8 QOL follow-up: MenuService.changePage now advances a
        // content-source section's page unconditionally in either direction
        // (page + 1 past the last page, or page - 1 below 0 from
        // MenuSession.previousPage's own wraparound) rather than clamping -
        // the real totalPages count (and therefore where "wrap around" lands)
        // is only known once the fetch below reveals totalCount. A negative
        // requestedPage can't be sent to the 1-based PagedQuery API at all,
        // so the first fetch always uses page 0 in that case; the wrap below
        // then resolves the real target page and re-fetches it.
        int firstFetchPage = Math.max(requestedPage, 0);
        Page<RuntimeMenuItem> page = fetchContentPage(section, context, firstFetchPage, pageSize, contentQuery);
        int totalPages = (int) Math.ceil(page.totalCount() / (double) pageSize);
        int clampedPage = totalPages > 0 ? Math.floorMod(requestedPage, totalPages) : 0;

        if (clampedPage != firstFetchPage) {
            session.setPage(section.id(), clampedPage);
            page = fetchContentPage(section, context, clampedPage, pageSize, contentQuery);
        }

        resolveAdditionalMaterialNamespaceKeys(page.items(), namespaceKeysByMaterialRefId);

        Map<Integer, RuntimeMenuItem> slotAssignments = new HashMap<>(pool.pinnedBySlot());
        List<Integer> availablePool = pool.availablePool();
        List<RuntimeMenuItem> fetchedItems = page.items();
        for (int i = 0; i < fetchedItems.size() && i < availablePool.size(); i++) {
            slotAssignments.put(availablePool.get(i), fetchedItems.get(i));
        }

        boolean hasNext = totalPages > 0 && clampedPage < totalPages - 1;
        boolean hasPrev = totalPages > 0 && clampedPage > 0;

        return new SectionSlotAssignment(Map.copyOf(slotAssignments), clampedPage, totalPages, hasNext, hasPrev);
    }

    /** @param page0 0-based (MenuSession convention) - converted to the 1-based PagedQuery/web-api convention here. */
    private Page<RuntimeMenuItem> fetchContentPage(RuntimeMenuSection section, MenuContentSourceContext context,
                                                     int page0, int pageSize, MenuContentQuery contentQuery) {
        PagedQuery query = new PagedQuery(page0 + 1, pageSize, contentQuery.searchText(), null, false, contentQuery.filterValues());
        try {
            return contentSourceRegistry.fetchPage(section.contentSourceId(), context, query).join();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "MenuContentSource '" + section.contentSourceId() + "' for section '"
                    + section.name() + "' failed to fetch page " + page0 + " - rendering it empty this pass", e);
            return new Page<>(List.of(), 0, page0 + 1, pageSize);
        }
    }

    /**
     * On-demand counterpart to {@link #resolveMaterialNamespaceKeys(RuntimeMenu)}:
     * that method only knows about material refs referenced by persisted
     * {@code MenuItemTemplate} rows at assembly time, so a content-source-
     * fetched item's {@code materialRefId} (e.g. an {@code ItemBlueprint}'s
     * real icon) needs its own resolution pass once the fetch reveals it.
     */
    private void resolveAdditionalMaterialNamespaceKeys(List<RuntimeMenuItem> items,
                                                          Map<Integer, String> namespaceKeysByMaterialRefId) {
        Set<Integer> missingIds = new HashSet<>();
        for (RuntimeMenuItem item : items) {
            if (item.materialRefId() != null && !namespaceKeysByMaterialRefId.containsKey(item.materialRefId())) {
                missingIds.add(item.materialRefId());
            }
        }
        if (missingIds.isEmpty()) {
            return;
        }

        List<CompletableFuture<Void>> fetches = missingIds.stream()
                .map(id -> materialRefsDataAccess.getByIdAsync(id, null)
                        .thenAccept(result -> result.value().ifPresent(ref -> putIfKeyed(namespaceKeysByMaterialRefId, id, ref)))
                        .exceptionally(ignored -> null))
                .toList();
        CompletableFuture.allOf(fetches.toArray(new CompletableFuture[0])).join();
    }

    /**
     * IMPLEMENTATION_PLAN.md Phase 5: builds the actual matching predicate
     * from a session's raw {@link MenuContentQuery} - search text matches
     * (case-insensitively, substring) against the item's resolved "Name"
     * binding, and every filter facet must match the resolved binding for
     * that {@code targetProperty} exactly (case-insensitively). Both must
     * pass when both are set (DESIGN_REVIEW.md §2.3: search and filters
     * compose, they don't override each other).
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
     * empty-results state - neither legacy system had one. Placed into the
     * first of the section's own layout slots not already occupied by a
     * pinned item (e.g. a persistent search/clear button); silently skipped
     * if none is free (a fully pinned section has nowhere to put it).
     */
    private static void placeEmptyResultsMarker(RuntimeMenuSection section, RuntimeMenu menu,
                                                  SectionSlotAssignment assignment, Map<Integer, ItemStack> itemStacksBySlot) {
        List<Integer> sectionSlots = MenuSlotCalculator.calculateSlots(
                section.displaySlot(), menu.totalSlots(), section.width(), section.height(),
                section.alignVertical(), section.alignHorizontal());

        Integer targetSlot = sectionSlots.stream()
                .filter(slot -> !assignment.itemsBySlot().containsKey(slot))
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
     * Approximate game ticks (1 tick = 50ms), used only as a monotonic clock
     * for TTL-policy variables - {@code System.currentTimeMillis()}-based
     * rather than {@code Bukkit.getCurrentTick()} so this doesn't depend on a
     * specific Paper API version being present.
     */
    private static long currentTick() {
        return System.currentTimeMillis() / 50L;
    }

    /**
     * Must only be called from the main thread. {@code revealControls}
     * (post-Phase-8 QOL follow-up) is the render-time equivalent of
     * {@link MenuControlHintListener}'s live sneak-toggle: a render that
     * happens to occur while the player is already sneaking (e.g. a page
     * turn) should come out of the gate showing control hints too, not wait
     * for the next sneak toggle to add them.
     */
    public void applyToInventory(Inventory inventory, MenuRenderResult result, boolean revealControls) {
        inventory.clear();
        for (Map.Entry<Integer, ItemStack> entry : result.itemStacksBySlot().entrySet()) {
            ItemStack itemStack = entry.getValue();
            if (revealControls) {
                List<String> hints = result.controlHintLoreBySlot().get(entry.getKey());
                if (hints != null && !hints.isEmpty()) {
                    appendLoreLines(itemStack, hints);
                }
            }
            inventory.setItem(entry.getKey(), itemStack);
        }
    }

    private void fillBackground(RuntimeMenu menu, Map<Integer, String> namespaceKeysByMaterialRefId,
                                 Map<Integer, ItemStack> itemStacksBySlot) {
        if (menu.backgroundMaterialRefId() == null) {
            return;
        }

        String namespaceKey = namespaceKeysByMaterialRefId.get(menu.backgroundMaterialRefId());
        Material background = MaterialNamespaceResolver.resolve(namespaceKey);
        if (background == null) {
            return;
        }

        for (int slot = 0; slot < menu.totalSlots(); slot++) {
            itemStacksBySlot.computeIfAbsent(slot, ignored -> new ItemStack(background));
        }
    }

    private Map<Integer, String> resolveMaterialNamespaceKeys(RuntimeMenu menu) {
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

        Map<Integer, String> namespaceKeysByMaterialRefId = new HashMap<>();
        List<CompletableFuture<Void>> fetches = materialRefIds.stream()
                .map(id -> materialRefsDataAccess.getByIdAsync(id, null)
                        .thenAccept(result -> result.value().ifPresent(ref -> putIfKeyed(namespaceKeysByMaterialRefId, id, ref)))
                        .exceptionally(ignored -> null))
                .toList();

        CompletableFuture.allOf(fetches.toArray(new CompletableFuture[0])).join();

        return namespaceKeysByMaterialRefId;
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
}
