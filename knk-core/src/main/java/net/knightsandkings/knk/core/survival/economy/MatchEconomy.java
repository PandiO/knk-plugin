package net.knightsandkings.knk.core.survival.economy;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One survival match's economy (DESIGN §8, §9; S20, S33, S44, S58): the {@link MatchLedger} for match currency, the
 * {@link KillRewardCalculator} for a kill's worth and the {@link PermanentRewardAccrual} for the permanent coins/XP that
 * link S8 settles. Pure accounting: membership, ghost state and kills are reported in; the Paper side applies the answers.
 * <p>
 * Nothing is settled here (S8): the accrual keeps exact tenths per player and {@link #accrual()} hands them over.
 */
public final class MatchEconomy {

    /** What a kill earned, for the message and the log. */
    public record KillCredit(UUID killer, KillReward reward, long balanceAfter, boolean bonus) {
        public boolean earned() {
            return reward != null && !reward.isNone();
        }
    }

    private final MatchLedger ledger;
    private final KillRewardCalculator calculator;
    private final PermanentRewardAccrual accrual = new PermanentRewardAccrual();
    private final Map<UUID, Integer> kills = new LinkedHashMap<>();
    private boolean ended;

    public MatchEconomy(String matchId, KillRewardSettings settings) {
        this.ledger = new MatchLedger(Objects.requireNonNull(matchId, "matchId"));
        this.calculator = new KillRewardCalculator(settings == null ? KillRewardSettings.defaults() : settings);
    }

    public String matchId() {
        return ledger.matchId();
    }

    public MatchLedger ledger() {
        return ledger;
    }

    public KillRewardSettings settings() {
        return calculator.settings();
    }

    public PermanentRewardAccrual accrual() {
        return accrual;
    }

    public boolean ended() {
        return ended;
    }

    // ---- membership and ghost state ----

    public void join(UUID player) {
        if (!ended) ledger.join(player);
    }

    /** A member left: the balance is gone (S44) and the permanent accrual is forfeited (S37, leavers earn nothing). */
    public void leave(UUID player) {
        ledger.leave(player);
        accrual.forfeit(player);
    }

    public void setGhost(UUID player, boolean ghost) {
        ledger.setGhost(player, ghost);
    }

    public boolean isGhost(UUID player) {
        return ledger.isGhost(player);
    }

    public boolean isMember(UUID player) {
        return ledger.isMember(player);
    }

    /** Match end: nothing is credited, charged or transferred afterwards. */
    public void end() {
        ended = true;
        ledger.end();
    }

    // ---- kills ----

    /**
     * Credits a player kill: match currency to the ledger (ghosts and non-members earn nothing), permanent coins/XP to the
     * accrual (exact tenths). The bonus multiplier applies once for a headshot or a critical hit.
     *
     * @return the credit, empty when the kill earned nothing (ghost killer, ended match, non-member)
     */
    public Optional<KillCredit> creditKill(long enemyId, KillBlow blow) {
        if (ended || blow == null) return Optional.empty();
        UUID killer = blow.attacker();
        if (!ledger.isMember(killer) || ledger.isGhost(killer)) return Optional.empty();
        EnemyKill kill = EnemyKill.byPlayer(enemyId, killer, blow.headshot(), blow.critical());
        KillReward reward = calculator.reward(kill);
        if (reward.isNone()) return Optional.empty();
        ledger.credit(killer, reward.matchCurrency());
        accrual.add(killer, reward);
        kills.merge(killer, 1, Integer::sum);
        return Optional.of(new KillCredit(killer, reward, ledger.balance(killer), kill.bonus()));
    }

    public int kills(UUID player) {
        return kills.getOrDefault(player, 0);
    }

    // ---- balances ----

    public long balance(UUID player) {
        return ledger.balance(player);
    }

    public Map<UUID, Long> balances() {
        return ledger.balances();
    }

    public boolean canAfford(UUID player, long price) {
        return !ended && ledger.canAfford(player, price);
    }

    /** A teammate transfer (atomic, no overdraft, idempotent by request id; ghosts on neither side). */
    public LedgerResult transfer(String requestId, UUID from, UUID to, long amount) {
        return ledger.transfer(new MatchLedger.TransferRequest(requestId, ledger.matchId(), from, to, amount));
    }

    /** A fixed-price charge (a purchase or a Mystery Box roll), idempotent by the charge id. */
    public LedgerResult charge(String chargeId, UUID player, long price, String product) {
        return ledger.charge(new MatchLedger.ChargeRequest(chargeId, ledger.matchId(), player, price, product));
    }

    /** The members with a balance or a kill, for the status lines. */
    public Collection<UUID> players() {
        return ledger.balances().keySet();
    }
}
