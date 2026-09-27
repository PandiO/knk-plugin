package net.knightsandkings.knk.paper.listeners;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.lootbox.ClaimGuard;
import net.knightsandkings.knk.core.lootbox.KnkLootboxPickup;
import net.knightsandkings.knk.core.lootbox.KnkLootboxSpawn;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import net.knightsandkings.knk.paper.lootbox.LootboxAnnouncer;
import net.knightsandkings.knk.paper.lootbox.LootboxDelivery;
import net.knightsandkings.knk.paper.lootbox.LootboxTokenDelivery;
import net.knightsandkings.knk.paper.lootbox.LootboxMessages;
import net.knightsandkings.knk.paper.lootbox.LootboxPresenter;
import net.knightsandkings.knk.paper.lootbox.LootboxRuntime;
import net.knightsandkings.knk.paper.lootbox.LootboxSettings;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Picking up a box (docs/specs/lootboxes/DESIGN.md §3.4, §3.8): a right- or left-click on a box's hitbox. In order: the
 * {@code knk.lootbox.open} node, not in staff/owner mode (unless {@code staff-mode-can-claim}), not in an active siege, within
 * {@code claim-max-distance} and in line of sight (no clicks through walls), a loaded account, a free slot for the token
 * item ({@code full-inventory: refuse}; no API call without one) and the {@link ClaimGuard}. Then the pickup goes to the
 * API asynchronously and the token item is handed over on the main thread; the player opens it later like any token
 * (the reel, {@code LootboxOpening}). The API decides everything that matters (who wins, the daily cap).
 * <p>
 * The permission check reads the permission cache; when nothing is cached yet (just after joining) it asks the API
 * before refusing, so a first click isn't wrongly denied.
 */
