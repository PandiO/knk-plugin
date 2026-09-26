package net.knightsandkings.knk.core.messaging;

import java.util.Objects;
import java.util.Optional;

/**
 * Drops a private message when the recipient ignores the sender (KNG-18 Phase 2,
 * docs/specs/private-messages/DESIGN.md §3.3.5). The drop is silent: the caller still shows the
 * sender their normal echo (§4 D5) and tells spies it was ignored, so {@link #MESSAGE} is never
 * shown to the sender. Holders of {@code knk.msg.bypass.ignore} and the console always get through.
 * <p>
 * The lookup reads the cached ignore lists, so the gate stays I/O-free.
 */
public final class IgnoreGate implements PrivateMessageGate {

    /** For logs only - the sender isn't told. */
    public static final String MESSAGE = "The recipient ignores you.";

    @FunctionalInterface
    public interface Lookup {
        /** Whether {@code recipient} ignores {@code sender}; false while the recipient's list is loading. */
        boolean ignores(ParticipantId recipient, ParticipantId sender);

        Lookup NONE = (recipient, sender) -> false;
    }

    private final Lookup lookup;

    public IgnoreGate(Lookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup must not be null");
    }

    @Override
    public Optional<Denial> check(SendAttempt attempt) {
        if (attempt.sender().isConsole() || attempt.recipient().isConsole()
                || attempt.senderNodes().contains(PrivateMessageNodes.BYPASS_IGNORE)
                || !lookup.ignores(attempt.recipient(), attempt.sender())) {
            return Optional.empty();
        }
        return Optional.of(new Denial(Reason.IGNORED, MESSAGE));
    }
}
