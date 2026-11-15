package net.knightsandkings.knk.core.npc.nav;

/** Where an NPC's navigation is (metrics label values are the lower-case names). */
public enum NpcNavState {
    /** No destination. */
    IDLE,
    /** Waiting for a planning slot. */
    QUEUED,
    /** A route or stretch is being planned. */
    PLANNING,
    /** Walking. */
    MOVING,
    /** Waiting: no allowed route, a closed gate, a denied region, or a retry delay (NAV-03). */
    WAITING,
    /** The NPC's chunk is unloaded (NAV-11); resumes when it loads. */
    PAUSED,
    /** Travelling without an entity outside loaded areas (NAV-12, Phase 7b). */
    SIMULATED
}
