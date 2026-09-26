package net.knightsandkings.knk.paper.user;

import java.time.Instant;
import java.util.UUID;

/**
 * Moderation log of private messages (docs/specs/private-messages/DESIGN.md §3.3.8). Phase 1 ships
 * the plugin-local file sink ({@link LocalFilePrivateMessageLog}); the knk-web-api sink (Phase 3)
 * plugs in behind the same interface once the plugin authenticates to the API.
 * <p>
 * {@link #log} is called on the main thread and must never block or throw: sinks hand the entry
 * to their own thread.
 */
public interface PrivateMessageLogger {

    /** Matches the server-side log's PrivateMessageOutcome (Phase 3). */
    enum Outcome { DELIVERED, BLOCKED_IGNORED, BLOCKED_RATE_LIMITED, BLOCKED_FROZEN }

    /** A null UUID means the console. */
    record Entry(Instant sentAt, Outcome outcome, String senderName, UUID senderUuid,
                 String recipientName, UUID recipientUuid, String text, boolean viaReply) {
    }

    /** Logs nothing (log.local-enabled: false). */
    PrivateMessageLogger NONE = entry -> { };

    void log(Entry entry);

    /** Flushes and stops the sink (plugin disable). */
    default void close() {
    }
}
