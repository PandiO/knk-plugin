package net.knightsandkings.knk.core.messaging;

import java.util.Optional;

/**
 * A frozen player may only message holders of {@code knk.freeze}, so they can answer the staff who
 * froze them (v1's design intent; docs/specs/private-messages/DESIGN.md §4 D10). Messaging the
 * console is always allowed.
 */
public final class FrozenGate implements PrivateMessageGate {

    public static final String MESSAGE = "You can only message staff while frozen.";

    @Override
    public Optional<Denial> check(SendAttempt attempt) {
        if (!attempt.senderFrozen() || attempt.recipient().isConsole()
                || attempt.recipientNodes().contains(PrivateMessageNodes.FREEZE)) {
            return Optional.empty();
        }
        return Optional.of(new Denial(Reason.FROZEN, MESSAGE));
    }
}
