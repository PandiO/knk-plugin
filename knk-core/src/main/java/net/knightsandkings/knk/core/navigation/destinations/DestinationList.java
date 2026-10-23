package net.knightsandkings.knk.core.navigation.destinations;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The engine's answer for one player (destination engine DESIGN §3): the destinations they may navigate to now,
 * in the order they are offered - or none, with the reason they are told. Immutable, so tab completion is a
 * filter over an array and the list can be cached per (scope, world, model version) (§11).
 *
 * @param scopeId      the scope that decided
 * @param displayName  how the scope calls the list ("this match", "the world")
 * @param candidates   the navigable candidates, ordered
 * @param otherWorld   candidates whose place is in another world than the player: not navigable, hidden from
 *                     completion, but a name among them still resolves to today's "is in another world" (E11)
 * @param reason       why nothing is navigable, when {@code candidates} is empty for a policy reason; null otherwise
 *                     (an empty catalogue has no reason)
 * @param degraded     some places are missing because the model has none (limited mode, §7.4) or is stale (§7.3)
 * @param degradedText the note that goes with {@code degraded}; null otherwise
 * @param modelVersion the model version the list was built from
 */
public record DestinationList(String scopeId, String displayName, List<Candidate> candidates, List<Candidate> otherWorld,
                              String reason, boolean degraded, String degradedText, long modelVersion) {

    public DestinationList {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(displayName, "displayName");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        otherWorld = otherWorld == null ? List.of() : List.copyOf(otherWorld);
    }

    /** Nothing navigable, for {@code reason}. */
    public static DestinationList empty(String scopeId, String displayName, String reason, long modelVersion) {
        return new DestinationList(scopeId, displayName, List.of(), List.of(), reason, false, null, modelVersion);
    }

    public boolean isEmpty() {
        return candidates.isEmpty();
    }

    /** Empty for a policy reason (loading, frozen, no permission, in a siege ...), not just an empty catalogue. */
    public boolean refused() {
        return reason != null;
    }

    /** The navigable candidate with {@code key}, if listed. */
    public Optional<Candidate> find(String key) {
        for (Candidate candidate : candidates) {
            if (candidate.key().equals(key)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public boolean contains(String key) {
        return find(key).isPresent();
    }

    /** The same list marked degraded with {@code text} (§7.4). */
    public DestinationList degraded(String text) {
        return new DestinationList(scopeId, displayName, candidates, otherWorld, reason, true, text, modelVersion);
    }
}
