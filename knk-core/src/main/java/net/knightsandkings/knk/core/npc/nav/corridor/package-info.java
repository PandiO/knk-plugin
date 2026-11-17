/**
 * K&K's own local legs for NPCs (link M6): {@link net.knightsandkings.knk.core.npc.nav.corridor.LocalLegSearch}, a
 * bounded A* in a corridor round the leg (implementation plan 4c.3, NAV-24). The corridor, the shaper, the free width
 * and the plaza line it builds on live in the shared package {@code core/navigation/shape} (PandiO/knk-workspace#704).
 * Pure knk-core over the {@link net.knightsandkings.knk.core.navigation.shape.NavWorld} port; no engine types.
 */
package net.knightsandkings.knk.core.npc.nav.corridor;
