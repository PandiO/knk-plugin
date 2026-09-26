package net.knightsandkings.knk.paper.listeners;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.lootbox.ClaimGuard;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxSpawn;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import net.knightsandkings.knk.paper.lootbox.LootboxAnnouncer;
import net.knightsandkings.knk.paper.lootbox.LootboxDelivery;
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

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Opening a box (docs/specs/lootboxes/DESIGN.md §3.4): a right- or left-click on a box's hitbox. In order: the
 * {@code knk.lootbox.open} node, not in staff/owner mode (unless {@code staff-mode-can-claim}), within
 * {@code claim-max-distance}, a loaded account, a free slot ({@code full-inventory: refuse}; no API call without one)
 * and the {@link ClaimGuard}. Then the claim goes to the API asynchronously and the item is delivered on the main
 * thread. The API decides everything that matters (who wins, what it is, the daily cap).
 */
public final class LootboxInteractListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(LootboxInteractListener.class.getName());

    /** Why a click did or didn't start a claim (for tests). */
    public enum Attempt { NOT_A_BOX, ORPHAN, NO_PERMISSION, STAFF_MODE, TOO_FAR, NO_ACCOUNT, INVENTORY_FULL, IN_FLIGHT, CLAIMING }

    private final LootboxRuntime runtime;
    private final ClaimGuard guard;
    private final LootboxesCommandApi commandApi;
    private final LootboxDelivery delivery;
    private final LootboxAnnouncer announcer;
    private final BiPredicate<Player, String> permission;
    private final Function<Player, ActiveMode> modeOf;
    private final Function<Player, Integer> userIdOf;
    private final Executor mainThread;

    public LootboxInteractListener(
            LootboxRuntime runtime,
            ClaimGuard guard,
            LootboxesCommandApi commandApi,
            LootboxDelivery delivery,
            LootboxAnnouncer announcer,
            BiPredicate<Player, String> permission,
            Function<Player, ActiveMode> modeOf,
            Function<Player, Integer> userIdOf,
            Executor mainThread
    ) {
        this.runtime = runtime;
        this.guard = guard;
        this.commandApi = commandApi;
        this.delivery = delivery;
        this.announcer = announcer;
        this.permission = permission;
        this.modeOf = modeOf;
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

    /** Main thread. Runs the checks and, when they pass, starts the claim. */
    public Attempt attemptOpen(Player player, Entity clicked) {
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

        if (!permission.test(player, "knk.lootbox.open")) {
            player.sendMessage(LootboxMessages.NO_PERMISSION);
            return Attempt.NO_PERMISSION;
        }
        ActiveMode mode = modeOf.apply(player);
        if (!settings.staffModeCanClaim() && mode != null && mode != ActiveMode.NONE) {
            player.sendMessage(LootboxMessages.STAFF_MODE);
            return Attempt.STAFF_MODE;
        }
        if (!withinReach(player, spawn, settings.claimMaxDistance())) {
            player.sendMessage(LootboxMessages.TOO_FAR);
            return Attempt.TOO_FAR;
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

        commandApi.claim(spawn.id(), spawn.token(), userId, ClaimGuard.idempotencyKey(spawn.token(), userId))
                .whenComplete((claim, ex) -> mainThread.execute(() -> {
                    guard.release(spawn.id(), playerId);
                    if (ex != null) {
                        onRejected(player, spawn, ex);
                    } else {
                        onClaimed(player, spawn, claim);
                    }
                }));
        return Attempt.CLAIMING;
    }

    private void onClaimed(Player player, KnkLootboxSpawn spawn, KnkLootboxClaimResult claim) {
        runtime.gone(spawn.id());
        if (claim == null || claim.isDelivered()) {
            return; // A replay of a claim that already reached the player.
        }
        delivery.deliver(player, claim, false).thenAccept(outcome -> {
            if (outcome.given() && player.isOnline()) {
                announcer.opened(player, claim, outcome.item(), runtime.settings(), runtime.config());
            } else if (!outcome.given() && player.isOnline()) {
                player.sendMessage(LootboxMessages.STUCK);
            }
        });
    }

    private void onRejected(Player player, KnkLootboxSpawn spawn, Throwable ex) {
        LootboxRejectedException rejected = LootboxRejectedException.find(ex);
        if (rejected == null) {
            LOGGER.log(Level.WARNING, "Lootbox " + spawn.id() + " claim failed: " + LootboxRejectedException.unwrap(ex).getMessage());
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
