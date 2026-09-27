package net.knightsandkings.knk.core.messaging;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One check a private message must pass before it is delivered (docs/specs/private-messages/
 * DESIGN.md §3.3.6). Gates run in order and the first denial wins: frozen, rate limit, then ignore
 * ({@link IgnoreGate}, whose denial is silent). A later mute is one more gate - nothing else changes.
 * <p>
 * Gates are pure: whatever permission nodes they need are resolved by the caller beforehand and
 * passed in {@link SendAttempt#senderNodes()} / {@link SendAttempt#recipientNodes()} (nodes from
 * {@link PrivateMessageNodes}), so a gate never does I/O.
 */
@FunctionalInterface
public interface PrivateMessageGate {

    Optional<Denial> check(SendAttempt attempt);

    enum Reason { FROZEN, RATE_LIMITED, IGNORED }

    /** Why a message was refused; {@code message} is shown to the sender (plain text, error colour). */
    record Denial(Reason reason, String message) {
    }

    /**
     * @param senderFrozen   whether the sender is admin-frozen (/freeze)
     * @param senderNodes    the {@link PrivateMessageNodes} the sender holds (checked by the caller)
     * @param recipientNodes the {@link PrivateMessageNodes} the recipient holds (checked by the caller)
     */
    record SendAttempt(ParticipantId sender, ParticipantId recipient, String text, boolean viaReply,
                       boolean senderFrozen, Set<String> senderNodes, Set<String> recipientNodes) {
        public SendAttempt {
            senderNodes = senderNodes == null ? Set.of() : Set.copyOf(senderNodes);
            recipientNodes = recipientNodes == null ? Set.of() : Set.copyOf(recipientNodes);
        }
    }

    static Optional<Denial> firstDenial(List<? extends PrivateMessageGate> gates, SendAttempt attempt) {
        for (PrivateMessageGate gate : gates) {
            Optional<Denial> denial = gate.check(attempt);
            if (denial.isPresent()) {
                return denial;
            }
        }
        return Optional.empty();
    }
}
