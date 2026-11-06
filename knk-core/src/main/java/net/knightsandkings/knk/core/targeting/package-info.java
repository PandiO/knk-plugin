/**
 * Shared NPC target scoring, commitment and the goal state machine (NPC platform DESIGN §5a, PandiO/knk-workspace#146;
 * first consumer Siege Survival, #41 link S5). Bukkit-free: the consumer supplies candidates with their eligibility,
 * positions and the time; the classes decide which target an actor pursues and what it should do about it.
 */
package net.knightsandkings.knk.core.targeting;