public final class LootboxInteractListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(LootboxInteractListener.class.getName());
    static final String OPEN_NODE = "knk.lootbox.open";

    /** Why a click did or didn't start a claim (for tests). */
    public enum Attempt { NOT_A_BOX, ORPHAN, NO_PERMISSION, CHECKING_PERMISSION, STAFF_MODE, IN_SIEGE, TOO_FAR, NO_LINE_OF_SIGHT, NO_ACCOUNT, INVENTORY_FULL, IN_FLIGHT, CLAIMING }

    private final LootboxRuntime runtime;
    private final ClaimGuard guard;
    private final LootboxesCommandApi commandApi;
    private final LootboxTokenDelivery tokens;
    private final LootboxAnnouncer announcer;
    private final BiPredicate<Player, String> permission;
    // Asked when the cached check says no (nothing cached yet); null = trust the cache.
    private final BiFunction<Player, String, CompletableFuture<Boolean>> freshPermission;
    private final Function<Player, ActiveMode> modeOf;
    private final Predicate<UUID> inSiege;
    private final Function<Player, Integer> userIdOf;
    private final Executor mainThread;

    public LootboxInteractListener(
            LootboxRuntime runtime,
            ClaimGuard guard,
            LootboxesCommandApi commandApi,
            LootboxTokenDelivery tokens,
            LootboxAnnouncer announcer,
            BiPredicate<Player, String> permission,
            BiFunction<Player, String, CompletableFuture<Boolean>> freshPermission,
            Function<Player, ActiveMode> modeOf,
            Predicate<UUID> inSiege,
            Function<Player, Integer> userIdOf,
            Executor mainThread
    ) {
        this.runtime = runtime;
        this.guard = guard;
        this.commandApi = commandApi;
        this.tokens = tokens;
        this.announcer = announcer;
        this.permission = permission;
        this.freshPermission = freshPermission;
        this.modeOf = modeOf;
        this.inSiege = inSiege;
        this.userIdOf = userIdOf;
        this.mainThread = mainThread;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (LootboxPresenter.tokenOf(event.getRightClicked()).isEmpty()) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // The off-hand copy of the same click.
        }
        attemptOpen(event.getPlayer(), event.getRightClicked());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (LootboxPresenter.tokenOf(event.getAttacked()).isEmpty()) {
            return;
        }
        event.setCancelled(true);
        attemptOpen(event.getPlayer(), event.getAttacked());
    }

    /** Main thread. Runs the checks and, when they pass, starts the pickup. */
    public Attempt attemptOpen(Player player, Entity clicked) {
        return attemptOpen(player, clicked, false);
    }

    private Attempt attemptOpen(Player player, Entity clicked, boolean permissionChecked) {
        Optional<UUID> token = LootboxPresenter.tokenOf(clicked);
        if (token.isEmpty()) {
            return Attempt.NOT_A_BOX;
        }
        Optional<KnkLootboxSpawn> found = runtime.cache().byToken(token.get());
        if (found.isEmpty()) {
            clicked.remove(); // Not an active box (claimed elsewhere, expired): an orphan entity.
            return Attempt.ORPHAN;
        }
        KnkLootboxSpawn spawn = found.get();
        LootboxSettings settings = runtime.settings();

        if (!permissionChecked && !permission.test(player, OPEN_NODE)) {
            if (freshPermission == null) {
                player.sendMessage(LootboxMessages.NO_PERMISSION);
                return Attempt.NO_PERMISSION;
            }
            freshPermission.apply(player, OPEN_NODE).whenComplete((allowed, ex) -> mainThread.execute(() -> {
                if (!player.isOnline()) {
                    return;
                }
                if (ex == null && Boolean.TRUE.equals(allowed)) {
                    attemptOpen(player, clicked, true);
                } else {
                    player.sendMessage(LootboxMessages.NO_PERMISSION);
                }
            }));
            return Attempt.CHECKING_PERMISSION;
        }
        ActiveMode mode = modeOf.apply(player);
        if (!settings.staffModeCanClaim() && mode != null && mode != ActiveMode.NONE) {
            player.sendMessage(LootboxMessages.STAFF_MODE);
            return Attempt.STAFF_MODE;
        }
        // In a siege (hub or match) the inventory is the siege one and is replaced afterwards: the token would be lost.
        if (inSiege.test(player.getUniqueId())) {
            player.sendMessage(LootboxMessages.IN_SIEGE);
            return Attempt.IN_SIEGE;
        }
        if (!withinReach(player, spawn, settings.claimMaxDistance())) {
            player.sendMessage(LootboxMessages.TOO_FAR);
            return Attempt.TOO_FAR;
        }
        // The server checks an entity click's distance only; a modified client can click through a wall.
        if (!player.hasLineOfSight(clicked)) {
            player.sendMessage(LootboxMessages.NO_LINE_OF_SIGHT);
            return Attempt.NO_LINE_OF_SIGHT;
        }
        Integer userId = userIdOf.apply(player);
        if (userId == null) {
            player.sendMessage(LootboxMessages.NO_ACCOUNT);
            return Attempt.NO_ACCOUNT;
        }
        if (settings.refuseWhenFull() && !LootboxDelivery.hasRoom(player)) {
            player.sendMessage(LootboxMessages.INVENTORY_FULL);
            return Attempt.INVENTORY_FULL;
        }
        UUID playerId = player.getUniqueId();
        if (!guard.tryAcquire(spawn.id(), playerId)) {
            return Attempt.IN_FLIGHT;
        }

        commandApi.pickup(spawn.id(), spawn.token(), userId)
                .whenComplete((pickup, ex) -> mainThread.execute(() -> {
                    guard.release(spawn.id(), playerId);
                    if (ex != null) {
                        onRejected(player, spawn, ex);
                    } else {
                        onPickedUp(player, spawn, userId, pickup);
                    }
                }));
        return Attempt.CLAIMING;
    }

    private void onPickedUp(Player player, KnkLootboxSpawn spawn, int userId, KnkLootboxPickup pickup) {
        runtime.gone(spawn.id());
        if (pickup == null || pickup.token() == null) {
            player.sendMessage(LootboxMessages.STUCK);
            return;
        }
        if (!player.isOnline()) {
            return; // an undelivered token: handed over on their next join
        }
        // give() skips a token already in the inventory or ender chest (a retried click) and confirms it either way.
        tokens.give(player, userId, List.of(pickup.token()));
        announcer.pickedUp(player, pickup.token().boxLabel(), pickup.token().boxStars(), runtime.settings());
    }

    private void onRejected(Player player, KnkLootboxSpawn spawn, Throwable ex) {
        LootboxRejectedException rejected = LootboxRejectedException.find(ex);
        if (rejected == null) {
            LOGGER.log(Level.WARNING, "Lootbox " + spawn.id() + " pickup failed: " + LootboxRejectedException.unwrap(ex).getMessage());
            player.sendMessage(LootboxMessages.STUCK);
            return;
        }
        if (LootboxMessages.boxIsGone(rejected)) {
            runtime.gone(spawn.id());
        }
        player.sendMessage(LootboxMessages.rejection(rejected, LootboxMessages.categoryOf(runtime.config(), spawn.lootboxTypeId())));
    }

    static boolean withinReach(Player player, KnkLootboxSpawn spawn, double maxDistance) {
        Location at = player.getLocation();
        if (at.getWorld() == null || !at.getWorld().getName().equals(spawn.world())) {
            return false;
        }
        double dx = at.getX() - (spawn.x() + 0.5);
        double dy = at.getY() - spawn.y();
        double dz = at.getZ() - (spawn.z() + 0.5);
        return dx * dx + dy * dy + dz * dz <= maxDistance * maxDistance;
    }
}
