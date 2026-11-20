package net.knightsandkings.knk.core.siege.shop;

import net.knightsandkings.knk.core.siege.economy.LedgerResult;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The siege shop of one match (siege shop DESIGN § 5.4, § 5.5, § 7.1.3, P06, P22, P23, P25;
 * PandiO/knk-workspace#868): the frozen {@link ShopCatalogue}, each player's purchase counters, and the purchase
 * order of § 5.5:
 * <ol>
 *   <li>match live and the buyer a living member ({@link Membership});</li>
 *   <li>the entry in the effective catalogue;</li>
 *   <li>the unlock of a weapon entry ({@link Unlocks}, W32; the title level is checked when unlocking, § 5.6);</li>
 *   <li>a handler for the entry's kind (weapons wait for #784);</li>
 *   <li>cap, then cooldown;</li>
 *   <li>room for the whole quantity or the weapon flow's own checks ({@link ShopPurchaseHandler#checkDelivery});</li>
 *   <li>balance ≥ current price;</li>
 * </ol>
 * then the handler: charge with retry id {@code shop:{matchId}:{player}:{entryId}:{n+1}}, then deliver. A refusal
 * charges nothing and names its reason; purchases are final (P23). Counters live with this object and are gone at
 * the match end (A22). Bukkit-free; main thread only.
 */
public final class ShopPurchaseService {

    /** The match's balances: the shared ledger (siege points in PvP, Survival's at its merge). */
    public interface Wallet {
        long balance(UUID player);

        /** An idempotent charge (the ledger's rules: member, no overdraft, retry id). */
        LedgerResult charge(String chargeId, UUID player, long price, String product);

        /** Gives a charged amount back once per {@code reversalId} (§ 5.5.2). */
        void reverse(String reversalId, UUID player, long amount);
    }

    /** Check 1: whether the player may buy now; empty when they are a living member of the live match. */
    @FunctionalInterface
    public interface Membership {
        Optional<ShopRefusal> refusal(UUID player);
    }

    /** Whether a player has unlocked a weapon entry's template (W32, P20); asked only for siege weapon entries. */
    @FunctionalInterface
    public interface Unlocks {
        boolean unlocked(UUID player, ShopEntry entry);

        /** Every weapon unlocked (tests, and modes without unlocks). */
        Unlocks ALL = (player, entry) -> true;
    }

    /** Told about every purchase that was charged (telemetry, P27). */
    @FunctionalInterface
    public interface Listener {
        void bought(UUID player, ShopPurchaseResult.Bought purchase);
    }

    /** What the shop shows for one player and entry right now. */
    public record EntryState(int purchases, long price, long cooldownMillisLeft, boolean capReached) {
    }

    private static final class Counter {
        int count;
        long lockedUntil;
        /** Purchase numbers whose charge was reversed, with how often: their next charge needs a fresh retry id. */
        final Map<Integer, Integer> reversals = new HashMap<>();
    }

    private final String matchId;
    private final ShopCatalogue catalogue;
    private final Wallet wallet;
    private final Membership membership;
    private Unlocks unlocks = Unlocks.ALL;
    private final Map<ShopEntryKind, ShopPurchaseHandler> handlers = new EnumMap<>(ShopEntryKind.class);
    private final LongSupplier clock;
    private final Listener listener;
    private final Map<UUID, Map<Integer, Counter>> counters = new HashMap<>();
    private boolean ended;

    public ShopPurchaseService(String matchId, ShopCatalogue catalogue, Wallet wallet, Membership membership,
                               LongSupplier clock, Listener listener) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.catalogue = catalogue == null ? ShopCatalogue.empty() : catalogue;
        this.wallet = Objects.requireNonNull(wallet, "wallet");
        this.membership = Objects.requireNonNull(membership, "membership");
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.listener = listener == null ? (p, b) -> { } : listener;
    }

    /** Registers (or replaces) the handler for its kind; a kind without one is refused as not available yet. */
    public ShopPurchaseService handler(ShopPurchaseHandler handler) {
        handlers.put(handler.kind(), handler);
        return this;
    }

    /** The weapon unlock check (default: everything unlocked). */
    public ShopPurchaseService unlocks(Unlocks check) {
        this.unlocks = check == null ? Unlocks.ALL : check;
        return this;
    }

    /**
     * Why the entry can't be bought whatever the brakes and balance say (for the shop lines): locked (A26) or no
     * handler for its kind yet; empty otherwise.
     */
    public Optional<ShopRefusal> blocked(UUID player, ShopEntry entry) {
        if (entry.kind() == ShopEntryKind.SIEGE_WEAPON && !unlocks.unlocked(player, entry)) return Optional.of(ShopRefusal.locked());
        if (!handlers.containsKey(entry.kind())) {
            return Optional.of(ShopRefusal.notAvailable(entry.kind() == ShopEntryKind.SIEGE_WEAPON ? "Siege weapons" : entry.name()));
        }
        return Optional.empty();
    }

    public boolean hasHandler(ShopEntryKind kind) {
        return handlers.containsKey(kind);
    }

    public Optional<ShopPurchaseHandler> handlerFor(ShopEntryKind kind) {
        return Optional.ofNullable(handlers.get(kind));
    }

    public String matchId() {
        return matchId;
    }

    public ShopCatalogue catalogue() {
        return catalogue;
    }

    public long balance(UUID player) {
        return wallet.balance(player);
    }

    /** The match ended: nothing more is sold (the ledger refuses too). */
    public void end() {
        ended = true;
    }

    public boolean ended() {
        return ended;
    }

    /** Purchases, current price, cooldown left and cap of one entry for one player (for the shop lines). */
    public EntryState state(UUID player, ShopEntry entry) {
        Counter c = counter(player, entry.id(), false);
        int count = c == null ? 0 : c.count;
        long left = c == null ? 0 : Math.max(0, c.lockedUntil - clock.getAsLong());
        return new EntryState(count, ShopBrakes.price(entry, count), left, ShopBrakes.capReached(entry, count));
    }

    /** Runs the § 5.5 checks and, when they all pass, hands the purchase to the kind's handler. */
    public ShopPurchaseResult purchase(UUID player, int entryId) {
        Optional<ShopRefusal> who = ended ? Optional.of(ShopRefusal.notInMatch()) : membership.refusal(player);
        if (who.isPresent()) return new ShopPurchaseResult.Refused(who.get());
        Optional<ShopEntry> found = catalogue.entry(entryId);
        if (found.isEmpty()) return new ShopPurchaseResult.Refused(ShopRefusal.unknownEntry());
        ShopEntry entry = found.get();
        Optional<ShopRefusal> blocked = blocked(player, entry);
        if (blocked.isPresent()) return new ShopPurchaseResult.Refused(blocked.get());
        ShopPurchaseHandler handler = handlers.get(entry.kind());
        Optional<ShopRefusal> brakes = brakes(player, entry);
        if (brakes.isPresent()) return new ShopPurchaseResult.Refused(brakes.get());
        Optional<ShopRefusal> delivery = handler.checkDelivery(player, entry);
        if (delivery.isPresent()) return new ShopPurchaseResult.Refused(delivery.get());
        long price = state(player, entry).price();
        long balance = wallet.balance(player);
        if (balance < price) return new ShopPurchaseResult.Refused(ShopRefusal.notEnoughPoints(balance, price));
        return handler.fulfil(new Ticket(player, entry));
    }

    /** Checks 4 and 5: cap, then cooldown. */
    private Optional<ShopRefusal> brakes(UUID player, ShopEntry entry) {
        EntryState s = state(player, entry);
        if (s.capReached()) return Optional.of(ShopRefusal.limitReached(entry.maxPerMatch()));
        if (s.cooldownMillisLeft() > 0) return Optional.of(ShopRefusal.cooldown(s.cooldownMillisLeft()));
        return Optional.empty();
    }

    private Counter counter(UUID player, int entryId, boolean create) {
        Map<Integer, Counter> perPlayer = create ? counters.computeIfAbsent(player, k -> new HashMap<>()) : counters.get(player);
        if (perPlayer == null) return null;
        return create ? perPlayer.computeIfAbsent(entryId, k -> new Counter()) : perPlayer.get(entryId);
    }

    static String chargeId(String matchId, UUID player, int entryId, int purchaseNumber) {
        return "shop:" + matchId + ":" + player + ":" + entryId + ":" + purchaseNumber;
    }

    private final class Ticket implements ShopPurchaseTicket {
        private final UUID buyer;
        private final ShopEntry entry;
        private ShopPurchaseResult result;
        private long previousLockedUntil;
        private boolean reversed;

        Ticket(UUID buyer, ShopEntry entry) {
            this.buyer = buyer;
            this.entry = entry;
        }

        @Override
        public UUID buyer() {
            return buyer;
        }

        @Override
        public ShopEntry entry() {
            return entry;
        }

        @Override
        public long price() {
            return state(buyer, entry).price();
        }

        @Override
        public ShopPurchaseResult charge() {
            if (result != null) return result;
            Optional<ShopRefusal> who = ended ? Optional.of(ShopRefusal.notInMatch()) : membership.refusal(buyer);
            if (who.isPresent()) return refuse(who.get());
            Optional<ShopRefusal> brakes = brakes(buyer, entry);
            if (brakes.isPresent()) return refuse(brakes.get());
            EntryState s = state(buyer, entry);
            int number = s.purchases() + 1;
            Counter existing = counter(buyer, entry.id(), false);
            int again = existing == null ? 0 : existing.reversals.getOrDefault(number, 0);
            String id = chargeId(matchId, buyer, entry.id(), number) + (again > 0 ? ":again" + again : "");
            LedgerResult ledger = s.price() <= 0
                    ? new LedgerResult.Accepted(id, wallet.balance(buyer), false)
                    : wallet.charge(id, buyer, s.price(), "shop:" + entry.id());
            if (ledger instanceof LedgerResult.Rejected rejected) {
                return refuse(refusalOf(rejected.reason(), wallet.balance(buyer), s.price()));
            }
            long after = ((LedgerResult.Accepted) ledger).balanceAfter();
            Counter c = counter(buyer, entry.id(), true);
            previousLockedUntil = c.lockedUntil;
            c.count = number;
            long cooldown = ShopBrakes.cooldownMillis(entry, number);
            c.lockedUntil = cooldown > 0 ? clock.getAsLong() + cooldown : 0;
            ShopPurchaseResult.Bought bought = new ShopPurchaseResult.Bought(entry, s.price(), number, after, id);
            result = bought;
            listener.bought(buyer, bought);
            return bought;
        }

        private ShopPurchaseResult refuse(ShopRefusal refusal) {
            result = new ShopPurchaseResult.Refused(refusal);
            return result;
        }

        @Override
        public void reverse() {
            if (reversed || !(result instanceof ShopPurchaseResult.Bought bought)) return;
            reversed = true;
            if (bought.price() > 0) wallet.reverse(bought.chargeId() + ":reversal", buyer, bought.price());
            Counter c = counter(buyer, entry.id(), false);
            if (c != null && c.count == bought.purchaseNumber()) {
                c.reversals.merge(bought.purchaseNumber(), 1, Integer::sum);
                c.count = bought.purchaseNumber() - 1;
                c.lockedUntil = previousLockedUntil;
            }
            result = new ShopPurchaseResult.Refused(ShopRefusal.deliveryFailed());
        }
    }

    static ShopRefusal refusalOf(LedgerResult.Reason reason, long balance, long price) {
        return switch (reason) {
            case INSUFFICIENT_BALANCE -> ShopRefusal.notEnoughPoints(balance, price);
            case PAYER_IS_GHOST -> ShopRefusal.notAlive();
            default -> ShopRefusal.notInMatch();
        };
    }
}
