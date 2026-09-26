package net.knightsandkings.knk.core.domain.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One private message as it goes to knk-web-api's server-side log (KNG-18 Phase 3,
 * docs/specs/private-messages/DESIGN.md §3.1). {@code clientMessageId} is generated once, when the
 * message is sent, and re-used on every retry, so the API stores it once however often the batch
 * is re-sent. A null UUID means the console.
 */
public record PrivateMessageLogEntry(
        UUID clientMessageId,
        Instant sentAt,
        UUID senderUuid,
        String senderName,
        UUID recipientUuid,
        String recipientName,
        String content,
        Outcome outcome,
        boolean viaReply
) {
    /** The API's PrivateMessageOutcome. */
    public enum Outcome { DELIVERED, BLOCKED_IGNORED, BLOCKED_RATE_LIMITED, BLOCKED_FROZEN }

    public PrivateMessageLogEntry {
        Objects.requireNonNull(clientMessageId, "clientMessageId must not be null");
        Objects.requireNonNull(sentAt, "sentAt must not be null");
        Objects.requireNonNull(senderName, "senderName must not be null");
        Objects.requireNonNull(recipientName, "recipientName must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        content = content == null ? "" : content;
    }
}
