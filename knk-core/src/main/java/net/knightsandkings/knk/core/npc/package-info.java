/**
 * Shared NPC mechanics, Bukkit-free (NPC platform DESIGN §5b, confirmed 2026-11-03; issues
 * PandiO/knk-workspace#146 and #41, link S2 of the Siege Survival chain).
 *
 * <p>What lives here: combat and speed profiles with code defaults per vanilla mob type
 * ({@code profile}), a configuration-override model a later link fills from {@code config.yml}
 * ({@code profile.ProfileOverrides}), a modifier pipeline keyed by source ({@code modifier}), a
 * resolver that combines base × modifiers and clamps to the profile's bounds while reporting every
 * clamp ({@code resolve}), tick-injected cooldown bookkeeping ({@code cooldown}) and the
 * {@link net.knightsandkings.knk.core.npc.NpcStatPipeline} facade the Paper adapters (link S5) call.
 *
 * <p>Boundaries: no {@code org.bukkit} / {@code io.papermc} (guarded by {@code ArchitectureGuardTest});
 * nothing Survival-specific — Survival adds modifiers by source key ({@code survival:round},
 * {@code survival:per-player}) with the multipliers it computes; health stays an attribute set by the
 * adapter and is not resolved here.
 */
package net.knightsandkings.knk.core.npc;
