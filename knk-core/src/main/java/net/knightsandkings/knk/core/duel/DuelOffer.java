package net.knightsandkings.knk.core.duel;

import java.util.Objects;

/**
 * One immutable offer (DESIGN §4.4, §6.3).
 *
 * @param offerId       fresh for every offer, unique within the {@link DuelOfferBook}; a chat button
 *                      carries it, and only the current offer's id can be accepted (V5)
 * @param number        1-based position in the negotiation (the "version": offer {@code number} of
 *                      {@code maxOffers})
 * @param expiresAtTick the turn ends at this server tick
 * @param last          the last allowed offer: it can only be accepted or declined (D2)
 */
public record DuelOffer(long offerId, int number, DuelSide author, StakeContract contract, long expiresAtTick, boolean last) {
    public DuelOffer {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(contract, "contract");
    }

    /** The side that answers this offer. */
    public DuelSide receiver() {
        return author.other();
    }

    public boolean isExpired(long nowTick) {
        return nowTick >= expiresAtTick;
    }

    public boolean canBeCountered() {
        return !last;
    }
}
