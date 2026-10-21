package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.duel.DuelEndReason;
import net.knightsandkings.knk.core.duel.DuelOfferBook;

/** Player-facing wording of the runtime's refusals and results (P5 may replace it with richer chat). */
public final class DuelTexts {

    private DuelTexts() {
    }

    /** Why an accept (or another offer-book call) didn't go through. */
    public static String offerStatus(DuelOfferBook.Status status) {
        return switch (status) {
            case OK -> "Done.";
            case SELF_INVITE -> "You can't duel yourself.";
            case INVITES_DISABLED -> "That player doesn't accept duel invites.";
            case ALREADY_INVITING -> "You already have an open duel invite.";
            case PAIR_ALREADY_NEGOTIATING -> "You two are already negotiating a duel.";
            case TOO_MANY_INCOMING -> "That player has too many open duel invites.";
            case ON_COOLDOWN -> "Wait a moment before sending another duel invite.";
            case PLAYER_BUSY -> "One of you is already locking in another duel.";
            case UNKNOWN_NEGOTIATION -> "That duel offer is gone.";
            case STALE_OFFER -> "That offer is no longer current.";
            case NOT_YOUR_TURN -> "It isn't your turn to answer.";
            case OFFER_EXPIRED -> "That offer expired.";
            case NO_COUNTERS_LEFT -> "This is the last offer: accept or decline it.";
            case INVALID_CONTRACT -> "That offer breaks the arena's rules.";
            case NOT_AWAITING_RESPONSE -> "That duel is already being locked in.";
        };
    }

    /** A result without a winner. */
    public static String noWinner(DuelEndReason reason) {
        return switch (reason) {
            case TIME_LIMIT -> "Time is up: the duel is a draw.";
            case AGREED_DRAW -> "You agreed a draw.";
            case SIMULTANEOUS_DEATH -> "You both fell at once: the duel is a draw.";
            case TELEPORT_FAILED -> "The duel was cancelled: a teleport didn't happen.";
            case PLAYER_LEFT_LOBBY -> "The duel was cancelled: a duelist left.";
            case NO_BATTLEGROUND -> "The duel was cancelled: no battleground came free in time.";
            case ADMIN_CANCELLED -> "Staff cancelled the duel.";
            case SERVER_RESTART -> "The duel was stopped by a server restart.";
            case API_OUTAGE -> "The duel was stopped: the server's services can't be reached.";
            case LOCK_REFUSED, WIN, FORFEIT -> "The duel is over.";
        };
    }
}
