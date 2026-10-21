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
import net.knightsandkings.knk.core.duel.DuelStateMachine.DrawOffer;
import net.knightsandkings.knk.core.duel.DuelStateMachine.KitChoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static net.knightsandkings.knk.core.duel.DuelTestSupport.KIT_A;
import static net.knightsandkings.knk.core.duel.DuelTestSupport.KIT_B;
import static net.knightsandkings.knk.core.duel.DuelTestSupport.has;
import static net.knightsandkings.knk.core.duel.DuelTestSupport.only;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuelStateMachineTest {
    private static final DuelTimings TIMINGS = new DuelTimings(30, 10, 300, 5, 15);
    private static final int BATTLEGROUND = 4;
    private static final long WARMUP_START = 100;
    private static final long COUNTDOWN_START = WARMUP_START + DuelTicks.ofSeconds(30);
    private static final long FIGHT_START = COUNTDOWN_START + DuelTicks.ofSeconds(10);

    private static DuelStateMachine ownGear() {
        return new DuelStateMachine(TIMINGS, GearMode.OWN_GEAR_KEEP, null, List.of());
    }

    /** Drives a machine to {@code target}, asserting each transition on the way. */
    private static DuelStateMachine at(DuelPhase target, DuelStateMachine m) {
        if (target == DuelPhase.LOCKING) return m;
        assertEquals(DuelPhase.TRAVELLING, only(m.lockSucceeded(0), PhaseChanged.class).to());
        if (target == DuelPhase.TRAVELLING) return m;
        m.travelCompleted(WARMUP_START);
        assertEquals(DuelPhase.WARMUP, m.phase());
        if (target == DuelPhase.WARMUP) return m;
        assertTrue(has(m.advance(COUNTDOWN_START), AwaitBattleground.class));
        m.battlegroundAssigned(BATTLEGROUND, COUNTDOWN_START);
        assertEquals(DuelPhase.BATTLE_COUNTDOWN, m.phase());
        if (target == DuelPhase.BATTLE_COUNTDOWN) return m;
        m.advance(FIGHT_START);
        assertEquals(DuelPhase.FIGHT, m.phase());
        if (target == DuelPhase.FIGHT) return m;
        throw new IllegalArgumentException(target.name());
    }

    private static DuelStateMachine at(DuelPhase target) {
        return at(target, ownGear());
    }

    // ==================== Transitions ====================

    @Test
    void theWholeLifecycle() {
        DuelStateMachine m = ownGear();
        assertEquals(DuelPhase.LOCKING, m.phase());
        assertTrue(m.damageLocked());

        List<DuelEffect> lock = m.lockSucceeded(0);
        assertEquals(List.of(new PhaseChanged(DuelPhase.LOCKING, DuelPhase.TRAVELLING), new StartLobbyTeleports()), lock);

        List<DuelEffect> arrive = m.travelCompleted(WARMUP_START);
        assertEquals(List.of(new PhaseChanged(DuelPhase.TRAVELLING, DuelPhase.WARMUP), new WarmupStarted(30)), arrive);
        assertEquals(30, m.secondsRemaining(WARMUP_START));

        assertTrue(m.advance(COUNTDOWN_START - 1).isEmpty());
        assertEquals(List.of(new AwaitBattleground()), m.advance(COUNTDOWN_START));
        assertTrue(m.isWaitingForBattleground());
        assertTrue(m.advance(COUNTDOWN_START + 1000).isEmpty(), "waiting has no deadline of its own (ArenaQueue)");

        long assigned = COUNTDOWN_START + 1000;
        List<DuelEffect> countdown = m.battlegroundAssigned(BATTLEGROUND, assigned);
        assertEquals(List.of(new PhaseChanged(DuelPhase.WARMUP, DuelPhase.BATTLE_COUNTDOWN),
                new EnterBattleground(BATTLEGROUND, 10)), countdown);
        assertTrue(m.damageLocked());
        assertTrue(m.battlegroundAssigned(9, assigned).isEmpty(), "only once");

        long fight = assigned + DuelTicks.ofSeconds(10);
        assertEquals(List.of(new PhaseChanged(DuelPhase.BATTLE_COUNTDOWN, DuelPhase.FIGHT), new FightStarted(300)),
                m.advance(fight));
        assertFalse(m.damageLocked());

        m.onDeath(DuelSide.OPPONENT, fight + 50);
        List<DuelEffect> ended = m.advance(fight + 51);
        assertEquals(new MatchEnded(DuelResult.win(DuelSide.CHALLENGER, DuelEndReason.WIN), DuelPhase.FIGHT), ended.getFirst());
        assertEquals(DuelPhase.ENDING, m.phase());
        assertTrue(m.damageLocked());

        long linger = fight + 51 + DuelTicks.ofSeconds(5);
        assertTrue(m.advance(linger - 1).isEmpty());
        assertEquals(List.of(new PhaseChanged(DuelPhase.ENDING, DuelPhase.CLOSED), new Release(true)), m.advance(linger));
        assertTrue(m.advance(linger + 1000).isEmpty());
        assertEquals(DuelResult.win(DuelSide.CHALLENGER, DuelEndReason.WIN), m.result());
    }

    @Test
    void lockRefused() {
        DuelStateMachine m = ownGear();
        List<DuelEffect> effects = m.lockFailed(0);
        assertEquals(DuelEndReason.LOCK_REFUSED, only(effects, MatchEnded.class).result().reason());
        assertEquals(new Release(false), effects.getLast());
        assertEquals(DuelPhase.CLOSED, m.phase());
        assertTrue(m.lockSucceeded(1).isEmpty());
    }

    @Test
    void aFailedLobbyTeleportCancels() {
        DuelStateMachine m = at(DuelPhase.TRAVELLING);
        assertEquals(DuelEndReason.TELEPORT_FAILED, only(m.travelFailed(5), MatchEnded.class).result().reason());
        assertEquals(DuelPhase.CLOSED, m.phase());
        assertTrue(m.travelCompleted(6).isEmpty());
    }

    @Test
    void aFailedBattlegroundTeleportCancelsAndLeavesTheBattleground() {
        DuelStateMachine m = at(DuelPhase.BATTLE_COUNTDOWN);
        List<DuelEffect> effects = m.battlegroundTeleportFailed(COUNTDOWN_START + 1);
        assertEquals(DuelEndReason.TELEPORT_FAILED, only(effects, MatchEnded.class).result().reason());
        assertEquals(new Release(true), effects.getLast());
    }

    @Test
    void noBattlegroundCancels() {
        DuelStateMachine m = at(DuelPhase.WARMUP);
        m.advance(COUNTDOWN_START);
        assertEquals(DuelEndReason.NO_BATTLEGROUND, only(m.noBattleground(COUNTDOWN_START + 10), MatchEnded.class).result().reason());
        assertFalse(m.isWaitingForBattleground());
        assertTrue(m.battlegroundAssigned(1, COUNTDOWN_START + 11).isEmpty());
    }

    @Test
    void aBattlegroundBeforeWarmupEndsIsRefused() {
        DuelStateMachine m = at(DuelPhase.WARMUP);
        assertTrue(m.battlegroundAssigned(1, WARMUP_START + 1).isEmpty());
        assertEquals(DuelPhase.WARMUP, m.phase());
    }

    // ==================== Abort at each phase ====================

    @ParameterizedTest
    @EnumSource(value = DuelPhase.class, names = {"LOCKING", "TRAVELLING", "WARMUP", "BATTLE_COUNTDOWN", "FIGHT"})
    void abortAtEachPhaseRefundsOnce(DuelPhase phase) {
        for (DuelEndReason reason : List.of(DuelEndReason.ADMIN_CANCELLED, DuelEndReason.API_OUTAGE, DuelEndReason.SERVER_RESTART)) {
            DuelStateMachine m = at(phase);
            List<DuelEffect> effects = m.abort(reason, 999_999);
            MatchEnded ended = only(effects, MatchEnded.class);
            assertEquals(DuelResult.noWinner(reason), ended.result());
            assertEquals(phase, ended.phaseAtEnd());
            assertEquals(new PhaseChanged(phase, DuelPhase.CLOSED), effects.get(1));
            assertEquals(new Release(phase.onBattleground()), effects.getLast());
            assertEquals(DuelPhase.CLOSED, m.phase());
            assertTrue(m.abort(reason, 1_000_000).isEmpty(), "end once");
            assertTrue(m.onQuit(DuelSide.CHALLENGER, 1_000_000).isEmpty());
        }
    }

    @Test
    void abortDuringEndingDoesNotChangeTheResult() {
        DuelStateMachine m = at(DuelPhase.FIGHT);
        m.onDeath(DuelSide.CHALLENGER, FIGHT_START + 1);
        m.advance(FIGHT_START + 2);
        assertTrue(m.abort(DuelEndReason.API_OUTAGE, FIGHT_START + 3).isEmpty());
        assertEquals(DuelEndReason.WIN, m.result().reason());
    }

    @Test
    void abortNeedsANonDecisiveReason() {
        assertThrows(IllegalArgumentException.class, () -> ownGear().abort(DuelEndReason.WIN, 0));
    }

    // ==================== Quits and deaths ====================

    @ParameterizedTest
    @EnumSource(value = DuelPhase.class, names = {"LOCKING", "TRAVELLING", "WARMUP"})
    void aQuitBeforeTheCountdownCancels(DuelPhase phase) {
        DuelStateMachine m = at(phase);
        MatchEnded ended = only(m.onQuit(DuelSide.OPPONENT, 5_000), MatchEnded.class);
        assertEquals(DuelResult.noWinner(DuelEndReason.PLAYER_LEFT_LOBBY), ended.result());
        assertEquals(DuelPhase.CLOSED, m.phase());
    }

    @Test
    void aDeathInWarmupCancels() {
        DuelStateMachine m = at(DuelPhase.WARMUP);
        assertEquals(DuelEndReason.PLAYER_LEFT_LOBBY, only(m.onDeath(DuelSide.CHALLENGER, WARMUP_START + 1), MatchEnded.class).result().reason());
    }

    @Test
    void aQuitInTheCountdownForfeits() {
        DuelStateMachine m = at(DuelPhase.BATTLE_COUNTDOWN);
        assertTrue(m.onQuit(DuelSide.CHALLENGER, COUNTDOWN_START + 5).isEmpty(), "decided next tick");
        MatchEnded ended = only(m.advance(COUNTDOWN_START + 6), MatchEnded.class);
        assertEquals(DuelResult.win(DuelSide.OPPONENT, DuelEndReason.FORFEIT), ended.result());
        assertEquals(DuelPhase.ENDING, m.phase());
    }

    @Test
    void aQuitInTheFightForfeits() {
        DuelStateMachine m = at(DuelPhase.FIGHT);
        m.onQuit(DuelSide.OPPONENT, FIGHT_START + 5);
        assertEquals(DuelResult.win(DuelSide.CHALLENGER, DuelEndReason.FORFEIT), m.advance(FIGHT_START + 6).stream()
                .filter(MatchEnded.class::isInstance).map(e -> ((MatchEnded) e).result()).findFirst().orElseThrow());
    }

    @Test
    void aLossIsNotDecidedOnItsOwnTick() {
        DuelStateMachine m = at(DuelPhase.FIGHT);
        m.onDeath(DuelSide.OPPONENT, FIGHT_START + 5);
        assertTrue(m.advance(FIGHT_START + 5).isEmpty());
        assertNull(m.result());
        assertFalse(m.damageLocked(), "the survivor can still die on this tick");
    }

    @Test
    void deathAndQuitOfOnePlayerOnTheSameTickGiveOneResult() {
        DuelStateMachine m = at(DuelPhase.FIGHT);
        long tick = FIGHT_START + 40;
        m.onQuit(DuelSide.OPPONENT, tick);
        m.onDeath(DuelSide.OPPONENT, tick);
        List<DuelEffect> effects = m.advance(tick + 1);
        assertEquals(1, effects.stream().filter(MatchEnded.class::isInstance).count());
        assertEquals(DuelResult.win(DuelSide.CHALLENGER, DuelEndReason.WIN), m.result());
        assertTrue(m.onDeath(DuelSide.CHALLENGER, tick + 2).isEmpty());
        assertTrue(m.advance(tick + 3).isEmpty());
        assertEquals(DuelResult.win(DuelSide.CHALLENGER, DuelEndReason.WIN), m.result());
    }

    @Test
    void aDeathAndTheOtherPlayersQuitOnTheSameTick() {
        DuelStateMachine m = at(DuelPhase.FIGHT);
        long tick = FIGHT_START + 40;
        m.onDeath(DuelSide.CHALLENGER, tick);
        m.onQuit(DuelSide.OPPONENT, tick);
        m.advance(tick + 1);
        assertEquals(DuelResult.win(DuelSide.OPPONENT, DuelEndReason.WIN), m.result(), "the death decides it");
    }

    @Test
    void bothQuitOnTheSameTickCancels() {
        DuelStateMachine m = at(DuelPhase.FIGHT);
        long tick = FIGHT_START + 40;
        m.onQuit(DuelSide.CHALLENGER, tick);
        m.onQuit(DuelSide.OPPONENT, tick);
        List<DuelEffect> effects = m.advance(tick + 1);
        assertEquals(DuelResult.noWinner(DuelEndReason.PLAYER_LEFT_LOBBY), m.result());
        assertEquals(DuelPhase.CLOSED, m.phase());
        assertEquals(new Release(true), effects.getLast());
    }

    @Test
    void aSimultaneousDeathIsADraw() {
        DuelStateMachine m = at(DuelPhase.FIGHT);
        long tick = FIGHT_START + 40;
        m.onDeath(DuelSide.CHALLENGER, tick);
        m.onDeath(DuelSide.OPPONENT, tick);
        MatchEnded ended = only(m.advance(tick + 1), MatchEnded.class);
        assertEquals(DuelResult.noWinner(DuelEndReason.SIMULTANEOUS_DEATH), ended.result());
        assertTrue(ended.result().reason().isDraw());
        assertEquals(DuelPhase.ENDING, m.phase());
    }

    @Test
    void deathsOnDifferentTicksAreNotADraw() {
        DuelStateMachine m = at(DuelPhase.FIGHT);
        m.onDeath(DuelSide.OPPONENT, FIGHT_START + 40);
        m.onDeath(DuelSide.CHALLENGER, FIGHT_START + 41);
        m.advance(FIGHT_START + 42);
        assertEquals(DuelResult.win(DuelSide.CHALLENGER, DuelEndReason.WIN), m.result());
    }

    @Test
    void aDeathDuringTheCountdownForfeits() {
        DuelStateMachine m = at(DuelPhase.BATTLE_COUNTDOWN);
        m.onDeath(DuelSide.OPPONENT, COUNTDOWN_START + 1);
        m.advance(COUNTDOWN_START + 2);
        assertEquals(DuelResult.win(DuelSide.CHALLENGER, DuelEndReason.FORFEIT), m.result());
    }

    @Test
    void aLossOnTheCountdownsLastTickStillForfeits() {
        DuelStateMachine m = at(DuelPhase.BATTLE_COUNTDOWN);
        m.onQuit(DuelSide.OPPONENT, FIGHT_START - 1);
        m.advance(FIGHT_START);
        assertEquals(DuelResult.win(DuelSide.CHALLENGER, DuelEndReason.FORFEIT), m.result());
        assertEquals(DuelPhase.ENDING, m.phase(), "the fight never starts");
    }

    @Test
    void aQuitDuringEndingChangesNothing() {
        DuelStateMachine m = at(DuelPhase.FIGHT);
        m.onDeath(DuelSide.OPPONENT, FIGHT_START + 1);
        m.advance(FIGHT_START + 2);
        assertTrue(m.onQuit(DuelSide.CHALLENGER, FIGHT_START + 3).isEmpty());
        assertTrue(m.advance(FIGHT_START + 4).isEmpty());
        assertEquals(DuelResult.win(DuelSide.CHALLENGER, DuelEndReason.WIN), m.result());
    }

    // ==================== Time limit ====================

    @Test
    void theTimeLimitIsADraw() {
        DuelStateMachine m = at(DuelPhase.FIGHT);
        long limit = FIGHT_START + DuelTicks.ofSeconds(300);
        assertEquals(300, m.secondsRemaining(FIGHT_START));
        assertTrue(m.advance(limit - 1).isEmpty());
        MatchEnded ended = only(m.advance(limit), MatchEnded.class);
        assertEquals(DuelResult.noWinner(DuelEndReason.TIME_LIMIT), ended.result());
    }

    @Test
    void aDeathRecordedJustBeforeTheLimitStillWins() {
        DuelStateMachine m = at(DuelPhase.FIGHT);
        long limit = FIGHT_START + DuelTicks.ofSeconds(300);
        m.onDeath(DuelSide.CHALLENGER, limit - 1);
        m.advance(limit);
        assertEquals(DuelResult.win(DuelSide.OPPONENT, DuelEndReason.WIN), m.result());
    }

    @Test
    void timeLimitZeroMeansNone() {
        DuelStateMachine m = at(DuelPhase.FIGHT, new DuelStateMachine(new DuelTimings(30, 10, 0, 5, 15),
                GearMode.OWN_GEAR_KEEP, null, List.of()));
        assertTrue(m.advance(FIGHT_START).isEmpty());
        assertEquals(0, m.secondsRemaining(FIGHT_START));
        assertTrue(m.advance(FIGHT_START + DuelTicks.ofSeconds(100_000)).isEmpty());
        assertEquals(DuelPhase.FIGHT, m.phase());
    }

    @Test
    void firstFightStartedCarriesTheLimit() {
        DuelStateMachine m = at(DuelPhase.BATTLE_COUNTDOWN, new DuelStateMachine(new DuelTimings(30, 10, 0, 5, 15),
                GearMode.OWN_GEAR_KEEP, null, List.of()));
        assertEquals(new FightStarted(0), only(m.advance(FIGHT_START), FightStarted.class));
    }

    // ==================== Agreed draw ====================

    @Test
    void anAgreedDrawNeedsBothInsideTheWindow() {
        DuelStateMachine m = at(DuelPhase.FIGHT);
        DuelStateMachine.DrawOutcome first = m.offerDraw(DuelSide.CHALLENGER, FIGHT_START + 10);
        assertEquals(DrawOffer.OFFERED, first.status());
        assertEquals(new DrawOffered(DuelSide.CHALLENGER, FIGHT_START + 10 + DuelTicks.ofSeconds(15)), first.effects().getFirst());
        assertEquals(FIGHT_START + 10 + DuelTicks.ofSeconds(15), m.drawOfferExpiresAt(DuelSide.CHALLENGER, FIGHT_START + 11));
        DuelStateMachine.DrawOutcome second = m.offerDraw(DuelSide.OPPONENT, FIGHT_START + 10 + DuelTicks.ofSeconds(15));
        assertEquals(DrawOffer.AGREED, second.status());
        assertEquals(DuelResult.noWinner(DuelEndReason.AGREED_DRAW), only(second.effects(), MatchEnded.class).result());
        assertEquals(DuelPhase.ENDING, m.phase());
    }

    @Test
    void anAgreedDrawOutsideTheWindowIsANewOffer() {
        DuelStateMachine m = at(DuelPhase.FIGHT);
        m.offerDraw(DuelSide.CHALLENGER, FIGHT_START + 10);
        long late = FIGHT_START + 11 + DuelTicks.ofSeconds(15);
        assertEquals(-1, m.drawOfferExpiresAt(DuelSide.CHALLENGER, late));
        assertEquals(DrawOffer.OFFERED, m.offerDraw(DuelSide.OPPONENT, late).status());
        assertNull(m.result());
        assertEquals(DrawOffer.AGREED, m.offerDraw(DuelSide.CHALLENGER, late + 1).status());
    }

    @Test
    void theSameSideTwiceIsNoDraw() {
        DuelStateMachine m = at(DuelPhase.FIGHT);
        m.offerDraw(DuelSide.CHALLENGER, FIGHT_START + 10);
        assertEquals(DrawOffer.OFFERED, m.offerDraw(DuelSide.CHALLENGER, FIGHT_START + 11).status());
        assertNull(m.result());
    }

    @ParameterizedTest
    @EnumSource(value = DuelPhase.class, names = {"LOCKING", "TRAVELLING", "WARMUP", "BATTLE_COUNTDOWN"})
    void drawOnlyInTheFight(DuelPhase phase) {
        assertEquals(DrawOffer.NOT_IN_FIGHT, at(phase).offerDraw(DuelSide.CHALLENGER, 0).status());
    }

    @Test
    void noDrawOnceALossIsRecorded() {
        DuelStateMachine m = at(DuelPhase.FIGHT);
        m.offerDraw(DuelSide.CHALLENGER, FIGHT_START + 1);
        m.onDeath(DuelSide.CHALLENGER, FIGHT_START + 2);
        assertEquals(DrawOffer.NOT_IN_FIGHT, m.offerDraw(DuelSide.OPPONENT, FIGHT_START + 2).status());
        m.advance(FIGHT_START + 3);
        assertEquals(DuelEndReason.WIN, m.result().reason());
    }

    // ==================== Kits ====================

    private static DuelStateMachine kitChoice() {
        return new DuelStateMachine(TIMINGS, GearMode.KIT_CHOICE, null, List.of(KIT_A, KIT_B));
    }

    @Test
    void kitChoiceGrantsTheFirstEligibleKitAsAPlaceholder() {
        DuelStateMachine m = kitChoice();
        m.lockSucceeded(0);
        List<DuelEffect> effects = m.travelCompleted(WARMUP_START);
        assertTrue(effects.contains(new GrantKit(DuelSide.CHALLENGER, KIT_A, true)));
        assertTrue(effects.contains(new GrantKit(DuelSide.OPPONENT, KIT_A, true)));
    }

    @Test
    void theKitDefaultsToTheFirstEligibleKitAtTheDeadline() {
        DuelStateMachine m = at(DuelPhase.WARMUP, kitChoice());
        DuelStateMachine.KitChoiceOutcome pick = m.chooseKit(DuelSide.OPPONENT, KIT_B);
        assertEquals(KitChoice.CHOSEN, pick.status());
        assertEquals(List.of(new SwapKit(DuelSide.OPPONENT, KIT_A, KIT_B)), pick.effects());
        assertTrue(m.hasPickedKit(DuelSide.OPPONENT));
        assertFalse(m.hasPickedKit(DuelSide.CHALLENGER));
        m.advance(COUNTDOWN_START);
        List<DuelEffect> countdown = m.battlegroundAssigned(BATTLEGROUND, COUNTDOWN_START);
        assertEquals(new KitsLocked(KIT_A, KIT_B), only(countdown, KitsLocked.class));
        assertEquals(KIT_A, m.kitOf(DuelSide.CHALLENGER));
        assertEquals(KitChoice.NOT_IN_WARMUP, m.chooseKit(DuelSide.CHALLENGER, KIT_B).status(), "locked at the countdown");
        assertEquals(KIT_A, m.kitOf(DuelSide.CHALLENGER));
    }

    @Test
    void kitChoiceRules() {
        DuelStateMachine m = at(DuelPhase.WARMUP, kitChoice());
        assertEquals(KitChoice.NOT_ELIGIBLE, m.chooseKit(DuelSide.CHALLENGER, 999).status());
        DuelStateMachine.KitChoiceOutcome same = m.chooseKit(DuelSide.CHALLENGER, KIT_A);
        assertEquals(KitChoice.CHOSEN, same.status());
        assertTrue(same.effects().isEmpty(), "re-picking the held kit swaps nothing");
        assertEquals(KitChoice.NOT_KIT_CHOICE, at(DuelPhase.WARMUP).chooseKit(DuelSide.CHALLENGER, KIT_A).status());
        DuelStateMachine travelling = at(DuelPhase.TRAVELLING, kitChoice());
        assertEquals(KitChoice.NOT_IN_WARMUP, travelling.chooseKit(DuelSide.CHALLENGER, KIT_B).status());
    }

    @Test
    void fixedKitGrantsTheProposalsKit() {
        DuelStateMachine m = new DuelStateMachine(TIMINGS, GearMode.FIXED_KIT, KIT_B, List.of(KIT_A, KIT_B));
        m.lockSucceeded(0);
        List<DuelEffect> effects = m.travelCompleted(WARMUP_START);
        assertTrue(effects.contains(new GrantKit(DuelSide.CHALLENGER, KIT_B, false)));
        assertTrue(effects.contains(new GrantKit(DuelSide.OPPONENT, KIT_B, false)));
        assertEquals(KitChoice.NOT_KIT_CHOICE, m.chooseKit(DuelSide.CHALLENGER, KIT_A).status());
    }

    @Test
    void ownGearGrantsNoKit() {
        DuelStateMachine m = at(DuelPhase.WARMUP);
        assertNull(m.kitOf(DuelSide.CHALLENGER));
        m.advance(COUNTDOWN_START);
        assertFalse(has(m.battlegroundAssigned(1, COUNTDOWN_START), KitsLocked.class));
    }

    @Test
    void kitModesNeedAKit() {
        assertThrows(IllegalArgumentException.class, () -> new DuelStateMachine(TIMINGS, GearMode.FIXED_KIT, null, List.of(KIT_A)));
        assertThrows(IllegalArgumentException.class, () -> new DuelStateMachine(TIMINGS, GearMode.KIT_CHOICE, null, List.of()));
    }

    @Test
    void forContractUsesTheArenaSettings() {
        DuelArenaSettings settings = DuelTestSupport.settings(3);
        StakeContract contract = new StakeContract(GearMode.KIT_CHOICE, null, Stake.NONE, Stake.NONE);
        DuelStateMachine m = DuelStateMachine.forContract(contract, settings, 15);
        assertEquals(GearMode.KIT_CHOICE, m.gearMode());
        assertEquals(settings.defaultKitId(), m.kitOf(DuelSide.OPPONENT));
    }

    @Test
    void zeroLingerClosesOnTheResultTick() {
        DuelStateMachine m = at(DuelPhase.FIGHT, new DuelStateMachine(new DuelTimings(30, 10, 300, 0, 15),
                GearMode.OWN_GEAR_KEEP, null, List.of()));
        m.onDeath(DuelSide.CHALLENGER, FIGHT_START + 1);
        List<DuelEffect> effects = m.advance(FIGHT_START + 2);
        assertTrue(has(effects, MatchEnded.class));
        assertEquals(new Release(true), effects.getLast());
        assertEquals(DuelPhase.CLOSED, m.phase());
    }
}
