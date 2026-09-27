package net.knightsandkings.knk.paper.user;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Moderation log of private messages (docs/specs/private-messages/DESIGN.md §3.3.8): the
 * plugin-local file sink ({@link LocalFilePrivateMessageLog}, Phase 1) and the knk-web-api sink
 * ({@link ApiPrivateMessageLog}, Phase 3), combined with {@link #all}.
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

    /** Logs nothing (both sinks off). */
    PrivateMessageLogger NONE = entry -> { };

    /** Logs to every sink in order; one sink failing doesn't stop the others. */
    static PrivateMessageLogger all(List<PrivateMessageLogger> sinks) {
        List<PrivateMessageLogger> copy = List.copyOf(sinks);
        if (copy.isEmpty()) {
            return NONE;
        }
        if (copy.size() == 1) {
            return copy.get(0);
        }
        return new PrivateMessageLogger() {
            @Override
            public void log(Entry entry) {
                for (PrivateMessageLogger sink : copy) {
                    try {
                        sink.log(entry);
                    } catch (RuntimeException e) {
                        Logger.getLogger(PrivateMessageLogger.class.getName())
                                .log(Level.WARNING, "Private message log sink failed", e);
                    }
                }
            }

            @Override
            public void close() {
                copy.forEach(PrivateMessageLogger::close);
            }
        };
    }

    void log(Entry entry);

    /** Flushes and stops the sink (plugin disable). */
    default void close() {
    }
}
