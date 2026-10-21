package net.knightsandkings.knk.core.duel;

/** The two duelists of a negotiation or match: the inviter is the challenger (DESIGN §4.3). */
public enum DuelSide {
    CHALLENGER,
    OPPONENT;

    public DuelSide other() {
        return this == CHALLENGER ? OPPONENT : CHALLENGER;
    }
}
