package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.material.KnkMinecraftMaterialRef;
import net.knightsandkings.knk.core.menu.MenuContentQuery;
import net.knightsandkings.knk.core.menu.MenuContentSourceRegistry;
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
                    permissionChecker, itemStacksBySlot, itemsBySlot, sectionsBySlot, section);

            if (queryActive && matchedNoAutoContent(assignment)) {
                placeEmptyResultsMarker(section, menu, assignment, itemStacksBySlot);
            }
        }

        fillBackground(menu, namespaceKeysByMaterialRefId, itemStacksBySlot);
        session.clearDirty();

        return new MenuRenderResult(Map.copyOf(itemStacksBySlot), Map.copyOf(itemsBySlot), Map.copyOf(sectionsBySlot));
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
                                  RuntimeMenuSection section) {
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
            }
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
     * A page request that overshoots the real last page (e.g. a filter just
     * narrowed the result set, or a stale click) comes back with zero items
     * but a correct {@code totalCount}; this re-fetches once at the clamped
     * page, the same "safe, harmless, one extra round-trip" cost
     * {@code RuntimeMenuSection#resolveSlots}'s in-memory clamp accepts for
     * free - accepted here too since the true last page can only be known
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

        Page<RuntimeMenuItem> page = fetchContentPage(section, context, requestedPage, pageSize, contentQuery);
        int totalPages = (int) Math.ceil(page.totalCount() / (double) pageSize);
        int clampedPage = totalPages > 0 ? Math.max(0, Math.min(requestedPage, totalPages - 1)) : 0;

        if (clampedPage != requestedPage) {
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

    /** Must only be called from the main thread. */
    public void applyToInventory(Inventory inventory, MenuRenderResult result) {
        inventory.clear();
        for (Map.Entry<Integer, ItemStack> entry : result.itemStacksBySlot().entrySet()) {
            inventory.setItem(entry.getKey(), entry.getValue());
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
