package net.knightsandkings.knk.core.duel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One invitation and its offers (DESIGN §4.4). Owned and changed only by {@link DuelOfferBook}; callers
 * get a live, read-only view.
 */
public final class DuelNegotiation {

    public enum State {
        /** The current offer waits for its receiver. */
        AWAITING_RESPONSE,
        /** An offer was accepted; lock-in is running (DESIGN §6.5). */
        LOCKING,
        CLOSED
    }

    private final long negotiationId;
    private final UUID inviter;
    private final UUID invitee;
    private final DuelArenaSettings settings;
    private final List<DuelOffer> offers = new ArrayList<>();
    private State state = State.AWAITING_RESPONSE;

    DuelNegotiation(long negotiationId, UUID inviter, UUID invitee, DuelArenaSettings settings) {
        this.negotiationId = negotiationId;
        this.inviter = Objects.requireNonNull(inviter, "inviter");
        this.invitee = Objects.requireNonNull(invitee, "invitee");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public long negotiationId() {
        return negotiationId;
    }

    public UUID inviter() {
        return inviter;
    }

    public UUID invitee() {
        return invitee;
    }

    public int arenaId() {
        return settings.arenaId();
    }

    /** The arena's settings as they were when the invitation was sent. */
    public DuelArenaSettings settings() {
        return settings;
    }

    public State state() {
        return state;
    }

    public List<DuelOffer> offers() {
        return List.copyOf(offers);
    }

    public DuelOffer currentOffer() {
        return offers.isEmpty() ? null : offers.getLast();
    }

    /** "Offer n of max" — the number of offers made so far. */
    public int offerCount() {
        return offers.size();
    }

    public UUID playerOf(DuelSide side) {
        return side == DuelSide.CHALLENGER ? inviter : invitee;
    }

    /** The player's side, or null when they aren't in this negotiation. */
    public DuelSide sideOf(UUID player) {
        if (inviter.equals(player)) return DuelSide.CHALLENGER;
        if (invitee.equals(player)) return DuelSide.OPPONENT;
        return null;
    }

    public boolean involves(UUID player) {
        return sideOf(player) != null;
    }

    /** The player whose turn it is to answer, or null when closed. */
    public UUID awaitedPlayer() {
        DuelOffer current = currentOffer();
        return current == null || state != State.AWAITING_RESPONSE ? null : playerOf(current.receiver());
    }

    void addOffer(DuelOffer offer) {
        offers.add(offer);
    }

    void setState(State state) {
        this.state = state;
    }
}
