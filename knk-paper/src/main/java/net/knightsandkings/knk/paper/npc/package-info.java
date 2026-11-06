/**
 * Paper adapters of the NPC platform's attack-rate and movement-speed mechanics (NPC platform DESIGN §5b,
 * PandiO/knk-workspace#146; first consumer Siege Survival, #41 link S5). The pure resolution lives in
 * {@code knk-core/.../core/npc}; these classes only drive a vanilla mob: a melee goal that attacks when the resolved
 * cooldown allows, a bow goal honouring the resolved draw and cooldown, an arrow-damage hook, and the speed attribute
 * modifier. Nothing here runs for a mob that no consumer registered.
 */
package net.knightsandkings.knk.paper.npc;
