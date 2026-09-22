package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.material.KnkMinecraftMaterialRef;
import net.knightsandkings.knk.core.menu.MenuContentQuery;
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

    private final MinecraftMaterialRefsDataAccess materialRefsDataAccess;

    public MenuRenderer(MinecraftMaterialRefsDataAccess materialRefsDataAccess) {
        this.materialRefsDataAccess = materialRefsDataAccess;
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

            // IMPLEMENTATION_PLAN.md Phase 5 / DESIGN_REVIEW.md §2.1 §2.3: a
            // searchable section's active MenuContentQuery narrows its auto-
            // placed content BEFORE resolveSlots paginates it - the predicate
            // itself has to be built here, not in knk-core, since matching
            // requires resolved display text (VariableResolver + the live
            // Player context knk-core deliberately doesn't have).
            MenuContentQuery contentQuery = session.getContentQuery(section.id());
            boolean queryActive = section.searchable() && !contentQuery.isEmpty();
            Predicate<RuntimeMenuItem> contentFilter = queryActive
                    ? buildContentFilter(contentQuery, session, variableContext, currentTick)
                    : item -> true;

            SectionSlotAssignment assignment = section.resolveSlots(
                    menu.totalSlots(), session.getPage(section.id()), contentFilter);
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

            if (queryActive && matchedNoAutoContent(assignment)) {
                placeEmptyResultsMarker(section, menu, assignment, itemStacksBySlot);
            }
        }

        fillBackground(menu, namespaceKeysByMaterialRefId, itemStacksBySlot);
        session.clearDirty();

        return new MenuRenderResult(Map.copyOf(itemStacksBySlot), Map.copyOf(itemsBySlot), Map.copyOf(sectionsBySlot));
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
