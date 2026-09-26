package net.knightsandkings.knk.paper.teleport;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.IntConsumer;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;
import net.knightsandkings.knk.core.domain.teleport.TeleportChargeResult;
import net.knightsandkings.knk.core.teleport.TeleportCharger;
import net.knightsandkings.knk.core.teleport.TeleportDenial;

/**
 * Builds the {@link TeleportCharge}s of paid teleports (docs/specs/teleport/DESIGN.md §3.5, §3.7,
 * Phase 5): a warp's gem price and a {@code /tpa}'s coin fee. Both go to knk-web-api's charge
 * routes through {@link TeleportCharger} with a fresh idempotency key per teleport attempt, so a
 * timed-out charge is retried without charging twice and refunded when the teleport doesn't happen.
 * The server decides whether the player may go and what it costs; the plugin only passes the
 * bypass nodes it resolved.
 */
public class TeleportCharges {

    /** The code of a refusal because the payer has no knk account (or it couldn't be looked up). */
    static final String NO_ACCOUNT = "NoAccount";

    private final TeleportCharger charger;
    private final TeleportAuditor.UserIdLookup userIds;
    private final Function<String, World> worlds;
    private final Function<UUID, Player> onlineById;
    /** Called with the payer's user id after a charge changed their balance (drops their cached warp list). */
    private volatile IntConsumer onCharged = userId -> { };

    public TeleportCharges(TeleportCharger charger, TeleportAuditor.UserIdLookup userIds, Function<String, World> worlds,
                           Function<UUID, Player> onlineById) {
        this.charger = Objects.requireNonNull(charger, "charger must not be null");
        this.userIds = Objects.requireNonNull(userIds, "userIds must not be null");
        this.worlds = Objects.requireNonNull(worlds, "worlds must not be null");
        this.onlineById = Objects.requireNonNull(onlineById, "onlineById must not be null");
    }

    public void setOnCharged(IntConsumer onCharged) {
        this.onCharged = onCharged != null ? onCharged : userId -> { };
    }

    /**
     * The charge of {@code /warp <destination>} for {@code player}: the server re-checks title,
     * premium tier and discovery and takes the gem price.
     */
    public TeleportCharge warp(Player player, KnkTeleportDestination destination, boolean bypassRequirements,
                               boolean bypassCost) {
        UUID payer = player.getUniqueId();
        String key = TeleportCharger.newKey("warp");
        return new Charge(payer, key, "gems", userId ->
            charger.chargeWarp(destination.domainId(), userId, key, bypassRequirements, bypassCost)) {
            @Override
            Location destinationOf(TeleportChargeResult result) {
                return toLocation(result.destination() != null ? result.destination() : destination, worlds);
            }
        };
    }

    /** The coin fee of an accepted {@code /tpa} or {@code /tpahere}, paid by the requester. */
    public TeleportCharge requestFee(Player requester, Player other, int coins) {
        UUID payer = requester.getUniqueId();
        UUID otherId = other.getUniqueId();
        String key = TeleportCharger.newKey("tpa");
        String requesterName = requester.getName();
        return new Charge(payer, key, "coins", userId -> idOf(otherId).thenCompose(otherUserId ->
            charger.chargeRequestFee(userId, coins, key, otherUserId))) {
            @Override
            TeleportDenial denialOf(TeleportChargeResult result) {
                if ("InsufficientCoins".equals(result.refusalCode())) {
                    return TeleportDenial.of("fee", requesterName + " doesn't have the " + coins
                        + " coins this teleport request costs.");
                }
                return super.denialOf(result);
            }
        };
    }

    /** A destination's coordinates as a Bukkit Location; null when its world isn't loaded. */
    public static Location toLocation(KnkTeleportDestination destination, Function<String, World> worlds) {
        World world = destination.world() != null ? worlds.apply(destination.world()) : null;
        if (world == null) {
            return null;
        }
        return new Location(world, destination.x(), destination.y(), destination.z(), destination.yaw(), destination.pitch());
    }

    private CompletableFuture<Integer> idOf(UUID player) {
        try {
            CompletableFuture<Integer> lookup = userIds.idOf(player);
            return lookup != null ? lookup.exceptionally(ex -> null) : CompletableFuture.completedFuture(null);
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(null);
        }
    }

    @FunctionalInterface
    private interface ChargeCall {
        CompletableFuture<TeleportChargeResult> charge(int userId);
    }

    /** One charge: authorize once, remember what was taken, refund at most once. */
    private abstract class Charge implements TeleportCharge {
        private final UUID payer;
        private final String key;
        private final String currencyWord;
        private final ChargeCall call;
        private volatile Integer userId;
        private volatile TeleportChargeResult paid;
        private volatile boolean refunded;

        Charge(UUID payer, String key, String currencyWord, ChargeCall call) {
            this.payer = payer;
            this.key = key;
            this.currencyWord = currencyWord;
            this.call = call;
        }

        @Override
        public CompletableFuture<Authorization> authorize() {
            return idOf(payer).thenCompose(id -> {
                if (id == null) {
                    return CompletableFuture.completedFuture(Authorization.denied(TeleportDenial.of(NO_ACCOUNT,
                        "Your account couldn't be loaded - try again in a moment.")));
                }
                userId = id;
                return call.charge(id).thenApply(result -> {
                    if (!result.allowed()) {
                        return Authorization.denied(denialOf(result));
                    }
                    paid = result;
                    if (result.charged() > 0) {
                        onCharged.accept(id);
                    }
                    return Authorization.allowed(destinationOf(result));
                });
            }).exceptionally(ex -> Authorization.denied(TeleportDenial.of(TeleportCharger.UNAVAILABLE,
                "Teleporting isn't available right now. Try again.")));
        }

        TeleportDenial denialOf(TeleportChargeResult result) {
            return TeleportDenial.of(result.refusalCode(), result.refusalMessage());
        }

        Location destinationOf(TeleportChargeResult result) {
            return null;
        }

        @Override
        public synchronized void refund(String why) {
            TeleportChargeResult result = paid;
            Integer id = userId;
            if (refunded || result == null || id == null || result.charged() <= 0) {
                return;
            }
            refunded = true;
            charger.refund(id, key, why).thenAccept(refund -> {
                if (refund != null && refund.refunded()) {
                    onCharged.accept(id);
                }
            });
        }

        @Override
        public void completed(Player subject) {
            TeleportChargeResult result = paid;
            if (result == null || result.charged() <= 0) {
                return;
            }
            Player payerPlayer = subject.getUniqueId().equals(payer) ? subject : onlineById.apply(payer);
            if (payerPlayer != null && payerPlayer.isOnline()) {
                // v1 wording.
                payerPlayer.sendMessage(ChatColor.GOLD + "You paid " + result.charged() + " " + currencyWord
                    + " and your new balance is " + result.newBalance() + ".");
            }
        }
    }
}
