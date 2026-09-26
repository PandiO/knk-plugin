package net.knightsandkings.knk.paper.kit;

import net.knightsandkings.knk.api.impl.enchantment.LocalEnchantmentRepositoryImpl;
import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.item.KnkKitClaimResult;
import net.knightsandkings.knk.core.domain.item.KnkKitContent;
import net.knightsandkings.knk.core.domain.material.KnkMinecraftMaterialRef;
import net.knightsandkings.knk.paper.item.BlueprintItemAssembler;
import net.knightsandkings.knk.paper.mapper.ItemBlueprintBukkitMapper;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one, shared grant-placement routine every Kit-granting surface (command, first-join hook,
 * future menu) calls (docs/specs/kits/DESIGN.md §4.2) - resolves each item referenced by a
 * {@link KnkKitClaimResult} to a real {@link ItemStack} via the existing
 * {@link ItemBlueprintsDataAccess}/{@link ItemBlueprintBukkitMapper} pipeline (the same one
 * {@code ItemBlueprintsDebugCommand} already uses for {@code /knk itemblueprints give}), then
 * places each one in order (Helmet, Chestplate, Leggings, Boots, Shield, Hand, Contents by
 * ascending SlotIndex) using one unified five-step routine per item:
 * <ol>
 *   <li>Empty target slot -&gt; place directly.</li>
 *   <li>Occupied, existing stack {@link ItemStack#isSimilar(ItemStack)} matches -&gt; merge up to
 *       the item's {@code ItemBlueprint.MaxStackSize}; any amount that doesn't fit continues to
 *       step 3 as its own remainder stack.</li>
 *   <li>Occupied with a non-matching item (or a merge remainder) -&gt; first empty slot in the
 *       player's general inventory ({@link PlayerInventory#firstEmpty()}, indices 0-35 - a
 *       displaced item never bumps into a different armor slot).</li>
 *   <li>No empty slot anywhere -&gt; drop on the ground rather than losing it silently.</li>
 * </ol>
 * <p>
 * Split into an async {@link #resolveAsync} phase (network calls, safe off the main thread) and a
 * synchronous {@link #place} phase (touches {@link Player}/{@link org.bukkit.inventory.Inventory},
 * must run on the main thread) - callers resolve first, then hop onto the main thread (e.g. via
 * {@code Bukkit.getScheduler().runTask}) before calling {@link #place}, the same split
 * {@code ItemBlueprintsDebugCommand#handleGive} already uses for a single item.
 * <p>
 * Items are built by {@link BlueprintItemAssembler} (docs/specs/lootboxes/IMPLEMENTATION_PLAN.md
 * Phase 0), so kit items carry their blueprint's default enchantments the way
 * {@code /knk itemblueprints give} applies them. Kits don't fetch the full enchantment
 * definitions: each one is built from the blueprint row's denormalized fields, as permanent
 * enchantment books do. One that can't be resolved on this server is logged and left off; the
 * item is still granted.
 */
public final class KitGrantPlacer {

    private static final Logger LOGGER = Logger.getLogger(KitGrantPlacer.class.getName());

    private static final BlueprintItemAssembler ITEM_ASSEMBLER = new BlueprintItemAssembler(new LocalEnchantmentRepositoryImpl());

    private KitGrantPlacer() {
    }

    private enum SlotKind { HELMET, CHESTPLATE, LEGGINGS, BOOTS, SHIELD, HAND, CONTENT }

    private record PendingSlot(SlotKind kind, int itemBlueprintId, Integer quantity, Integer contentSlotIndex) {
    }

    /** One resolved, ready-to-place item, not yet touching any {@link Player}. */
    public record ResolvedItem(SlotKind kind, Integer contentSlotIndex, ItemStack itemStack, int maxStackSize) {
    }

    /** Outcome summary for chat feedback - how many items landed in each of the four ways. */
    public record PlacementSummary(int placed, int merged, int displaced, int dropped, int unresolved) {
        static PlacementSummary empty() {
            return new PlacementSummary(0, 0, 0, 0, 0);
        }
    }

    /**
     * Resolves every item referenced by {@code claimResult} to a built {@link ItemStack}, in
     * placement order (Helmet -&gt; Chestplate -&gt; Leggings -&gt; Boots -&gt; Shield -&gt; Hand
     * -&gt; Contents by ascending SlotIndex). Safe to call off the main thread - every lookup goes
     * through the async data-access gateways. An item whose {@code ItemBlueprint} can't be
     * resolved (deleted/unknown id) or whose material can't be mapped is skipped, not failed -
     * one bad reference in a Kit shouldn't block granting the rest of it.
     */
    public static CompletableFuture<List<ResolvedItem>> resolveAsync(
            KnkKitClaimResult claimResult,
            ItemBlueprintsDataAccess itemBlueprintsDataAccess,
            MinecraftMaterialRefsDataAccess minecraftMaterialRefsDataAccess
    ) {
        Objects.requireNonNull(claimResult, "claimResult must not be null");
        Objects.requireNonNull(itemBlueprintsDataAccess, "itemBlueprintsDataAccess must not be null");
        Objects.requireNonNull(minecraftMaterialRefsDataAccess, "minecraftMaterialRefsDataAccess must not be null");

        List<PendingSlot> pending = buildPendingSlots(claimResult);

        List<CompletableFuture<ResolvedItem>> futures = new ArrayList<>(pending.size());
        for (PendingSlot slot : pending) {
            futures.add(resolveOne(slot, itemBlueprintsDataAccess, minecraftMaterialRefsDataAccess));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(unused -> futures.stream()
                        .map(CompletableFuture::join)
                        .filter(Objects::nonNull)
                        .toList());
    }

    private static List<PendingSlot> buildPendingSlots(KnkKitClaimResult claimResult) {
        List<PendingSlot> pending = new ArrayList<>();

        addIfPresent(pending, SlotKind.HELMET, claimResult.helmetId());
        addIfPresent(pending, SlotKind.CHESTPLATE, claimResult.chestplateId());
        addIfPresent(pending, SlotKind.LEGGINGS, claimResult.leggingsId());
        addIfPresent(pending, SlotKind.BOOTS, claimResult.bootsId());
        addIfPresent(pending, SlotKind.SHIELD, claimResult.shieldId());
        addIfPresent(pending, SlotKind.HAND, claimResult.handId());

        if (claimResult.contents() != null) {
            List<KnkKitContent> sortedContents = claimResult.contents().stream()
                    .sorted(Comparator.comparingInt(KnkKitContent::slotIndex))
                    .toList();
            for (KnkKitContent content : sortedContents) {
                pending.add(new PendingSlot(SlotKind.CONTENT, content.itemBlueprintId(), content.quantity(), content.slotIndex()));
            }
        }

        return pending;
    }

    private static void addIfPresent(List<PendingSlot> pending, SlotKind kind, Integer itemBlueprintId) {
        if (itemBlueprintId != null) {
            pending.add(new PendingSlot(kind, itemBlueprintId, null, null));
        }
    }

    private static CompletableFuture<ResolvedItem> resolveOne(
            PendingSlot slot,
            ItemBlueprintsDataAccess itemBlueprintsDataAccess,
            MinecraftMaterialRefsDataAccess minecraftMaterialRefsDataAccess
    ) {
        return itemBlueprintsDataAccess.getByIdAsync(slot.itemBlueprintId())
                .thenCompose(result -> {
                    KnkItemBlueprint blueprint = result != null ? result.value().orElse(null) : null;
                    if (blueprint == null) {
                        LOGGER.warning("KitGrantPlacer: ItemBlueprint id=" + slot.itemBlueprintId() + " not found, skipping slot " + slot.kind());
                        return CompletableFuture.completedFuture(null);
                    }

                    return resolveMaterialNamespaceKey(blueprint, minecraftMaterialRefsDataAccess)
                            .thenApply(materialNamespaceKey -> buildResolvedItem(slot, blueprint, materialNamespaceKey));
                })
                .exceptionally(ex -> {
                    LOGGER.log(Level.WARNING, "KitGrantPlacer: failed to resolve ItemBlueprint id=" + slot.itemBlueprintId(), ex);
                    return null;
                });
    }

    private static ResolvedItem buildResolvedItem(PendingSlot slot, KnkItemBlueprint blueprint, String materialNamespaceKey) {
        if (materialNamespaceKey == null || materialNamespaceKey.isBlank()) {
            LOGGER.warning("KitGrantPlacer: ItemBlueprint id=" + blueprint.id() + " has no resolvable material, skipping slot " + slot.kind());
            return null;
        }

        final ItemStack itemStack;
        try {
            itemStack = ITEM_ASSEMBLER.build(blueprint, materialNamespaceKey);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "KitGrantPlacer: failed to map ItemBlueprint id=" + blueprint.id() + " to a Bukkit item", ex);
            return null;
        }

        try {
            BlueprintItemAssembler.Result assembled = ITEM_ASSEMBLER.enchant(itemStack, blueprint,
                    BlueprintItemAssembler.defaultEnchantments(blueprint, Map.of()), BlueprintItemAssembler.Options.DEFAULTS);
            if (!assembled.skipped().isEmpty()) {
                LOGGER.warning("KitGrantPlacer: ItemBlueprint id=" + blueprint.id() + " skipped enchantments: " + String.join(", ", assembled.skipped()));
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "KitGrantPlacer: failed to enchant ItemBlueprint id=" + blueprint.id() + ", granting it as built", ex);
        }

        int maxStackSize = blueprint.maxStackSize() != null && blueprint.maxStackSize() > 0
                ? blueprint.maxStackSize()
                : itemStack.getMaxStackSize();

        if (slot.kind() == SlotKind.CONTENT && slot.quantity() != null) {
            itemStack.setAmount(Math.max(1, Math.min(slot.quantity(), maxStackSize)));
        }

        return new ResolvedItem(slot.kind(), slot.contentSlotIndex(), itemStack, maxStackSize);
    }

    /** The blueprint's icon material key (its material ref's namespace key, else its own) - shared with the menus. */
    public static CompletableFuture<String> resolveMaterialNamespaceKey(
            KnkItemBlueprint blueprint,
            MinecraftMaterialRefsDataAccess minecraftMaterialRefsDataAccess
    ) {
        if (blueprint.iconMaterialRefId() != null && blueprint.iconMaterialRefId() > 0) {
            return minecraftMaterialRefsDataAccess.getByIdAsync(blueprint.iconMaterialRefId())
                    .thenApply(result -> {
                        KnkMinecraftMaterialRef materialRef = result != null ? result.value().orElse(null) : null;
                        if (materialRef != null && materialRef.namespaceKey() != null && !materialRef.namespaceKey().isBlank()) {
                            return materialRef.namespaceKey();
                        }
                        return blueprint.iconNamespaceKey();
                    });
        }

        return CompletableFuture.completedFuture(blueprint.iconNamespaceKey());
    }

    /**
     * Places every resolved item in order, applying the unified five-step routine to each one in
     * turn (so a step-3/4 fallback correctly sees the effect of every item placed before it).
     * Main-thread only - touches {@link Player}/{@link org.bukkit.inventory.Inventory}.
     */
    public static PlacementSummary place(Player player, List<ResolvedItem> items) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(items, "items must not be null");

        int placed = 0;
        int merged = 0;
        int displaced = 0;
        int dropped = 0;

        for (ResolvedItem item : items) {
            PlacementSummary result = placeOne(player, item);
            placed += result.placed();
            merged += result.merged();
            displaced += result.displaced();
            dropped += result.dropped();
        }

        return new PlacementSummary(placed, merged, displaced, dropped, 0);
    }

    private static PlacementSummary placeOne(Player player, ResolvedItem item) {
        SlotAccessor accessor = accessorFor(player, item);
        ItemStack toPlace = item.itemStack();

        ItemStack existing = accessor.get();
        if (isEmpty(existing)) {
            accessor.set(toPlace);
            return new PlacementSummary(1, 0, 0, 0, 0);
        }

        boolean merged = false;
        if (existing.isSimilar(toPlace)) {
            int spaceLeft = item.maxStackSize() - existing.getAmount();
            if (spaceLeft > 0) {
                int toMerge = Math.min(spaceLeft, toPlace.getAmount());
                existing.setAmount(existing.getAmount() + toMerge);
                accessor.set(existing);
                merged = true;

                int remainder = toPlace.getAmount() - toMerge;
                if (remainder <= 0) {
                    return new PlacementSummary(0, 1, 0, 0, 0);
                }

                toPlace = toPlace.clone();
                toPlace.setAmount(remainder);
            }
        }

        // Displaced: occupied by a non-matching item, or a merge remainder that didn't fully fit.
        int firstEmpty = player.getInventory().firstEmpty();
        if (firstEmpty >= 0) {
            player.getInventory().setItem(firstEmpty, toPlace);
            return merged ? new PlacementSummary(0, 1, 0, 0, 0) : new PlacementSummary(0, 0, 1, 0, 0);
        }

        player.getWorld().dropItemNaturally(player.getLocation(), toPlace);
        return merged ? new PlacementSummary(0, 1, 0, 0, 0) : new PlacementSummary(0, 0, 0, 1, 0);
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir();
    }

    private interface SlotAccessor {
        ItemStack get();
        void set(ItemStack stack);
    }

    private static SlotAccessor accessorFor(Player player, ResolvedItem item) {
        PlayerInventory inventory = player.getInventory();
        return switch (item.kind()) {
            case HELMET -> new SlotAccessor() {
                public ItemStack get() { return inventory.getHelmet(); }
                public void set(ItemStack stack) { inventory.setHelmet(stack); }
            };
            case CHESTPLATE -> new SlotAccessor() {
                public ItemStack get() { return inventory.getChestplate(); }
                public void set(ItemStack stack) { inventory.setChestplate(stack); }
            };
            case LEGGINGS -> new SlotAccessor() {
                public ItemStack get() { return inventory.getLeggings(); }
                public void set(ItemStack stack) { inventory.setLeggings(stack); }
            };
            case BOOTS -> new SlotAccessor() {
                public ItemStack get() { return inventory.getBoots(); }
                public void set(ItemStack stack) { inventory.setBoots(stack); }
            };
            case SHIELD -> new SlotAccessor() {
                public ItemStack get() { return inventory.getItemInOffHand(); }
                public void set(ItemStack stack) { inventory.setItemInOffHand(stack); }
            };
            // "Hand" targets whatever hotbar slot is currently selected at grant time (DESIGN.md
            // §4.2), not a stored original index - re-read on every access rather than captured
            // once, in case something (unlikely, mid-synchronous-loop) changed it.
            case HAND -> new SlotAccessor() {
                public ItemStack get() { return inventory.getItem(inventory.getHeldItemSlot()); }
                public void set(ItemStack stack) { inventory.setItem(inventory.getHeldItemSlot(), stack); }
            };
            case CONTENT -> {
                int slotIndex = item.contentSlotIndex();
                yield new SlotAccessor() {
                    public ItemStack get() { return inventory.getItem(slotIndex); }
                    public void set(ItemStack stack) { inventory.setItem(slotIndex, stack); }
                };
            }
        };
    }
}
