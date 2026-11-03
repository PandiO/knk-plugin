package net.knightsandkings.knk.core.survival.economy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Personal match balances (S12, S33, S44, S58, §8): one ledger per live match, every member starts at 0, earnings
 * are immediately spendable, nothing is stored on the user or survives the match.
 * <ul>
 *   <li>Credit: kill currency for a living member (ghosts earn nothing, S20).</li>
 *   <li>Transfer: a positive amount to a teammate in the same match, debit and credit atomically, no overdraft,
 *       no ghosts on either side, idempotent by retry id (a retry returns the first result and pays nothing twice;
 *       the same id with a different request is a conflict).</li>
 *   <li>Charge: a fixed-price purchase or Mystery Box roll (S34: 1000), same rules, charged once per retry id.</li>
 * </ul>
 */
public final class MatchLedger {

    /** A teammate transfer. */
    public record TransferRequest(String retryId, String matchId, UUID from, UUID to, long amount) {
        public TransferRequest {
            Objects.requireNonNull(retryId, "retryId");
            Objects.requireNonNull(matchId, "matchId");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /** A fixed-price purchase or roll. */
    public record ChargeRequest(String retryId, String matchId, UUID player, long price, String product) {
        public ChargeRequest {
            Objects.requireNonNull(retryId, "retryId");
            Objects.requireNonNull(matchId, "matchId");
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(product, "product");
        }
    }

    private final String matchId;
    private final Map<UUID, Long> balances = new LinkedHashMap<>();
    private final Set<UUID> ghosts = new LinkedHashSet<>();
    private final Map<String, Record> applied = new LinkedHashMap<>();
    private boolean ended;

    private record Record(Object request, long balanceAfter) { }

    public MatchLedger(String matchId) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
    }

    public String matchId() {
        return matchId;
    }

    /** A player joined the match: balance 0. Rejoining keeps the balance (there is no rejoin in v1 anyway). */
    public void join(UUID player) {
        balances.putIfAbsent(player, 0L);
    }

    /** The player left: the balance is gone for good. */
    public void leave(UUID player) {
        balances.remove(player);
        ghosts.remove(player);
    }

    public boolean isMember(UUID player) {
        return balances.containsKey(player);
    }

    public void setGhost(UUID player, boolean ghost) {
        if (ghost) ghosts.add(player); else ghosts.remove(player);
    }

    public boolean isGhost(UUID player) {
        return ghosts.contains(player);
    }

    /** The match ended: no more operations; balances are only read for the result screen. */
    public void end() {
        ended = true;
    }

    public long balance(UUID player) {
        return balances.getOrDefault(player, 0L);
    }

    public Map<UUID, Long> balances() {
        return Map.copyOf(balances);
    }

    /**
     * Credits earned match currency.
     *
     * @return false when nothing was credited (non-member, ghost, ended match, or a non-positive amount)
     */
    public boolean credit(UUID player, long amount) {
        if (ended || amount <= 0 || !isMember(player) || isGhost(player)) return false;
        balances.merge(player, amount, Long::sum);
        return true;
    }

    public LedgerResult transfer(TransferRequest request) {
        Optional<LedgerResult> replay = replay(request.retryId(), request);
        if (replay.isPresent()) return replay.get();
        LedgerResult.Reason reason = null;
        if (ended) reason = LedgerResult.Reason.MATCH_ENDED;
        else if (!matchId.equals(request.matchId())) reason = LedgerResult.Reason.WRONG_MATCH;
        else if (request.amount() <= 0) reason = LedgerResult.Reason.AMOUNT_NOT_POSITIVE;
        else if (!isMember(request.from())) reason = LedgerResult.Reason.PAYER_NOT_IN_MATCH;
        else if (!isMember(request.to())) reason = LedgerResult.Reason.RECIPIENT_NOT_IN_MATCH;
        else if (request.from().equals(request.to())) reason = LedgerResult.Reason.SELF_TRANSFER;
        else if (isGhost(request.from())) reason = LedgerResult.Reason.PAYER_IS_GHOST;
        else if (isGhost(request.to())) reason = LedgerResult.Reason.RECIPIENT_IS_GHOST;
        else if (balance(request.from()) < request.amount()) reason = LedgerResult.Reason.INSUFFICIENT_BALANCE;
        if (reason != null) return new LedgerResult.Rejected(request.retryId(), reason);
        long after = balance(request.from()) - request.amount();
        balances.put(request.from(), after);
        balances.merge(request.to(), request.amount(), Long::sum);
        applied.put(request.retryId(), new Record(request, after));
        return new LedgerResult.Accepted(request.retryId(), after, false);
    }

    public LedgerResult charge(ChargeRequest request) {
        Optional<LedgerResult> replay = replay(request.retryId(), request);
        if (replay.isPresent()) return replay.get();
        LedgerResult.Reason reason = null;
        if (ended) reason = LedgerResult.Reason.MATCH_ENDED;
        else if (!matchId.equals(request.matchId())) reason = LedgerResult.Reason.WRONG_MATCH;
        else if (request.price() <= 0) reason = LedgerResult.Reason.AMOUNT_NOT_POSITIVE;
        else if (!isMember(request.player())) reason = LedgerResult.Reason.PAYER_NOT_IN_MATCH;
        else if (isGhost(request.player())) reason = LedgerResult.Reason.PAYER_IS_GHOST;
        else if (balance(request.player()) < request.price()) reason = LedgerResult.Reason.INSUFFICIENT_BALANCE;
        if (reason != null) return new LedgerResult.Rejected(request.retryId(), reason);
        long after = balance(request.player()) - request.price();
        balances.put(request.player(), after);
        applied.put(request.retryId(), new Record(request, after));
        return new LedgerResult.Accepted(request.retryId(), after, false);
    }

    /** Whether a player can afford a price now (for showing the box price, §8a). */
    public boolean canAfford(UUID player, long price) {
        return isMember(player) && !isGhost(player) && balance(player) >= price;
    }

    private Optional<LedgerResult> replay(String retryId, Object request) {
        Record record = applied.get(retryId);
        if (record == null) return Optional.empty();
        if (record.request().equals(request)) {
            return Optional.of(new LedgerResult.Accepted(retryId, record.balanceAfter(), true));
        }
        return Optional.of(new LedgerResult.Rejected(retryId, LedgerResult.Reason.RETRY_ID_CONFLICT));
    }
}
