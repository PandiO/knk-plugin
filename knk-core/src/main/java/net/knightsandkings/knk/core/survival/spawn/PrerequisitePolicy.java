package net.knightsandkings.knk.core.survival.spawn;

/** How a spawnzone's objective prerequisites combine (DESIGN §6 "explicit ALL/ANY policy"). */
public enum PrerequisitePolicy {
    /** Every listed objective must be NPC-held. */
    ALL,
    /** At least one listed objective must be NPC-held. */
    ANY
}
