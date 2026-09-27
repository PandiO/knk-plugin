package net.knightsandkings.knk.core.messaging;

/**
 * Who sees a private message in the social spy feed (docs/specs/private-messages/DESIGN.md §3.3.4,
 * developer decision 2026-09-26: staff + owners, owners' PMs hidden from staff):
 * <ul>
 *   <li>only holders of {@code knk.socialspy} with their toggle on;</li>
 *   <li>never the two participants;</li>
 *   <li>a PM where either participant holds {@code knk.socialspy.exempt} only reaches spies who
 *   hold it themselves.</li>
 * </ul>
 * The flags are cached by the caller, so this never does permission I/O.
 */
public final class SpyRules {
    private SpyRules() {}

    /** A potential spy: {@code holdsSpyNode} = {@code knk.socialspy}, {@code enabled} = their /socialspy toggle. */
    public record Spy(ParticipantId id, boolean holdsSpyNode, boolean enabled, boolean exempt) {
    }

    /** A PM participant; {@code exempt} = holds {@code knk.socialspy.exempt}. */
    public record Participant(ParticipantId id, boolean exempt) {
    }

    public static boolean shouldSee(Spy spy, Participant sender, Participant recipient) {
        if (!spy.holdsSpyNode() || !spy.enabled()) {
            return false;
        }
        if (spy.id().equals(sender.id()) || spy.id().equals(recipient.id())) {
            return false;
        }
        boolean exemptMessage = sender.exempt() || recipient.exempt();
        return !exemptMessage || spy.exempt();
    }
}
