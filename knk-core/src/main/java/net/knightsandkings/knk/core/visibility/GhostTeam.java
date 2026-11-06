package net.knightsandkings.knk.core.visibility;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The team a ghost belongs to (Siege Survival DESIGN §3b, S20): the living and ghost members of this team are the
 * only viewers who see the ghost (translucent); nobody else does. The membership test is the team's own (for a
 * Survival match: the roster's team of a player), so a ghost follows roster changes without re-marking.
 *
 * @param key        a stable name for logs and equality, e.g. {@code siege:<lobbyId>:<teamId>}
 * @param membership whether a player (by UUID) is a living or ghost member of this team
 */
public record GhostTeam(String key, Predicate<UUID> membership) {

    public GhostTeam {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(membership, "membership");
    }

    /** Whether this viewer is on the team and so sees its ghosts. */
    public boolean includes(UUID viewerId) {
        return viewerId != null && membership.test(viewerId);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GhostTeam other && key.equals(other.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "GhostTeam[" + key + "]";
    }
}
