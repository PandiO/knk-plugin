package net.knightsandkings.knk.core.statistics;

/**
 * What kind of creature a non-player combatant is, as far as the combat statistics care (Siege Survival link S3,
 * PandiO/knk-workspace#41): an ordinary world creature counts for PvE kills, damage and death causes; a Survival
 * match NPC (a mob tagged as an actor of a match's NPC team) is a match event and stays out of all of them. The
 * Bukkit side classifies the entity; this enum and {@link CombatStatisticsRules} hold the rule.
 */
public enum CreatureClass {
    /** Any creature that is not a match actor (also farmed ones; the spawn-reason filter decides those). */
    ORDINARY,
    /** A mob acting for the NPC team of a Survival match. */
    MATCH_NPC;

    public static CreatureClass of(boolean matchNpc) {
        return matchNpc ? MATCH_NPC : ORDINARY;
    }
}
