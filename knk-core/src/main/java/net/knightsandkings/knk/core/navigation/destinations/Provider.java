package net.knightsandkings.knk.core.navigation.destinations;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A source of {@link Candidate}s (destination engine DESIGN §3): the world catalogue, a running siege match's
 * objectives, the configured compass targets, later arena duels, homes, quests. A provider answers from memory
 * (§7.1); the engine calls it on the main thread. A provider that throws is logged and skipped, the rest of the
 * list is still served (§7.4).
 */
public interface Provider {

    /** Stable id, recorded as each candidate's {@link Candidate#source()}. */
    String id();

    /** The candidates for this player, in any order: the scope and the candidates' priorities order the list. */
    List<Candidate> candidates(NavigationContext ctx);

    /** A provider from a function. */
    static Provider of(String id, Function<NavigationContext, List<Candidate>> candidates) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(candidates, "candidates");
        return new Provider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<Candidate> candidates(NavigationContext ctx) {
                return candidates.apply(ctx);
            }

            @Override
            public String toString() {
                return "Provider " + id;
            }
        };
    }

    /** A provider with a fixed list, for tests and constants. */
    static Provider fixed(String id, List<Candidate> candidates) {
        List<Candidate> copy = List.copyOf(candidates);
        return of(id, ctx -> copy);
    }
}
