/**
 * Siege Survival ("Defend the Castle") core logic, Bukkit-free (docs/specs/siege-survival/DESIGN.md, decisions
 * S01–S68; PandiO/knk-workspace#41). Pure accounting and rules: wave queue and kill accounting ({@code wave}),
 * stuck-enemy handling ({@code stuck}), end conditions ({@code end}), count and stat scaling ({@code scaling}),
 * monster mix, special waves and spawnzone selection ({@code spawn}), cooperative repair maths ({@code repair}),
 * per-kill rewards and the match-currency ledger ({@code economy}) and the Mystery Box pool ({@code mysterybox}).
 * <p>
 * Nothing here touches the world: time is passed in as milliseconds, randomness as a
 * {@link java.util.random.RandomGenerator}, and the Paper adapters (later links) call these classes and apply the
 * answers. The substate names {@link net.knightsandkings.knk.core.survival.SurvivalSubstate} are the design's
 * names (DESIGN §3), not the existing Siege enums.
 */
package net.knightsandkings.knk.core.survival;
