package net.knightsandkings.knk.core.minigame;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import net.knightsandkings.knk.core.teleport.TeleportKind;

/**
 * A teleport-engine request as a minigame judges it (teleport DESIGN §4 D9), without Bukkit types.
 *
 * @param subject     the player who would move
 * @param subjectName their name, for messages
 * @param kind        the kind of teleport
 * @param movesSelf   true when the subject asked for the move themselves
 * @param visited     the other player of a {@code /tpa}-style request, or null
 * @param visitedName their name, or null
 * @param authority   whether the request carries a node (the subject's own, or staff's)
 */
public record MinigameTeleportAttempt(UUID subject, String subjectName, TeleportKind kind, boolean movesSelf,
                                      UUID visited, String visitedName, Predicate<String> authority) {

    public MinigameTeleportAttempt {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(kind, "kind");
        authority = authority == null ? node -> false : authority;
    }

    public boolean hasBypass(String node) {
        return authority.test(node);
    }
}
