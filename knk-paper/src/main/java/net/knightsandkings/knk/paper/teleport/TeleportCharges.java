package net.knightsandkings.knk.paper.teleport;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.IntConsumer;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.dataaccess.TeleportPolicyDataAccess;
import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;
import net.knightsandkings.knk.core.domain.teleport.KnkTeleportPolicy;
import net.knightsandkings.knk.core.domain.teleport.TeleportChargeResult;
import net.knightsandkings.knk.core.domain.teleport.TeleportPayment;
import net.knightsandkings.knk.core.teleport.BackKind;
import net.knightsandkings.knk.core.teleport.TeleportCharger;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportKind;

/**
 * Builds the {@link TeleportCharge}s of paid teleports (docs/specs/teleport/DESIGN.md §3.5, §3.7,
 * Phase 5): a warp's price, a {@code /tpa}'s fee, a {@code /spawn}'s fee (Linear KNG-41) and a
 * {@code /back}'s coin fee (KNG-42). All go to knk-web-api's charge routes through
 * {@link TeleportCharger} with a fresh idempotency key per teleport attempt, so a timed-out charge
 * is retried without charging twice and refunded when the teleport doesn't happen. The server
 * decides whether the player may go and what it costs - including the player's permission-group
 * prices (KNG-41), which may combine coins, gems and XP; the plugin only passes the bypass nodes it
 * resolved.
 * <p>
 * A {@code /tpa} or {@code /spawn} only calls the server when it costs something: the player's
 * cached group settings ({@link TeleportPolicyDataAccess}, at most a few seconds old) or the
 * configured default say so. Without an answer about the groups, the default applies - so a free
 * {@code /spawn} keeps working while the API is down.
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
    /** The players' permission-group teleport settings (KNG-41); null = defaults only. */
    private volatile TeleportPolicyDataAccess policies;

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

    public void setPolicies(TeleportPolicyDataAccess policies) {
        this.policies = policies;
    }


    /**
     * The charge of {@code /warp <destination>} for {@code player}: the server re-checks title,
     * premium tier and discovery and takes the gem price.
     */
    public TeleportCharge warp(Player player, KnkTeleportDestination destination, boolean bypassRequirements,
                               boolean bypassCost) {
        UUID payer = player.getUniqueId();
        String key = TeleportCharger.newKey("warp");
        return new Charge(payer, key, userId ->
            charger.chargeWarp(destination.domainId(), userId, key, bypassRequirements, bypassCost)) {
            @Override
            public CompletableFuture<Optional<PriceNotice>> priceNotice() {
                // The player's own price from their (at most a few seconds old) destination list.
                return CompletableFuture.completedFuture(bypassCost || !destination.hasPrice()
                    ? Optional.empty() : Optional.of(new PriceNotice(player, destination.priceLabel())));
            }

            @Override
            Location destinationOf(TeleportChargeResult result) {
                return toLocation(result.destination() != null ? result.destination() : destination, worlds);
            }
        };
    }

    /**
     * The fee of an accepted {@code /tpa} or {@code /tpahere}, paid by the requester: their permission
     * group's price (KNG-41), else {@code coins} ({@code teleport.request.price-coins}). Nothing is
     * asked of the server when neither makes it cost anything.
     */
    public TeleportCharge requestFee(Player requester, Player other, int coins) {
        UUID payer = requester.getUniqueId();
        UUID otherId = other.getUniqueId();
        String requesterName = requester.getName();
        return new PolicyCharge(requester, TeleportKind.REQUEST, coins, settings -> {
            String key = TeleportCharger.newKey("tpa");
            String price = settings.priceLabel(coins);
            return new Charge(payer, key, userId -> idOf(otherId).thenCompose(otherUserId ->
                charger.chargeRequestFee(userId, coins, key, otherUserId))) {
                @Override
                TeleportDenial denialOf(TeleportChargeResult result) {
                    if (isInsufficient(result)) {
                        return TeleportDenial.of("fee", requesterName + " doesn't have the " + price
                            + " this teleport request costs.");
                    }
                    return super.denialOf(result);
                }
            };
        });
    }

    /**
     * The fee of {@code player}'s own {@code /spawn} (Linear KNG-41): only their permission groups
     * price it, so nothing is asked of the server unless one does.
     */
    public TeleportCharge spawnFee(Player player) {
        UUID payer = player.getUniqueId();
        return new PolicyCharge(player, TeleportKind.SPAWN, 0, settings -> {
            String key = TeleportCharger.newKey("spawn");
            return new Charge(payer, key, userId -> charger.chargeSpawnFee(userId, key)) { };
        });
    }

    /** The flat coin fee of {@code player}'s own {@code /back} (Linear KNG-42), for the ledger tagged with {@code kind}. */
    public TeleportCharge backFee(Player player, int coins, BackKind kind) {
        UUID payer = player.getUniqueId();
        String key = TeleportCharger.newKey("back");
        return new Charge(payer, key, userId -> charger.chargeBackFee(userId, coins, key, kind.configKey())) {
            @Override
            public CompletableFuture<Optional<PriceNotice>> priceNotice() {
                return CompletableFuture.completedFuture(Optional.of(new PriceNotice(player, TeleportPayment.amount(coins, "Coins"))));
            }

            @Override
            TeleportDenial denialOf(TeleportChargeResult result) {
                if ("InsufficientCoins".equals(result.refusalCode())) {
                    return TeleportDenial.of("fee", "You don't have the " + coins + " coins /back costs.");
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

    private static boolean isInsufficient(TeleportChargeResult result) {
        return result.refusalCode() != null && result.refusalCode().startsWith("Insufficient");
    }

    private CompletableFuture<KnkTeleportPolicy> policyOf(UUID player) {
        TeleportPolicyDataAccess current = policies;
        if (current == null) {
            return CompletableFuture.completedFuture(KnkTeleportPolicy.DEFAULT);
        }
        return current.getAsync(player, TeleportPolicyDataAccess.PLAYER_READ_MAX_AGE);
    }

    /**
     * A {@code /tpa} or {@code /spawn} charge that first looks at the payer's group settings (KNG-41):
     * when the teleport is free by them and the default, it is allowed without asking the server;
     * otherwise the real charge takes over.
     */
    private final class PolicyCharge implements TeleportCharge {
        private final Player payerPlayer;
        private final UUID payer;
        private final TeleportKind kind;
        private final int defaultCoins;
        private final Function<KnkTeleportPolicy.Kind, TeleportCharge> paidCharge;
        private TeleportCharge inner;
        private boolean abandoned;

        PolicyCharge(Player payer, TeleportKind kind, int defaultCoins, Function<KnkTeleportPolicy.Kind, TeleportCharge> paidCharge) {
            this.payerPlayer = payer;
            this.payer = payer.getUniqueId();
            this.kind = kind;
            this.defaultCoins = defaultCoins;
            this.paidCharge = paidCharge;
        }

        @Override
        public CompletableFuture<Authorization> authorize() {
            return policyOf(payer).thenCompose(policy -> {
                KnkTeleportPolicy.Kind settings = policy.of(kind);
                if (!settings.costsAnything(defaultCoins)) {
                    return CompletableFuture.completedFuture(Authorization.allowed(() -> null));
                }
                TeleportCharge charge;
                synchronized (this) {
                    if (abandoned) {
                        return CompletableFuture.completedFuture(Authorization.denied(TeleportDenial.of(
                            TeleportCharger.UNAVAILABLE, "Teleporting isn't available right now. Try again.")));
                    }
                    charge = paidCharge.apply(settings);
                    inner = charge;
                }
                return charge.authorize();
            }).exceptionally(ex -> Authorization.denied(TeleportDenial.of(TeleportCharger.UNAVAILABLE,
                "Teleporting isn't available right now. Try again.")));
        }

        @Override
        public CompletableFuture<Optional<PriceNotice>> priceNotice() {
            return policyOf(payer).thenApply(policy -> {
                KnkTeleportPolicy.Kind settings = policy.of(kind);
                return settings.costsAnything(defaultCoins)
                    ? Optional.of(new PriceNotice(payerPlayer, settings.priceLabel(defaultCoins)))
                    : Optional.<PriceNotice>empty();
            }).exceptionally(ex -> Optional.empty());
        }

        private synchronized TeleportCharge inner() {
            return inner;
        }

        @Override
        public void refund(String why) {
            TeleportCharge charge = inner();
            if (charge != null) {
                charge.refund(why);
            }
        }

        @Override
        public CompletableFuture<Void> abandon(String why) {
            TeleportCharge charge;
            synchronized (this) {
                abandoned = true;
                charge = inner;
            }
            return charge != null ? charge.abandon(why) : CompletableFuture.completedFuture(null);
        }

        @Override
        public void completed(Player subject) {
            TeleportCharge charge = inner();
            if (charge != null) {
                charge.completed(subject);
            }
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
        private final ChargeCall call;
        private volatile Integer userId;
        private volatile TeleportChargeResult paid;
        private volatile boolean refunded;
        private boolean abandoned;

        Charge(UUID payer, String key, ChargeCall call) {
            this.payer = payer;
            this.key = key;
            this.call = call;
        }

        @Override
        public CompletableFuture<Authorization> authorize() {
            return idOf(payer).thenCompose(id -> {
                if (id == null) {
                    return CompletableFuture.completedFuture(Authorization.denied(TeleportDenial.of(NO_ACCOUNT,
                        "Your account couldn't be loaded - try again in a moment.")));
                }
                synchronized (this) {
                    if (abandoned) {
                        // Shutting down: never send a charge after abandon() - it couldn't be refunded.
                        return CompletableFuture.completedFuture(Authorization.denied(TeleportDenial.of(
                            TeleportCharger.UNAVAILABLE, "Teleporting isn't available right now. Try again.")));
                    }
                    userId = id;
                }
                return call.charge(id).thenApply(result -> {
                    if (!result.allowed()) {
                        return Authorization.denied(denialOf(result));
                    }
                    paid = result;
                    if (result.paid()) {
                        onCharged.accept(id);
                    }
                    // Turned into a Bukkit Location on the main thread (Authorization.destination()).
                    return Authorization.allowed(() -> destinationOf(result));
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
            if (refunded || result == null || id == null || !result.paid()) {
                return;
            }
            refunded = true;
            charger.refund(id, key, why).thenAccept(refund -> {
                if (refund != null && refund.refunded()) {
                    onCharged.accept(id);
                }
            });
        }

        /**
         * Plugin shutdown: refund the key whether or not the answer is in yet - the server reverses a
         * charge that went through, or voids the key so one still on its way is refused (both under
         * the player's row lock, so either order is safe) - and never send the charge afterwards.
         */
        @Override
        public CompletableFuture<Void> abandon(String why) {
            Integer id;
            synchronized (this) {
                abandoned = true;
                TeleportChargeResult result = paid;
                if (refunded || userId == null || (result != null && !result.paid())) {
                    // Refunded already, the charge was never sent (and now never will be), or it was free.
                    return CompletableFuture.completedFuture(null);
                }
                refunded = true;
                id = userId;
            }
            return charger.refund(id, key, why).thenAccept(refund -> {
                if (refund != null && refund.refunded()) {
                    onCharged.accept(id);
                }
            });
        }

        @Override
        public void completed(Player subject) {
            TeleportChargeResult result = paid;
            if (result == null || !result.paid() || result.payments().isEmpty()) {
                return;
            }
            Player payerPlayer = subject.getUniqueId().equals(payer) ? subject : onlineById.apply(payer);
            if (payerPlayer != null && payerPlayer.isOnline()) {
                // v1 wording.
                // v1 wording for one currency; a combined group price (KNG-41) lists each balance.
                List<TeleportPayment> payments = result.payments();
                String balance = payments.size() == 1
                    ? " and your new balance is " + payments.get(0).newBalance()
                    : "; your new balance is " + TeleportPayment.describeBalances(payments);
                payerPlayer.sendMessage(ChatColor.GOLD + "You paid " + TeleportPayment.describeAmounts(payments) + balance + ".");
            }
        }
    }
}
