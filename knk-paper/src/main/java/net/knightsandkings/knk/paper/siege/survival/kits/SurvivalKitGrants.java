package net.knightsandkings.knk.paper.siege.survival.kits;

import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.KitsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.item.KnkKit;
import net.knightsandkings.knk.core.domain.item.KnkKitClaimResult;
import net.knightsandkings.knk.paper.kit.KitGrantPlacer;
import net.knightsandkings.knk.paper.siege.survival.items.MatchItems;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Grants a scenario kit or the baseline loadout for a survival match (DESIGN §8; S23, S4-12): the kit's slots go through
 * the kits grant pipeline ({@link KitGrantPlacer#resolveAsync} → {@code BlueprintItemAssembler.assembleDefaults} →
 * {@link KitGrantPlacer#place}) and nothing else of an ordinary claim — no permission, title-bracket or premium
 * condition, no cooldown, no cost, no {@code KitClaim}/{@code KitPurchase} row. The (already snapshotted) inventory is
 * cleared first; every placed item is a match item (§8b). A missing kit clears the inventory and is logged (flag S7b).
 */
public final class SurvivalKitGrants {

    /** The outcome of one grant, for the log and the status. */
    public record Outcome(int kitId, boolean kitFound, int placed, int dropped) {
        public static final Outcome EMPTY = new Outcome(-1, true, 0, 0);
    }

    private final KitsDataAccess kits;
    private final ItemBlueprintsDataAccess blueprints;
    private final MinecraftMaterialRefsDataAccess materials;
    private final MatchItems items;
    private final Executor mainThread;
    private final Logger logger;

    public SurvivalKitGrants(KitsDataAccess kits, ItemBlueprintsDataAccess blueprints, MinecraftMaterialRefsDataAccess materials,
                             MatchItems items, Executor mainThread, Logger logger) {
        this.kits = kits;
        this.blueprints = Objects.requireNonNull(blueprints, "blueprints");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.items = Objects.requireNonNull(items, "items");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.logger = logger;
    }

    /** Clears the inventory and grants nothing (match-progression without a baseline, or a missing kit). */
    public void clear(Player player) {
        player.getInventory().clear();
        player.getInventory().setItemInOffHand(null);
        player.setItemOnCursor(null);
    }

    /**
     * Clears the inventory, then grants the kit as match items of {@code matchKey}. Completes on the main thread. An empty
     * kit id clears only.
     */
    public CompletableFuture<Outcome> grant(Player player, OptionalInt kitId, String matchKey) {
        CompletableFuture<Outcome> done = new CompletableFuture<>();
        clear(player);
        if (kitId.isEmpty()) {
            done.complete(Outcome.EMPTY);
            return done;
        }
        int id = kitId.getAsInt();
        if (kits == null) {
            warn("kit " + id + " cannot be granted to " + player.getName() + ": the kit data access is not available; empty inventory");
            done.complete(new Outcome(id, false, 0, 0));
            return done;
        }
        kits.getByIdAsync(id)
            .thenCompose(result -> {
                KnkKit kit = result == null ? null : result.value().orElse(null);
                if (kit == null) return CompletableFuture.completedFuture((List<KitGrantPlacer.ResolvedItem>) null);
                return KitGrantPlacer.resolveAsync(claimOf(kit), blueprints, materials);
            })
            .whenComplete((resolved, ex) -> mainThread.execute(() -> {
                try {
                    if (ex != null) {
                        if (logger != null) logger.log(Level.WARNING, "[Survival] Kit " + id + " could not be resolved for " + player.getName() + "; empty inventory", ex);
                        done.complete(new Outcome(id, false, 0, 0));
                        return;
                    }
                    if (resolved == null) {
                        warn("kit " + id + " not found for " + player.getName() + " (deleted after the configuration was frozen?); empty inventory");
                        done.complete(new Outcome(id, false, 0, 0));
                        return;
                    }
                    if (!player.isOnline()) {
                        done.complete(new Outcome(id, true, 0, 0));
                        return;
                    }
                    for (KitGrantPlacer.ResolvedItem item : resolved) items.tag(item.itemStack(), matchKey);
                    clear(player); // whatever arrived meanwhile (nothing should) goes too
                    KitGrantPlacer.PlacementSummary summary = KitGrantPlacer.place(player, resolved);
                    done.complete(new Outcome(id, true, summary.placed() + summary.merged(), summary.dropped()));
                } catch (RuntimeException e) {
                    done.completeExceptionally(e);
                }
            }));
        return done;
    }

    /** The kit's slots as the placer expects them (the claim result of an ordinary claim, without the claim). */
    static KnkKitClaimResult claimOf(KnkKit kit) {
        return new KnkKitClaimResult(kit.id() == null ? 0 : kit.id(), kit.helmetId(), kit.chestplateId(), kit.leggingsId(),
            kit.bootsId(), kit.shieldId(), kit.handId(), kit.contents() == null ? List.of() : kit.contents());
    }

    private void warn(String message) {
        if (logger != null) logger.warning("[Survival] " + message);
    }
}
