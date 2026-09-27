package net.knightsandkings.knk.core.messaging;

/**
 * In-house permission nodes used by private messaging (docs/specs/private-messages/DESIGN.md §3.5).
 * Sending, replying and ignoring need none.
 */
public final class PrivateMessageNodes {
    private PrivateMessageNodes() {}

    /** Receive the social spy feed and use /socialspy. */
    public static final String SOCIAL_SPY = "knk.socialspy";
    /** Your PMs are hidden from spies without this node; you see such PMs yourself. */
    public static final String SOCIAL_SPY_EXEMPT = "knk.socialspy.exempt";
    /** No PM rate limit. */
    public static final String BYPASS_RATE_LIMIT = "knk.msg.bypass.ratelimit";
    /** Your PMs reach players who ignore you (staff). */
    public static final String BYPASS_IGNORE = "knk.msg.bypass.ignore";
    /** You can't be ignored (staff) - checked by knk-web-api on /ignore, pre-checked in-game. */
    public static final String UNIGNORABLE = "knk.msg.unignorable";
    /** Existing /freeze node: frozen players may still message holders of it. */
    public static final String FREEZE = "knk.freeze";
}
