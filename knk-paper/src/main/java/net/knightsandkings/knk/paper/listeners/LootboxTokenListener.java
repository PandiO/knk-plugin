package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import net.knightsandkings.knk.core.lootbox.TokenOpenGuard;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import net.knightsandkings.knk.paper.lootbox.LootboxAnnouncer;
import net.knightsandkings.knk.paper.lootbox.LootboxDelivery;
import net.knightsandkings.knk.paper.lootbox.LootboxMessages;
import net.knightsandkings.knk.paper.lootbox.LootboxRuntime;
import net.knightsandkings.knk.paper.lootbox.LootboxSettings;
import net.knightsandkings.knk.paper.lootbox.LootboxTokenDelivery;
import net.knightsandkings.knk.paper.mapper.LootboxTokenTag;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Opening a lootbox token item (docs/specs/lootboxes/IMPLEMENTATION_PLAN.md Phase 5): a right-click with an item
 * carrying {@code knightsandkings:knk_lootbox_token}. Nothing else makes an item a token (v1 matched the display name,
 * so an anvil rename made a free box). Same checks as a world box: {@code knk.lootbox.open}, not in staff/owner mode or a siege,
 * a loaded account, room for the item (the token's own slot counts when it is the last one), and one open in flight per
 * token and player. The API consumes the token in the claim transaction; only after its 200 is one copy taken from the
 * inventory and the item delivered. A token the API reports as opened or revoked is dead: every copy is removed.
 * <p>
 * Also keeps tokens out of block placement and crafting, and hands over undelivered tokens shortly after a join.
 */
public final class LootboxTokenListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(LootboxTokenListener.class.getName());
    private static final long JOIN_DELAY_TICKS = 80L; // after LootboxJoinListener's pending claims

    /** Why a click did or didn't start an open (for tests). */
    public enum Attempt { NOT_A_TOKEN, NO_PERMISSION, STAFF_MODE, IN_SIEGE, NO_ACCOUNT, INVENTORY_FULL, IN_FLIGHT, OPENING }

    private final Plugin plugin;
    private final LootboxRuntime runtime;
    private final TokenOpenGuard guard;
    private final LootboxesCommandApi commandApi;
    private final LootboxDelivery delivery;
    private final LootboxTokenDelivery tokens;
    private final LootboxAnnouncer announcer;
    private final BiPredicate<Player, String> permission;
    private final Function<Player, ActiveMode> modeOf;
    private final Predicate<UUID> inSiege;
    private final Function<Player, Integer> userIdOf;
    private final Executor mainThread;

    public LootboxTokenListener(
            Plugin plugin,
            LootboxRuntime runtime,
            TokenOpenGuard guard,
            LootboxesCommandApi commandApi,
            LootboxDelivery delivery,
            LootboxTokenDelivery tokens,
            LootboxAnnouncer announcer,
            BiPredicate<Player, String> permission,
            Function<Player, ActiveMode> modeOf,
            Predicate<UUID> inSiege,
            Function<Player, Integer> userIdOf,
            Executor mainThread
    ) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.guard = guard;
        this.commandApi = commandApi;
        this.delivery = delivery;
        this.tokens = tokens;
        this.announcer = announcer;
        this.permission = permission;
        this.modeOf = modeOf;
        this.inSiege = inSiege;
        this.userIdOf = userIdOf;
        this.mainThread = mainThread;
    }

    // Not ignoreCancelled: a right-click in the air arrives already cancelled.
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        Optional<UUID> token = LootboxTokenTag.read(item);
        if (token.isEmpty()) {
            return;
        }
        // Never place, eat or use the item itself.
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);
        attemptOpen(event.getPlayer(), token.get(), item);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (LootboxTokenTag.isToken(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (LootboxTokenTag.isToken(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                tokens.deliverUndelivered(player);
            }
        }, JOIN_DELAY_TICKS);
    }

    /** Main thread. Runs the checks and, when they pass, sends the redeem. */
    public Attempt attemptOpen(Player player, UUID token, ItemStack item) {
        if (token == null) {
            return Attempt.NOT_A_TOKEN;
        }
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
        // In a siege (hub or match) the inventory is the siege one and is replaced afterwards: the item would be lost.
        if (inSiege.test(player.getUniqueId())) {
            player.sendMessage(LootboxMessages.IN_SIEGE);
            return Attempt.IN_SIEGE;
        }
        Integer userId = userIdOf.apply(player);
        if (userId == null) {
            player.sendMessage(LootboxMessages.NO_ACCOUNT);
            return Attempt.NO_ACCOUNT;
        }
        // The token's own slot frees up when it is the last of its stack.
        boolean lastOfStack = item != null && item.getAmount() <= 1;
        if (settings.refuseWhenFull() && !lastOfStack && !LootboxDelivery.hasRoom(player)) {
            player.sendMessage(LootboxMessages.INVENTORY_FULL);
            return Attempt.INVENTORY_FULL;
        }
        UUID playerId = player.getUniqueId();
        if (!guard.tryAcquire(token, playerId)) {
            return Attempt.IN_FLIGHT;
        }

        commandApi.redeemToken(token, userId, TokenOpenGuard.idempotencyKey(token))
                .whenComplete((claim, ex) -> mainThread.execute(() -> {
                    guard.release(token, playerId);
                    if (ex != null) {
                        onRejected(player, token, ex);
                    } else {
                        onOpened(player, token, claim);
                    }
                }));
        return Attempt.OPENING;
    }

    private void onOpened(Player player, UUID token, KnkLootboxClaimResult claim) {
        // The API has consumed the token: take one copy away whatever happens next.
        if (!LootboxTokenDelivery.removeOne(player, token)) {
            LOGGER.info("Lootbox token " + token + " opened by " + player.getName() + " but no copy was left in their inventory");
        }
        if (claim == null || claim.isDelivered()) {
            return; // A replay of an open that already reached the player.
        }
        delivery.deliver(player, claim, false).thenAccept(outcome -> {
            if (outcome.given() && player.isOnline()) {
                announcer.opened(player, claim, outcome.item(), runtime.settings(), runtime.config());
            } else if (!outcome.given() && !outcome.alreadyHeld() && player.isOnline()) {
                player.sendMessage(LootboxMessages.STUCK);
            }
        });
    }

    private void onRejected(Player player, UUID token, Throwable ex) {
        LootboxRejectedException rejected = LootboxRejectedException.find(ex);
        if (rejected == null) {
            LOGGER.log(Level.WARNING, "Lootbox token " + token + " open failed: " + LootboxRejectedException.unwrap(ex).getMessage());
            player.sendMessage(LootboxMessages.STUCK);
            return;
        }
        if (LootboxMessages.tokenIsSpent(rejected)) {
            int removed = LootboxTokenDelivery.removeAll(player, token);
            LOGGER.info("Lootbox token " + token + " (" + rejected.code() + ") held by " + player.getName() + ": removed " + removed + " copies");
        } else if (rejected.is(LootboxRejectedException.INVALID_TOKEN)) {
            LOGGER.warning("Lootbox token " + token + " held by " + player.getName() + " was never issued by the API");
        }
        player.sendMessage(LootboxMessages.rejection(rejected, null));
    }
}
