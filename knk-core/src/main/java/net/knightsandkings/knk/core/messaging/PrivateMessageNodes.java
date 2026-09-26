package net.knightsandkings.knk.core.messaging;

/**
 * In-house permission nodes used by private messaging (docs/specs/private-messages/DESIGN.md §3.5).
 * Sending and replying need none.
 */
public final class PrivateMessageNodes {
    private PrivateMessageNodes() {}

    /** Receive the social spy feed and use /socialspy. */
    public static final String SOCIAL_SPY = "knk.socialspy";
    /** Your PMs are hidden from spies without this node; you see such PMs yourself. */
    public static final String SOCIAL_SPY_EXEMPT = "knk.socialspy.exempt";
    /** No PM rate limit. */
    public static final String BYPASS_RATE_LIMIT = "knk.msg.bypass.ratelimit";
    /** Existing /freeze node: frozen players may still message holders of it. */
    public static final String FREEZE = "knk.freeze";
}
