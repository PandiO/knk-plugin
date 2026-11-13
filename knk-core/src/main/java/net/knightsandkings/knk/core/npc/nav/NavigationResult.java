package net.knightsandkings.knk.core.npc.nav;

import java.util.Objects;

/** How a navigation order ended, for the caller's behavior to act on (DESIGN §6 failure policy). */
public sealed interface NavigationResult {

    /** The destination was reached. */
    record Arrived() implements NavigationResult {
    }

    /**
     * No allowed route now (a closed gate, a denied region): the NPC walks as far as the rules allow and waits at
     * the blocking element; the order resumes on its own when the element opens. Behavior may act on it instead
     * (BEHAVIOR_MODEL §8, §17 gate confrontation) by giving another order.
     *
     * @param blockingElement what blocks (a gate, a domain's region), or null when the router names nothing
     */
    record Blocked(String reason, BlockingElement blockingElement) implements NavigationResult {
        public Blocked {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The order cannot be carried out (no road connects, bounded retries used up). */
    record Failed(String reason) implements NavigationResult {
        public Failed {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The caller or a removal ended the order. */
    record Cancelled(String reason) implements NavigationResult {
        public Cancelled {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
