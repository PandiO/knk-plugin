package net.knightsandkings.knk.paper.kit;

import net.knightsandkings.knk.core.domain.item.KnkKitClaimResult;
import net.knightsandkings.knk.core.domain.item.KnkKitDelivery;
import net.knightsandkings.knk.core.ports.api.KitsCommandApi;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hands over kits a staff member granted from outside the game server ("Grant" on the web app's player profile,
 * PandiO/knk-workspace#261). The API writes the claim as a pending delivery; nothing placed its items before, because
 * placement only ran in the plugin's own flows ({@link KitGrantFlow}: {@code /kit get|give|purchase}, the kits menu).
 * <p>
 * Asked for a player on their join, on a {@code KitDeliveryPending} notification (pushed over the realtime hub or
 * polled, so it works with {@code realtime.enabled} off) and after a siege gave their own inventory back. Per pending
 * delivery: resolve the loadout's items, take the delivery at the API ({@code markDeliveredAsync}: exactly one caller
 * wins, whichever server or path it is), and only then place them with {@link KitGrantPlacer}. A delivery taken by an
 * earlier call or another server is skipped, so a grant is handed over at most once; one that can't be resolved, or
 * whose player left before it was taken, stays pending for the next join.
 * <p>
 * Held back while the player is in a siege (hub or match): their inventory is the siege one then and is replaced
 * afterwards, so the items would be lost. The after-siege hook asks again. A delivery taken in the moment the player
 * entered a siege (the take was in flight) is kept here with its items and placed on the next call that isn't held
 * back (after the siege, or the next join); only a server restart in between loses it, and the log names the claim.
 * <p>
 * Threading: {@link #deliverPending} is called on the main thread; API calls and item resolution run on their
 * futures and every Bukkit interaction hops back through {@code mainThread}.
 */
public final class KitGrantDelivery {

    private static final Logger LOGGER = Logger.getLogger(KitGrantDelivery.class.getName());

    /** Resolves a loadout into placeable items (in production {@link KitGrantPlacer#resolveAsync}). */
    @FunctionalInterface
    public interface Resolver {
        CompletableFuture<List<KitGrantPlacer.ResolvedItem>> resolve(KnkKitClaimResult loadout);
    }

    private final Executor mainThread;
    private final KitsCommandApi kitsCommandApi;
    private final Function<Player, Integer> userIdOf;
    private final Predicate<UUID> heldBack;
    private final Resolver resolver;
    private final BiFunction<Player, List<KitGrantPlacer.ResolvedItem>, KitGrantPlacer.PlacementSummary> placer;

    // Players with a run in flight -> whether another was asked for meanwhile (main thread only).
    private final Map<UUID, Boolean> running = new HashMap<>();

    /** A delivery taken at the API whose items wait until the player is out of the siege (main thread only). */
    private record Taken(KnkKitDelivery delivery, List<KitGrantPlacer.ResolvedItem> items) {
    }

    private final Map<UUID, List<Taken>> heldItems = new HashMap<>();

    public KitGrantDelivery(
            Executor mainThread,
            KitsCommandApi kitsCommandApi,
            Function<Player, Integer> userIdOf,
            Predicate<UUID> heldBack,
            Resolver resolver,
            BiFunction<Player, List<KitGrantPlacer.ResolvedItem>, KitGrantPlacer.PlacementSummary> placer
    ) {
        this.mainThread = mainThread;
        this.kitsCommandApi = kitsCommandApi;
        this.userIdOf = userIdOf;
        this.heldBack = heldBack != null ? heldBack : id -> false;
        this.resolver = resolver;
        this.placer = placer != null ? placer : KitGrantPlacer::place;
    }

    /**
     * Main thread: fetches the player's pending kit deliveries and hands them over one by one. A call while a run for
     * the same player is in flight runs once more after it (a grant made in between isn't missed, and a delivery is
     * never worked on twice at once). Returns a future that completes on the main thread with how many kits were
     * handed over by this run (for tests and logs).
     */
    public CompletableFuture<Integer> deliverPending(Player player) {
        if (player == null || !player.isOnline() || heldBack.test(player.getUniqueId())) {
            return CompletableFuture.completedFuture(0);
        }
        Integer userId = userIdOf.apply(player);
        if (userId == null) {
            return CompletableFuture.completedFuture(0);
        }
        UUID id = player.getUniqueId();
        if (running.containsKey(id)) {
            running.put(id, true);
            return CompletableFuture.completedFuture(0);
        }
        running.put(id, false);

        int heldGiven = placeTaken(player);
        CompletableFuture<Integer> done = new CompletableFuture<>();
        kitsCommandApi.pendingDeliveriesAsync(userId).whenComplete((pending, ex) -> mainThread.execute(() -> {
            if (ex != null) {
                LOGGER.warning("Could not read pending kit deliveries for user " + userId + ": " + rootMessage(ex));
                finish(player, id, heldGiven, done);
                return;
            }
            Deque<KnkKitDelivery> queue = new ArrayDeque<>(pending == null ? List.of() : pending);
            next(player, userId, queue, heldGiven, done);
        }));
        return done;
    }

    /** Main thread: one delivery after the other, so their messages and placements stay in order. */
    private void next(Player player, int userId, Deque<KnkKitDelivery> queue, int given, CompletableFuture<Integer> done) {
        KnkKitDelivery delivery = queue.poll();
        UUID id = player.getUniqueId();
        if (delivery == null || !player.isOnline() || heldBack.test(id)) {
            finish(player, id, given, done);
            return;
        }
        CompletableFuture<List<KitGrantPlacer.ResolvedItem>> resolved;
        try {
            resolved = resolver.resolve(delivery.loadout());
        } catch (RuntimeException e) {
            resolved = CompletableFuture.failedFuture(e);
        }
        resolved.whenComplete((items, resolveEx) -> mainThread.execute(() -> {
            if (resolveEx != null || items == null) {
                // Left pending: a blueprint that can't be resolved now (cache, API) gets another chance on the next join.
                LOGGER.log(Level.WARNING, "Kit claim " + delivery.claimId() + " (\"" + delivery.kitName()
                        + "\"): could not resolve its items; left pending", resolveEx);
                next(player, userId, queue, given, done);
                return;
            }
            if (!player.isOnline() || heldBack.test(id)) {
                finish(player, id, given, done); // still pending: next join or after the siege
                return;
            }
            kitsCommandApi.markDeliveredAsync(userId, delivery.claimId()).whenComplete((taken, markEx) -> mainThread.execute(() -> {
                if (markEx != null) {
                    LOGGER.warning("Kit claim " + delivery.claimId() + ": could not take the delivery (" + rootMessage(markEx)
                            + "); left pending");
                    next(player, userId, queue, given, done);
                    return;
                }
                if (!Boolean.TRUE.equals(taken)) {
                    // Another server or an earlier call has it: never placed twice.
                    next(player, userId, queue, given, done);
                    return;
                }
                if (!player.isOnline()) {
                    // Taken in the moment the player left: the API counts it delivered, so say which one for staff.
                    LOGGER.warning("Kit claim " + delivery.claimId() + " (\"" + delivery.kitName() + "\") for user " + userId
                            + " was taken but its player logged out before the items were placed; grant it again if needed");
                    finish(player, id, given, done);
                    return;
                }
                if (heldBack.test(id)) {
                    // Entered a siege while the take was in flight: its inventory is replaced afterwards, so keep the
                    // items here and place them after the siege (the next call that isn't held back).
                    heldItems.computeIfAbsent(id, k -> new ArrayList<>()).add(new Taken(delivery, items));
                    LOGGER.info("Kit claim " + delivery.claimId() + " (\"" + delivery.kitName() + "\") taken as "
                            + player.getName() + " entered a siege; placed after the siege");
                    finish(player, id, given, done);
                    return;
                }
                int placedNow = place(player, delivery, items) ? given + 1 : given;
                next(player, userId, queue, placedNow, done);
            }));
        }));
    }

    /** Main thread: places what was taken while the player was held back; returns how many kits that were. */
    private int placeTaken(Player player) {
        List<Taken> waiting = heldItems.remove(player.getUniqueId());
        if (waiting == null) {
            return 0;
        }
        int placedNow = 0;
        for (Taken t : waiting) {
            if (place(player, t.delivery(), t.items())) {
                placedNow++;
            }
        }
        return placedNow;
    }

    /** Main thread: places a taken delivery's items and tells the player; false when placing failed (logged). */
    private boolean place(Player player, KnkKitDelivery delivery, List<KitGrantPlacer.ResolvedItem> items) {
        try {
            KitGrantPlacer.PlacementSummary summary = placer.apply(player, items);
            player.sendMessage(ChatColor.GREEN + "A staff member gave you the \"" + delivery.kitName() + "\" kit.");
            if (summary != null && summary.dropped() > 0) {
                player.sendMessage(ChatColor.GRAY + "Some of it didn't fit and was dropped at your feet.");
            }
            LOGGER.info("Kit claim " + delivery.claimId() + " (\"" + delivery.kitName() + "\") delivered to "
                    + player.getName());
            return true;
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Kit claim " + delivery.claimId() + ": placing the items failed after the"
                    + " delivery was taken; grant it again if needed", e);
            return false;
        }
    }

    private void finish(Player player, UUID id, int given, CompletableFuture<Integer> done) {
        boolean again = Boolean.TRUE.equals(running.remove(id));
        done.complete(given);
        if (again && player.isOnline()) {
            deliverPending(player);
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
