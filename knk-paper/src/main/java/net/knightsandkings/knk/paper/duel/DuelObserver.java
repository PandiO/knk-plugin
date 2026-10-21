package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Settlement;
import net.knightsandkings.knk.core.duel.DuelNegotiation;
import net.knightsandkings.knk.core.duel.DuelOfferBook;
import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.DuelResult;
import net.knightsandkings.knk.core.duel.DuelSide;

import java.util.List;

/**
 * What the duel runtime tells the UI (P5: chat offers, the kit picker) and the announcer/scoreboards (P6). Every
 * call is on the main thread, after the runtime applied the change; an observer that throws is logged and skipped.
 */
public interface DuelObserver {

    /** Negotiations ended: declined, expired, a player left, superseded by a lock-in, or a lock-in refused. */
    default void negotiationsClosed(List<DuelOfferBook.Closed> closed) {
    }

    /** An accept was refused at lock-in; nothing was taken (or everything was handed back). */
    default void lockRefused(DuelNegotiation negotiation, String message) {
    }

    default void phaseChanged(DuelMatch match, DuelPhase from, DuelPhase to) {
    }

    /** Both duelists are in the lobby (the warmup-start broadcast, P6). */
    default void warmupStarted(DuelMatch match, int warmupSeconds) {
    }

    /** {@code KIT_CHOICE}: a duelist picked (or swapped to) a kit. */
    default void kitChosen(DuelMatch match, DuelSide side, int kitId) {
    }

    default void fightStarted(DuelMatch match) {
    }

    /** {@code /duel draw} by one side; the other has until {@code expiresAtTick}. */
    default void drawOffered(DuelMatch match, DuelSide side, long expiresAtTick) {
    }

    /** The one result (the end-once guard has passed). */
    default void matchEnded(DuelMatch match, DuelResult result) {
    }

    /** The API's answer: PAID, HELD (pending review) or REFUNDED; null while it can't be reached (spooled). */
    default void settled(DuelMatch match, Settlement settlement) {
    }

    /** Gear restored and the duelists sent out; the match is gone. */
    default void released(DuelMatch match) {
    }
}
