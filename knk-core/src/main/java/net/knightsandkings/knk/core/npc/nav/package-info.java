/**
 * Shared NPC navigation (PandiO/knk-workspace#358; npc-platform IMPLEMENTATION_PLAN, BEHAVIOR_MODEL §2 NAV-01..18):
 * K&K plans routes, an engine-specific mover moves the NPC. Bukkit-free and engine-neutral (developer Q1): the
 * engine is reached only through ports such as {@link net.knightsandkings.knk.core.npc.nav.NpcMover}; the Paper and
 * Citizens adapters live in {@code knk-paper}'s {@code paper.npc.citizens}.
 */
package net.knightsandkings.knk.core.npc.nav;
