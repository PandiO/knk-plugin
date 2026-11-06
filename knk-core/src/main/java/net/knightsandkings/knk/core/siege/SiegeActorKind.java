package net.knightsandkings.knk.core.siege;

/**
 * Who is acting in a match (Siege Survival link S3, PandiO/knk-workspace#41): the NPC-as-actor vocabulary of
 * the NPC platform design (§5a, §7). A player and an NPC with a team both count towards capture presence and may
 * damage the gates of the opposing team; only a player is a roster member, earns rewards or appears in statistics.
 */
public enum SiegeActorKind {
    /** A match member (a Bukkit player on the roster). */
    PLAYER,
    /** A non-player match actor: a mob tagged with the NPC team of a Survival match. */
    NPC
}
