package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.material.KnkMinecraftMaterialRef;
import net.knightsandkings.knk.core.menu.MenuRenderPriority;
import net.knightsandkings.knk.core.menu.MenuSession;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
import net.knightsandkings.knk.core.menu.SectionSlotAssignment;
import net.knightsandkings.knk.paper.mapper.MaterialNamespaceResolver;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
     */
    public MenuRenderResult computeState(RuntimeMenu menu, MenuSession session) {
        Map<Integer, String> namespaceKeysByMaterialRefId = resolveMaterialNamespaceKeys(menu);

        Map<Integer, ItemStack> itemStacksBySlot = new HashMap<>();
        Map<Integer, RuntimeMenuItem> itemsBySlot = new HashMap<>();

        List<RuntimeMenuSection> sectionsByRenderOrder = menu.sections().stream()
                .sorted(Comparator.comparingInt(section -> priorityRank(section.priority())))
                .toList();

        for (RuntimeMenuSection section : sectionsByRenderOrder) {
            SectionSlotAssignment assignment = section.resolveSlots(menu.totalSlots(), session.getPage(section.id()));
            for (Map.Entry<Integer, RuntimeMenuItem> entry : assignment.itemsBySlot().entrySet()) {
                RuntimeMenuItem item = entry.getValue();
                String namespaceKey = item.materialRefId() != null
                        ? namespaceKeysByMaterialRefId.get(item.materialRefId())
                        : null;

                ItemStack itemStack = MenuItemBukkitMapper.toItemStack(item, namespaceKey);
                if (itemStack != null) {
                    itemStacksBySlot.put(entry.getKey(), itemStack);
                    itemsBySlot.put(entry.getKey(), item);
                }
            }
        }

        fillBackground(menu, namespaceKeysByMaterialRefId, itemStacksBySlot);

        return new MenuRenderResult(Map.copyOf(itemStacksBySlot), Map.copyOf(itemsBySlot));
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
