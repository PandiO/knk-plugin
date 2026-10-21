package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.item.KnkKit;
import net.knightsandkings.knk.core.domain.item.KnkKitClaimResult;
import net.knightsandkings.knk.core.ports.api.KitsQueryApi;
import net.knightsandkings.knk.paper.kit.KitGrantPlacer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Kit loans for the duel kit modes (arena duels DESIGN §8.4, developer O4): the kit is read from the API and placed
 * through {@link KitGrantPlacer} with <b>no claim</b> - no cost, cooldown or access check (the arena's eligible list
 * is the only gate) - and every granted stack carries {@code knk_duel_loan=<matchToken>}. Loaned stacks are removed
 * before the vault is restored, by the inventory guard, and by the join sweep. Kept general for the siege kit mode
 * (KNG-199).
 */
public interface DuelKitLoans {

    /**
     * Grant kit {@code kitId} to {@code player} for match {@code token}. Completes on the main thread, true when the
     * kit was placed; false when it couldn't be read, or a newer grant for the player replaced it.
     */
    CompletableFuture<Boolean> grant(Player player, UUID token, int kitId);

    /** Remove every loaned stack (any match) from the player's inventory, armour, off-hand and cursor. */
    int removeLoans(Player player);

    /** The real one. */
    final class Paper implements DuelKitLoans {
        private static final Logger LOGGER = Logger.getLogger(Paper.class.getName());

        private final KitsQueryApi kits;
        private final ItemBlueprintsDataAccess blueprints;
        private final MinecraftMaterialRefsDataAccess materials;
        private final DuelItems items;
        private final Executor mainThread;
        /** The newest grant per player: a swap while an older grant still resolves drops the older one. */
        private final Map<UUID, Long> generation = new ConcurrentHashMap<>();
        private final AtomicLong grants = new AtomicLong();

        public Paper(KitsQueryApi kits, ItemBlueprintsDataAccess blueprints, MinecraftMaterialRefsDataAccess materials,
                     DuelItems items, Executor mainThread) {
            this.kits = Objects.requireNonNull(kits, "kits");
            this.blueprints = Objects.requireNonNull(blueprints, "blueprints");
            this.materials = Objects.requireNonNull(materials, "materials");
            this.items = Objects.requireNonNull(items, "items");
            this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        }

        @Override
        public CompletableFuture<Boolean> grant(Player player, UUID token, int kitId) {
            long mine = grants.incrementAndGet();
            generation.put(player.getUniqueId(), mine);
            CompletableFuture<Boolean> placed = new CompletableFuture<>();
            kits.getById(kitId)
                    .thenCompose(kit -> KitGrantPlacer.resolveAsync(claimOf(kit), blueprints, materials))
                    .whenComplete((resolved, error) -> mainThread.execute(() -> {
                        if (error != null || resolved == null) {
                            LOGGER.log(Level.WARNING, "[Duel] Could not load kit " + kitId + " for " + player.getName(), error);
                            placed.complete(false);
                            return;
                        }
                        if (!player.isOnline() || generation.getOrDefault(player.getUniqueId(), 0L) != mine) {
                            placed.complete(false);
                            return;
                        }
                        for (KitGrantPlacer.ResolvedItem item : resolved) items.markLoan(item.itemStack(), token);
                        KitGrantPlacer.place(player, resolved);
                        player.updateInventory();
                        placed.complete(true);
                    }));
            return placed;
        }

        @Override
        public int removeLoans(Player player) {
            generation.remove(player.getUniqueId());
            return DuelKitLoans.removeTagged(player, items);
        }

        /** The kit's loadout as a claim result, without claiming it (no cost, cooldown or access check, O4). */
        static KnkKitClaimResult claimOf(KnkKit kit) {
            return new KnkKitClaimResult(kit.id(), kit.helmetId(), kit.chestplateId(), kit.leggingsId(), kit.bootsId(),
                    kit.shieldId(), kit.handId(), kit.contents() == null ? List.of() : kit.contents());
        }
    }

    /** Remove every stack {@code items} calls a loan from the whole player inventory and the cursor. */
    static int removeTagged(Player player, DuelItems items) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        int removed = 0;
        for (int slot = 0; slot < contents.length; slot++) {
            if (contents[slot] != null && items.isLoan(contents[slot])) {
                inventory.setItem(slot, null);
                removed++;
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && items.isLoan(cursor)) {
            player.setItemOnCursor(null);
            removed++;
        }
        return removed;
    }
}
