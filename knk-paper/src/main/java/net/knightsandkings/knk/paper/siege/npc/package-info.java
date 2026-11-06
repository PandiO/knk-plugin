/**
 * Match NPCs as actors (Siege Survival link S3, PandiO/knk-workspace#41; NPC platform DESIGN §5a/§7): a Bukkit mob
 * registered with the NPC team of a Survival match counts towards objective capture with its weight
 * ({@link net.knightsandkings.knk.paper.siege.npc.MatchActorRegistry}) and may damage the doors of the opposing team
 * ({@link net.knightsandkings.knk.paper.siege.npc.NpcGateDamage}). The Survival runtime (link S5) registers and
 * forgets the actors; nothing here spawns or drives a mob.
 */
package net.knightsandkings.knk.paper.siege.npc;
