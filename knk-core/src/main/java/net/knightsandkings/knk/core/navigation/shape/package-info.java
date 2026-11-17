/**
 * Shared path shaping for NPC routes and, later, the players' walk trail (off-road navigation design O11, phase 1,
 * PandiO/knk-workspace#704): the world port {@link net.knightsandkings.knk.core.navigation.shape.NavWorld} with
 * {@link net.knightsandkings.knk.core.navigation.shape.NavPoint} and
 * {@link net.knightsandkings.knk.core.navigation.shape.NavGeometry}, the clearance map
 * {@link net.knightsandkings.knk.core.navigation.shape.Corridor}, the shaper
 * {@link net.knightsandkings.knk.core.navigation.shape.CorridorShaper}, the free width across a line
 * {@link net.knightsandkings.knk.core.navigation.shape.FreeWidth} and the plaza line
 * {@link net.knightsandkings.knk.core.navigation.shape.PlazaLine} (moved from {@code core/npc/nav} and
 * {@code core/npc/nav/corridor} without a behaviour change). Pure knk-core, no engine types; imports nothing from
 * {@code core/npc} ({@code ArchitectureGuardTest}).
 */
package net.knightsandkings.knk.core.navigation.shape;
