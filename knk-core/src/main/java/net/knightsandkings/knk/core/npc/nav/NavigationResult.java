package net.knightsandkings.knk.core.npc.nav;

import java.util.Objects;

/** How a navigation order ended, for the caller's behavior to act on (DESIGN §6 failure policy). */
public sealed interface NavigationResult {

    /** The destination was reached. */
    record Arrived() implements NavigationResult {
    }

    /**
     * No allowed route now (a closed gate, a denied region): the NPC waits at the last allowed point; the order
     * resumes on its own when the blocking element opens.
     *
     * @param blockingElement what blocks, as an id or name for logs and behavior (a gate, a region), or null
     */
    record Blocked(String reason, String blockingElement) implements NavigationResult {
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
