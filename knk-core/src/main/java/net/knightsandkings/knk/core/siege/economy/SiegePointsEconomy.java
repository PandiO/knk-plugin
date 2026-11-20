package net.knightsandkings.knk.core.siege.economy;

import net.knightsandkings.knk.core.siege.economy.SiegePointsEarnCalculator.Award;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One PvP Siege match's siege points (siege shop DESIGN § 5.1, P01, P07, P12, P15, P27; PandiO/knk-workspace#866): the
 * shared {@link MatchLedger} for balances, the {@link SiegePointsEarnCalculator} and {@link AssistTracker} for what
 * play earns, and the earned/spent totals per player for the match result. Survival's {@code MatchEconomy} does the
 * same for its kills; both sit on the same ledger.
 * <ul>
 *   <li>Every member starts at 0 ({@link #join}); a leaver's balance is gone, their totals stay for the result.</li>
 *   <li>Credits are only for members of a live match ({@link #credit} is also the hook for notable events, #612).</li>
 *   <li>Transfers need the mode's switch (P07) and a teammate; the ledger keeps them atomic and retry-safe.</li>
 *   <li>Nothing outlives the plugin (P12).</li>
 * </ul>
 * Earned counts what the player received (play and transfers in), spent what they paid (charges and transfers out),
 * as Survival's {@code MatchEconomy} does. Pure; main thread only.
 */
public final class SiegePointsEconomy {

    /** Points that reached a player's balance. */
    public record Credit(UUID player, SiegePointsSource source, long amount, double multiplier, long balanceAfter) { }

    /** The answer to a teammate transfer. */
    public record TransferOutcome(Status status, LedgerResult ledgerResult) {
        public enum Status {
            /** The ledger answered (accepted or rejected; see {@link #ledgerResult}). */
            LEDGER,
            /** The mode's transfer switch is off (PvP default, P07). */
            TRANSFERS_OFF,
            /** The receiver is not on the payer's team. */
            NOT_TEAMMATE
        }

        public boolean accepted() {
            return status == Status.LEDGER && ledgerResult != null && ledgerResult.accepted();
        }
    }

    private final MatchLedger ledger;
    private final SiegePointsEarnCalculator calculator;
    private final AssistTracker assists;
    private final Map<UUID, Long> earned = new LinkedHashMap<>();
    private final Map<UUID, Long> spent = new LinkedHashMap<>();
    private final java.util.Set<String> reversals = new java.util.HashSet<>();
    private boolean ended;

    public SiegePointsEconomy(String matchId, SiegePointsSettings settings) {
        SiegePointsSettings s = settings == null ? SiegePointsSettings.defaults() : settings;
        this.ledger = new MatchLedger(Objects.requireNonNull(matchId, "matchId"));
        this.calculator = new SiegePointsEarnCalculator(s);
        this.assists = AssistTracker.of(s);
    }

    public String matchId() {
        return ledger.matchId();
    }

    public MatchLedger ledger() {
        return ledger;
    }

    public SiegePointsEarnCalculator calculator() {
        return calculator;
    }

    public AssistTracker assists() {
        return assists;
    }

    public SiegePointsSettings settings() {
        return calculator.settings();
    }

    public boolean ended() {
        return ended;
    }

    // ---- membership ----

    /** A member joined (match start, rejoin, admin join): balance 0 unless they are still a member. */
    public void join(UUID player) {
        if (!ended) ledger.join(player);
    }

    /** A member left: the balance is gone (§ 5.1.3); earned and spent stay for the result. */
    public void leave(UUID player) {
        ledger.leave(player);
        assists.forget(player);
        calculator.forget(player);
    }

    public boolean isMember(UUID player) {
        return ledger.isMember(player);
    }

    /** The match ended: nothing is credited, charged or transferred afterwards; balances stay readable. */
    public void end() {
        ended = true;
        ledger.end();
        assists.clear();
    }

    // ---- earning ----

    /**
     * The credit hook (P15): adds points from any source, notable events (#612) included.
     *
     * @return the credit, empty when nothing was credited (ended match, non-member, non-positive amount)
     */
    public Optional<Credit> credit(UUID player, SiegePointsSource source, long amount) {
        return credit(player, source, amount, 1.0);
    }

    /** Applies the calculator's awards; returns those that reached a balance, in order. */
    public List<Credit> apply(Collection<Award> awards) {
        List<Credit> credits = new ArrayList<>();
        for (Award award : awards) {
            credit(award.player(), award.source(), award.amount(), award.multiplier()).ifPresent(credits::add);
        }
        return credits;
    }

    private Optional<Credit> credit(UUID player, SiegePointsSource source, long amount, double multiplier) {
        if (ended || player == null || source == null || !ledger.credit(player, amount)) return Optional.empty();
        earned.merge(player, amount, Long::sum);
        return Optional.of(new Credit(player, source, amount, multiplier, ledger.balance(player)));
    }

    // ---- spending ----

    /**
     * A teammate transfer (P07, § 5.1.5): refused when the switch is off or the receiver is not a teammate; otherwise
     * the ledger's rules (positive, both members, no overdraft, idempotent by request id).
     */
    public TransferOutcome transfer(String requestId, UUID from, UUID to, long amount, boolean sameTeam) {
        if (!settings().transfersEnabled()) return new TransferOutcome(TransferOutcome.Status.TRANSFERS_OFF, null);
        if (!sameTeam) return new TransferOutcome(TransferOutcome.Status.NOT_TEAMMATE, null);
        LedgerResult result = ledger.transfer(new MatchLedger.TransferRequest(requestId, ledger.matchId(), from, to, amount));
        if (result instanceof LedgerResult.Accepted accepted && !accepted.replayed()) {
            spent.merge(from, amount, Long::sum);
            earned.merge(to, amount, Long::sum);
        }
        return new TransferOutcome(TransferOutcome.Status.LEDGER, result);
    }

    /** A purchase (the siege shop, #868), idempotent by the charge id. */
    public LedgerResult charge(String chargeId, UUID player, long price, String product) {
        LedgerResult result = ledger.charge(new MatchLedger.ChargeRequest(chargeId, ledger.matchId(), player, price, product));
        if (result instanceof LedgerResult.Accepted accepted && !accepted.replayed()) {
            spent.merge(player, price, Long::sum);
        }
        return result;
    }

    /**
     * Gives back a charge whose delivery failed (the siege shop, #868, § 5.5.2): a ledger credit of {@code amount}
     * that no longer counts as spent, applied once per {@code reversalId}.
     *
     * @return true when the points went back
     */
    public boolean reverseCharge(String reversalId, UUID player, long amount) {
        if (reversalId == null || player == null || amount <= 0 || reversals.contains(reversalId)) return false;
        if (!ledger.credit(player, amount)) return false;
        reversals.add(reversalId);
        spent.merge(player, -amount, Long::sum);
        return true;
    }

    // ---- reading ----

    public long balance(UUID player) {
        return ledger.balance(player);
    }

    public long earned(UUID player) {
        return earned.getOrDefault(player, 0L);
    }

    public long spent(UUID player) {
        return spent.getOrDefault(player, 0L);
    }

    /** Everyone who earned or spent anything this match, leavers included (for the match result). */
    public Map<UUID, Long> earnedTotals() {
        return Map.copyOf(earned);
    }

    public Map<UUID, Long> spentTotals() {
        return Map.copyOf(spent);
    }
}
