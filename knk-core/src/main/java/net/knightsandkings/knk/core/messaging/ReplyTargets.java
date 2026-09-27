package net.knightsandkings.knk.core.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who {@code /reply} goes to (docs/specs/private-messages/DESIGN.md §3.3.3). Every delivered
 * message links both participants to each other (reciprocal, as v1/v3); a blocked message links
 * nobody, because the caller simply never records it.
 * <p>
 * Vanish safety: a link remembers whether the partner messaged this participant themselves
 * ({@code initiatedByPartner}) and whether this participant could see the partner when the link
 * was written. A partner who is now hidden from the replier is only reachable when they started
 * the conversation (they chose to reveal themselves), and a partner who went offline is only named
 * ("X is no longer online.") when the replier could see them; otherwise the caller prints the same
 * generic "not found" line as for an unknown name.
 * <p>
 * {@code initiatedByPartner} stays set while the replier keeps answering the same partner, so a
 * back-and-forth with a vanished staff member who opened the conversation doesn't break after the
 * first reply.
 * <p>
 * Thread-safe (concurrent map); in practice only the main thread writes.
 */
public final class ReplyTargets {

    /** Where a participant's {@code /reply} goes. */
    public record Link(ParticipantId partner, String partnerName, boolean initiatedByPartner,
                       boolean partnerWasVisible, Instant at) {
    }

    /** Whether a reply partner is reachable right now, from the replier's point of view. */
    public enum Presence {
        /** Online and the replier can see them (the console always is). */
        ONLINE_VISIBLE,
        /** Online, but vanished for the replier. */
        ONLINE_HIDDEN,
        OFFLINE
    }

    @FunctionalInterface
    public interface PresenceLookup {
        Presence of(ParticipantId replier, ParticipantId partner);
    }

    public enum Outcome {
        /** Send to {@link Resolution#link()}'s partner. */
        OK,
        /** No link: "Nobody to reply to." */
        NO_TARGET,
        /** Unreachable and must not be revealed: the generic "No online player found named …" line. */
        NOT_FOUND,
        /** Offline and the replier could see them: "&lt;name&gt; is no longer online." */
        PARTNER_OFFLINE
    }

    /** {@code link} is null only for {@link Outcome#NO_TARGET}. */
    public record Resolution(Outcome outcome, Link link) {
    }

    private final Map<ParticipantId, Link> links = new ConcurrentHashMap<>();
    private final Clock clock;

    public ReplyTargets(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Records a delivered message.
     *
     * @param senderCouldSeeRecipient    whether the sender could see the recipient when sending
     *                                   (always true for a console sender)
     * @param recipientCouldSeeSender    whether the recipient can see the sender (always true for a
     *                                   console recipient or a console sender)
     */
    public void recordDelivered(ParticipantId sender, String senderName, ParticipantId recipient, String recipientName,
                                boolean senderCouldSeeRecipient, boolean recipientCouldSeeSender) {
        Objects.requireNonNull(sender, "sender must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");
        Instant now = clock.instant();

        Link previous = links.get(sender);
        boolean stillPartnerInitiated = previous != null && previous.partner().equals(recipient) && previous.initiatedByPartner();
        links.put(sender, new Link(recipient, recipientName, stillPartnerInitiated, senderCouldSeeRecipient, now));
        links.put(recipient, new Link(sender, senderName, true, recipientCouldSeeSender, now));
    }

    public Optional<Link> linkOf(ParticipantId participant) {
        return Optional.ofNullable(links.get(participant));
    }

    public Resolution resolve(ParticipantId replier, PresenceLookup presence) {
        Link link = links.get(replier);
        if (link == null) {
            return new Resolution(Outcome.NO_TARGET, null);
        }
        Presence now = link.partner().isConsole() ? Presence.ONLINE_VISIBLE : presence.of(replier, link.partner());
        return switch (now) {
            case ONLINE_VISIBLE -> new Resolution(Outcome.OK, link);
            case ONLINE_HIDDEN -> new Resolution(link.initiatedByPartner() ? Outcome.OK : Outcome.NOT_FOUND, link);
            case OFFLINE -> new Resolution(link.partnerWasVisible() ? Outcome.PARTNER_OFFLINE : Outcome.NOT_FOUND, link);
        };
    }

    /**
     * Drops {@code participant}'s own link (they quit, so their "last conversation" resets). Links
     * pointing at them are kept, so their partners can still {@code /r} once they are back.
     */
    public void forget(ParticipantId participant) {
        links.remove(participant);
    }
}
