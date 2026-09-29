package net.knightsandkings.knk.core.teleport;

import java.util.Objects;

/**
 * One staff teleport as the web API's audit log records it (docs/specs/teleport/DESIGN.md §3.10,
 * {@code POST /api/users/{id}/teleport-audit}). Knk user ids, not Minecraft UUIDs.
 *
 * @param kind          what started it (only {@link TeleportKind#STAFF} is audited today)
 * @param actorUserId   the staff member, sent as the acting-user header; null for the console or an
 *                      account the plugin couldn't resolve (the entry is then logged without an actor)
 * @param subjectUserId the player who moved
 * @param visitedUserId the player whose location was the destination, if any
 * @param from          where the subject was
 * @param to            where they landed
 * @param silent        the moved/visited player wasn't told
 * @param reason        optional staff reason, null when none was given
 * @param console       started from the console rather than by a player
 */
public record TeleportAudit(
    TeleportKind kind,
    Integer actorUserId,
    int subjectUserId,
    Integer visitedUserId,
    Point from,
    Point to,
    boolean silent,
    String reason,
    boolean console
) {
    public TeleportAudit {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
    }

    /** A position in a named world. */
    public record Point(String world, double x, double y, double z) {
        public Point {
            Objects.requireNonNull(world, "world must not be null");
        }
    }

    /**
     * The user the entry is filed under (DESIGN §3.10): for "{@code /tp <player>}" - the actor moving
     * themselves to someone - the visited player, so it shows on the profile of the one who was
     * visited; for every other form (another player moved, or the actor to coordinates) the subject.
     */
    public int targetUserId() {
        boolean actorMovedThemselves = actorUserId != null && actorUserId == subjectUserId;
        return actorMovedThemselves && visitedUserId != null ? visitedUserId : subjectUserId;
    }
}
