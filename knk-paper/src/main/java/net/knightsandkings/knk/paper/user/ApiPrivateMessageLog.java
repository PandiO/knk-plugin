package net.knightsandkings.knk.paper.user;

import java.util.Objects;
import java.util.UUID;

import net.knightsandkings.knk.core.domain.messaging.PrivateMessageLogEntry;
import net.knightsandkings.knk.core.messaging.PrivateMessageLogShipper;

/**
 * The knk-web-api sink of the PM log (KNG-18 Phase 3, DESIGN.md §3.3.8): gives each message its
 * client message id and hands it to {@link PrivateMessageLogShipper}, which batches, retries and
 * spools on its own thread. Enabled by {@code private-messages.log.api-enabled}.
 */
public final class ApiPrivateMessageLog implements PrivateMessageLogger {

    private final PrivateMessageLogShipper shipper;

    public ApiPrivateMessageLog(PrivateMessageLogShipper shipper) {
        this.shipper = Objects.requireNonNull(shipper, "shipper must not be null");
    }

    public void start() {
        shipper.start();
    }

    @Override
    public void log(Entry entry) {
        shipper.submit(toLogEntry(UUID.randomUUID(), entry));
    }

    /** Messages waiting to be sent (/knk health). */
    public int queueDepth() {
        return shipper.queueDepth();
    }

    @Override
    public void close() {
        shipper.close();
    }

    static PrivateMessageLogEntry toLogEntry(UUID clientMessageId, Entry entry) {
        return new PrivateMessageLogEntry(clientMessageId, entry.sentAt(), entry.senderUuid(), entry.senderName(),
                entry.recipientUuid(), entry.recipientName(), entry.text(), outcome(entry.outcome()), entry.viaReply());
    }

    private static PrivateMessageLogEntry.Outcome outcome(Outcome outcome) {
        return switch (outcome) {
            case DELIVERED -> PrivateMessageLogEntry.Outcome.DELIVERED;
            case BLOCKED_IGNORED -> PrivateMessageLogEntry.Outcome.BLOCKED_IGNORED;
            case BLOCKED_RATE_LIMITED -> PrivateMessageLogEntry.Outcome.BLOCKED_RATE_LIMITED;
            case BLOCKED_FROZEN -> PrivateMessageLogEntry.Outcome.BLOCKED_FROZEN;
        };
    }
}
