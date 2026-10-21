package net.knightsandkings.knk.core.duel;

import net.knightsandkings.knk.core.duel.DuelEffect.AwaitBattleground;
import net.knightsandkings.knk.core.duel.DuelEffect.DrawOffered;
import net.knightsandkings.knk.core.duel.DuelEffect.EnterBattleground;
import net.knightsandkings.knk.core.duel.DuelEffect.FightStarted;
import net.knightsandkings.knk.core.duel.DuelEffect.GrantKit;
import net.knightsandkings.knk.core.duel.DuelEffect.KitsLocked;
import net.knightsandkings.knk.core.duel.DuelEffect.MatchEnded;
import net.knightsandkings.knk.core.duel.DuelEffect.PhaseChanged;
import net.knightsandkings.knk.core.duel.DuelEffect.Release;
import net.knightsandkings.knk.core.duel.DuelEffect.StartLobbyTeleports;
import net.knightsandkings.knk.core.duel.DuelEffect.SwapKit;
import net.knightsandkings.knk.core.duel.DuelEffect.WarmupStarted;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One match's lifecycle (DESIGN §5, §5.1, §8.4, §8.6), as a pure state machine: each call returns the
 * {@link DuelEffect}s the runtime must apply, in order. Nothing here touches Bukkit or reads a clock.
 *
 * <p><b>Time</b> is the server tick passed in ({@link DuelTicks}). The runtime calls {@link #advance}
 * from its 1 s ticker, and also on the tick after a death or quit (see below).
 *
 * <p><b>One result</b> (end-once guard): the first ending wins; every later death, quit, draw, timeout
 * or abort is ignored and returns no effects.
 *
 * <p><b>Deaths and quits</b> from {@link DuelPhase#BATTLE_COUNTDOWN} on are recorded with their tick and
 * decided on the first {@link #advance} with a later tick, so that events on the same tick give one
 * result:
 * <ul>
 *   <li>one side lost → that side loses: {@link DuelEndReason#WIN} for a death in FIGHT, otherwise
 *       {@link DuelEndReason#FORFEIT} (a quit, or a death during the countdown, D10);</li>
 *   <li>a death and a quit by the same player → one loss (the death counts);</li>
 *   <li>both died → {@link DuelEndReason#SIMULTANEOUS_DEATH}, a draw (§8.6);</li>
 *   <li>one died and the other quit → the death decides it (the quitter is paid on join);</li>
 *   <li>both quit → cancelled as {@link DuelEndReason#PLAYER_LEFT_LOBBY}: nobody can claim the win.</li>
 * </ul>
 * Before the countdown, a quit or death cancels the match with a full refund
 * ({@link DuelEndReason#PLAYER_LEFT_LOBBY}, D10).
 *
 * <p><b>Kits</b>: {@code FIXED_KIT} uses the proposal's kit; {@code KIT_CHOICE} starts with the arena's
 * first eligible kit as a placeholder, a pick in WARMUP swaps it, and the countdown locks it (the
 * kit-choice deadline; no pick = the default, O3).
 *
 * <p>Not thread-safe: main thread only.
 */
public final class DuelStateMachine {

    public enum KitChoice {
        /** Picked (or re-picked the kit already held). */
        CHOSEN,
        /** The match isn't {@code KIT_CHOICE}. */
        NOT_KIT_CHOICE,
        /** Only in WARMUP; locked from the countdown on. */
        NOT_IN_WARMUP,
        /** Not one of the arena's eligible kits. */
        NOT_ELIGIBLE
    }

    public record KitChoiceOutcome(KitChoice status, List<DuelEffect> effects) {
        public KitChoiceOutcome {
            effects = List.copyOf(effects);
        }
    }

    public enum DrawOffer {
        /** First {@code /duel draw}; the other side has the window to agree. */
        OFFERED,
        /** Both sides asked within the window: the match is a draw. */
        AGREED,
        /** Only in FIGHT before a result. */
        NOT_IN_FIGHT
    }

    public record DrawOutcome(DrawOffer status, List<DuelEffect> effects) {
        public DrawOutcome {
            effects = List.copyOf(effects);
        }
    }

    private enum LossCause { DEATH, QUIT }

    private record Loss(LossCause cause, long tick, boolean inFight) {}

    private final DuelTimings timings;
    private final GearMode gearMode;
    private final List<Integer> eligibleKitIds;

    private DuelPhase phase = DuelPhase.LOCKING;
    private long phaseDeadlineTick = Long.MAX_VALUE;
    private boolean waitingForBattleground;
    private Integer battlegroundId;
    private final Map<DuelSide, Integer> kits = new EnumMap<>(DuelSide.class);
    private final Map<DuelSide, Boolean> picked = new EnumMap<>(DuelSide.class);
    private final Map<DuelSide, Loss> losses = new EnumMap<>(DuelSide.class);
    private final Map<DuelSide, Long> drawOfferTick = new EnumMap<>(DuelSide.class);
    private DuelResult result;
    private DuelPhase phaseAtEnd;

    /**
     * @param fixedKitId     the proposal's kit for {@code FIXED_KIT}, else ignored
     * @param eligibleKitIds the arena's eligible kits, in order (the first is the {@code KIT_CHOICE} default)
     * @throws IllegalArgumentException when a kit mode has no kit to grant
     */
    public DuelStateMachine(DuelTimings timings, GearMode gearMode, Integer fixedKitId, List<Integer> eligibleKitIds) {
        this.timings = Objects.requireNonNull(timings, "timings");
        this.gearMode = Objects.requireNonNull(gearMode, "gearMode");
        this.eligibleKitIds = List.copyOf(eligibleKitIds);
        if (gearMode == GearMode.FIXED_KIT) {
            if (fixedKitId == null) throw new IllegalArgumentException("FIXED_KIT needs a kit");
            kits.put(DuelSide.CHALLENGER, fixedKitId);
            kits.put(DuelSide.OPPONENT, fixedKitId);
        } else if (gearMode == GearMode.KIT_CHOICE) {
            if (this.eligibleKitIds.isEmpty()) throw new IllegalArgumentException("KIT_CHOICE needs an eligible kit");
            kits.put(DuelSide.CHALLENGER, this.eligibleKitIds.getFirst());
            kits.put(DuelSide.OPPONENT, this.eligibleKitIds.getFirst());
        }
    }

    /** For a contract that passed {@link StakeContractValidator}. */
    public static DuelStateMachine forContract(StakeContract contract, DuelArenaSettings settings, int agreedDrawWindowSeconds) {
        return new DuelStateMachine(settings.timings(agreedDrawWindowSeconds), contract.gearMode(),
                contract.fixedKitId(), settings.eligibleKitIds());
    }

    // ==================== Lock-in and travel ====================

    /** Stakes escrowed and the match created (DESIGN §6.5 step 4): LOCKING → TRAVELLING. */
    public List<DuelEffect> lockSucceeded(long nowTick) {
        if (phase != DuelPhase.LOCKING || ended()) return List.of();
        List<DuelEffect> effects = new ArrayList<>();
        moveTo(DuelPhase.TRAVELLING, Long.MAX_VALUE, effects);
        effects.add(new StartLobbyTeleports());
        return List.copyOf(effects);
    }

    /** Lock-in refused: closed, items handed back, no match record ({@link DuelEndReason#LOCK_REFUSED}). */
    public List<DuelEffect> lockFailed(long nowTick) {
        if (phase != DuelPhase.LOCKING) return List.of();
        return end(DuelResult.noWinner(DuelEndReason.LOCK_REFUSED), nowTick);
    }

    /** Both lobby teleports reported {@code TELEPORTED}: TRAVELLING → WARMUP. */
    public List<DuelEffect> travelCompleted(long nowTick) {
        if (phase != DuelPhase.TRAVELLING || ended()) return List.of();
        List<DuelEffect> effects = new ArrayList<>();
        moveTo(DuelPhase.WARMUP, nowTick + DuelTicks.ofSeconds(timings.warmupSeconds()), effects);
        effects.add(new WarmupStarted(timings.warmupSeconds()));
        if (gearMode.isKitMode()) {
            boolean placeholder = gearMode == GearMode.KIT_CHOICE;
            for (DuelSide side : DuelSide.values()) {
                effects.add(new GrantKit(side, kits.get(side), placeholder));
            }
        }
        return List.copyOf(effects);
    }

    /** Either lobby teleport wasn't {@code TELEPORTED} (D6): cancelled with a refund. */
    public List<DuelEffect> travelFailed(long nowTick) {
        if (phase != DuelPhase.TRAVELLING) return List.of();
        return end(DuelResult.noWinner(DuelEndReason.TELEPORT_FAILED), nowTick);
    }

    // ==================== Warmup ====================

    /** {@code /duel kit} in WARMUP ({@code KIT_CHOICE}). */
    public KitChoiceOutcome chooseKit(DuelSide side, int kitId) {
        if (gearMode != GearMode.KIT_CHOICE) return new KitChoiceOutcome(KitChoice.NOT_KIT_CHOICE, List.of());
        if (phase != DuelPhase.WARMUP || ended()) return new KitChoiceOutcome(KitChoice.NOT_IN_WARMUP, List.of());
        if (!eligibleKitIds.contains(kitId)) return new KitChoiceOutcome(KitChoice.NOT_ELIGIBLE, List.of());
        picked.put(side, true);
        int current = kits.get(side);
        if (current == kitId) return new KitChoiceOutcome(KitChoice.CHOSEN, List.of());
        kits.put(side, kitId);
        return new KitChoiceOutcome(KitChoice.CHOSEN, List.of(new SwapKit(side, current, kitId)));
    }

    /**
     * The arena allocated a battleground ({@link ArenaQueue#advance}): WARMUP → BATTLE_COUNTDOWN.
     * Returns no effects when the match isn't waiting (the runtime then releases the battleground).
     */
    public List<DuelEffect> battlegroundAssigned(int battlegroundId, long nowTick) {
        if (phase != DuelPhase.WARMUP || !waitingForBattleground || ended()) return List.of();
        this.battlegroundId = battlegroundId;
        waitingForBattleground = false;
        List<DuelEffect> effects = new ArrayList<>();
        moveTo(DuelPhase.BATTLE_COUNTDOWN, nowTick + DuelTicks.ofSeconds(timings.countdownSeconds()), effects);
        if (gearMode.isKitMode()) {
            effects.add(new KitsLocked(kits.get(DuelSide.CHALLENGER), kits.get(DuelSide.OPPONENT)));
        }
        effects.add(new EnterBattleground(battlegroundId, timings.countdownSeconds()));
        return List.copyOf(effects);
    }

    /** The wait for a battleground ran out ({@code MaxWaitSeconds}): cancelled with a refund. */
    public List<DuelEffect> noBattleground(long nowTick) {
        if (phase != DuelPhase.WARMUP) return List.of();
        return end(DuelResult.noWinner(DuelEndReason.NO_BATTLEGROUND), nowTick);
    }

    /** The {@code ARENA_SYSTEM} move to the spawns failed (DESIGN §5): cancelled with a refund. */
    public List<DuelEffect> battlegroundTeleportFailed(long nowTick) {
        if (phase != DuelPhase.BATTLE_COUNTDOWN) return List.of();
        return end(DuelResult.noWinner(DuelEndReason.TELEPORT_FAILED), nowTick);
    }

    // ==================== Fight ====================

    /** {@code /duel draw}: offer, or agree within the window (D11, §8.6). */
    public DrawOutcome offerDraw(DuelSide side, long nowTick) {
        if (phase != DuelPhase.FIGHT || ended() || !losses.isEmpty()) {
            return new DrawOutcome(DrawOffer.NOT_IN_FIGHT, List.of());
        }
        Long other = drawOfferTick.get(side.other());
        if (other != null && nowTick - other <= DuelTicks.ofSeconds(timings.agreedDrawWindowSeconds())) {
            return new DrawOutcome(DrawOffer.AGREED, end(DuelResult.noWinner(DuelEndReason.AGREED_DRAW), nowTick));
        }
        drawOfferTick.put(side, nowTick);
        return new DrawOutcome(DrawOffer.OFFERED,
                List.of(new DrawOffered(side, nowTick + DuelTicks.ofSeconds(timings.agreedDrawWindowSeconds()))));
    }

    /** A duelist died. Decided on the next {@link #advance} from the countdown on (see class doc). */
    public List<DuelEffect> onDeath(DuelSide side, long nowTick) {
        return recordLoss(side, LossCause.DEATH, nowTick);
    }

    /** A duelist quit or disconnected. Decided on the next {@link #advance} from the countdown on. */
    public List<DuelEffect> onQuit(DuelSide side, long nowTick) {
        return recordLoss(side, LossCause.QUIT, nowTick);
    }

    // ==================== Staff, outage, restart ====================

    /**
     * Ends the match without a winner from any phase before a result: {@code ADMIN_CANCELLED},
     * {@code API_OUTAGE}, {@code SERVER_RESTART} (or another non-decisive reason).
     */
    public List<DuelEffect> abort(DuelEndReason reason, long nowTick) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isDecisive()) throw new IllegalArgumentException("abort needs a non-decisive reason: " + reason);
        return end(DuelResult.noWinner(reason), nowTick);
    }

    // ==================== Time ====================

    /**
     * Time passes: decides recorded deaths/quits from an earlier tick, then the phase deadlines
     * (warmup → wait for a battleground, countdown → fight, fight time limit → draw, linger → closed).
     */
    public List<DuelEffect> advance(long nowTick) {
        List<DuelEffect> effects = new ArrayList<>();
        if (!losses.isEmpty() && !ended()) {
            long lossTick = losses.values().stream().mapToLong(Loss::tick).max().orElseThrow();
            if (nowTick > lossTick) effects.addAll(end(decideLosses(), nowTick));
        }
        if (nowTick < phaseDeadlineTick) return List.copyOf(effects);
        switch (phase) {
            case WARMUP -> {
                if (!waitingForBattleground && !ended()) {
                    waitingForBattleground = true;
                    phaseDeadlineTick = Long.MAX_VALUE;
                    effects.add(new AwaitBattleground());
                }
            }
            case BATTLE_COUNTDOWN -> {
                if (!ended()) {
                    long limit = timings.hasFightTimeLimit()
                            ? nowTick + DuelTicks.ofSeconds(timings.fightTimeLimitSeconds()) : Long.MAX_VALUE;
                    moveTo(DuelPhase.FIGHT, limit, effects);
                    effects.add(new FightStarted(timings.fightTimeLimitSeconds()));
                }
            }
            case FIGHT -> {
                if (losses.isEmpty()) effects.addAll(end(DuelResult.noWinner(DuelEndReason.TIME_LIMIT), nowTick));
            }
            case ENDING -> {
                moveTo(DuelPhase.CLOSED, Long.MAX_VALUE, effects);
                effects.add(new Release(phaseAtEnd.onBattleground()));
            }
            default -> { }
        }
        return List.copyOf(effects);
    }

    // ==================== Queries ====================

    public DuelPhase phase() {
        return phase;
    }

    public GearMode gearMode() {
        return gearMode;
    }

    /** The one result, or null while the match runs. */
    public DuelResult result() {
        return result;
    }

    /** The phase the match was in when its result came, or null while it runs. */
    public DuelPhase phaseAtEnd() {
        return phaseAtEnd;
    }

    public boolean ended() {
        return result != null;
    }

    /** The allocated battleground, or null before the countdown. */
    public Integer battlegroundId() {
        return battlegroundId;
    }

    /** Warmup is over and the match waits in the arena queue. */
    public boolean isWaitingForBattleground() {
        return waitingForBattleground;
    }

    /** The side's kit (kit modes), or null for own gear. */
    public Integer kitOf(DuelSide side) {
        return kits.get(side);
    }

    /** {@code KIT_CHOICE}: the player picked a kit themselves (otherwise they hold the default). */
    public boolean hasPickedKit(DuelSide side) {
        return picked.getOrDefault(side, false);
    }

    /** Damage between the duelists is locked: everywhere but FIGHT before a result (§5.1). */
    public boolean damageLocked() {
        return phase != DuelPhase.FIGHT || ended();
    }

    /** Seconds until the current phase's deadline (warmup, countdown, time limit, linger); 0 when none. */
    public int secondsRemaining(long nowTick) {
        return phaseDeadlineTick == Long.MAX_VALUE ? 0 : DuelTicks.secondsUntil(phaseDeadlineTick, nowTick);
    }

    /** The side's open draw offer expires at this tick, or -1 when it has none. */
    public long drawOfferExpiresAt(DuelSide side, long nowTick) {
        Long tick = drawOfferTick.get(side);
        if (tick == null) return -1;
        long expires = tick + DuelTicks.ofSeconds(timings.agreedDrawWindowSeconds());
        return nowTick <= expires ? expires : -1;
    }

    // ==================== Internals ====================

    private List<DuelEffect> recordLoss(DuelSide side, LossCause cause, long nowTick) {
        Objects.requireNonNull(side, "side");
        if (ended()) return List.of();
        if (!phase.quitForfeits()) {
            return end(DuelResult.noWinner(DuelEndReason.PLAYER_LEFT_LOBBY), nowTick);
        }
        Loss earlier = losses.get(side);
        if (earlier == null || (cause == LossCause.DEATH && earlier.cause() == LossCause.QUIT)) {
            losses.put(side, new Loss(cause, earlier == null ? nowTick : earlier.tick(), phase == DuelPhase.FIGHT));
        }
        return List.of();
    }

    private DuelResult decideLosses() {
        Loss challenger = losses.get(DuelSide.CHALLENGER);
        Loss opponent = losses.get(DuelSide.OPPONENT);
        if (challenger != null && opponent != null && challenger.tick() == opponent.tick()) {
            boolean challengerDied = challenger.cause() == LossCause.DEATH;
            boolean opponentDied = opponent.cause() == LossCause.DEATH;
            if (challengerDied && opponentDied) return DuelResult.noWinner(DuelEndReason.SIMULTANEOUS_DEATH);
            if (!challengerDied && !opponentDied) return DuelResult.noWinner(DuelEndReason.PLAYER_LEFT_LOBBY);
            DuelSide loser = challengerDied ? DuelSide.CHALLENGER : DuelSide.OPPONENT;
            return lossOf(loser, losses.get(loser));
        }
        // One loss, or two on different ticks: the earlier one decided it.
        DuelSide loser;
        if (challenger == null) loser = DuelSide.OPPONENT;
        else if (opponent == null) loser = DuelSide.CHALLENGER;
        else loser = challenger.tick() <= opponent.tick() ? DuelSide.CHALLENGER : DuelSide.OPPONENT;
        return lossOf(loser, losses.get(loser));
    }

    private static DuelResult lossOf(DuelSide loser, Loss loss) {
        DuelEndReason reason = loss.cause() == LossCause.DEATH && loss.inFight() ? DuelEndReason.WIN : DuelEndReason.FORFEIT;
        return DuelResult.win(loser.other(), reason);
    }

    /** The end-once guard: the first result wins, later endings return nothing. */
    private List<DuelEffect> end(DuelResult outcome, long nowTick) {
        if (ended() || phase == DuelPhase.CLOSED) return List.of();
        result = outcome;
        phaseAtEnd = phase;
        waitingForBattleground = false;
        List<DuelEffect> effects = new ArrayList<>();
        effects.add(new MatchEnded(outcome, phaseAtEnd));
        if (outcome.reason().status() == DuelEndReason.Status.COMPLETED) {
            moveTo(DuelPhase.ENDING, nowTick + DuelTicks.ofSeconds(timings.winnerLingerSeconds()), effects);
        } else {
            moveTo(DuelPhase.CLOSED, Long.MAX_VALUE, effects);
            effects.add(new Release(phaseAtEnd.onBattleground()));
        }
        return List.copyOf(effects);
    }

    private void moveTo(DuelPhase next, long deadlineTick, List<DuelEffect> effects) {
        DuelPhase from = phase;
        phase = next;
        phaseDeadlineTick = deadlineTick;
        effects.add(new PhaseChanged(from, next));
    }
}
