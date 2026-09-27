package net.knightsandkings.knk.core.messaging;

import java.util.Objects;
import java.util.UUID;

/**
 * One end of a private message (docs/specs/private-messages/DESIGN.md §3.3.3): a player, by UUID
 * (so a reply target survives a rename or relog), or the server console ({@link #CONSOLE}, whose
 * {@code uuid} is null).
 */
public record ParticipantId(UUID uuid) {

    public static final ParticipantId CONSOLE = new ParticipantId(null);

    public static ParticipantId player(UUID uuid) {
        return new ParticipantId(Objects.requireNonNull(uuid, "uuid must not be null"));
    }

    public boolean isConsole() {
        return uuid == null;
    }

    @Override
    public String toString() {
        return isConsole() ? "CONSOLE" : uuid.toString();
    }
}
