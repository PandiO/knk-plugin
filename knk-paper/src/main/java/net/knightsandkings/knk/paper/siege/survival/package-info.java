/**
 * Siege Survival runtime (docs/specs/siege-survival/DESIGN.md, PandiO/knk-workspace#41, link S5): a survival match on
 * the Siege lifecycle. A lobby plays survival when its drawn scenario has a survival configuration on the API
 * ({@link net.knightsandkings.knk.paper.siege.survival.SurvivalConfigurationCache}); otherwise nothing in this package
 * runs and ordinary PvP Siege is untouched. The engine (waves, stuck rules, end conditions, scaling, spawn selection)
 * is {@code knk-core/.../core/survival}; targeting is {@code core/targeting}; the NPC combat adapters are
 * {@code paper/npc}; the match-actor registry and NPC gate damage are {@code paper/siege/npc} (link S3).
 */
package net.knightsandkings.knk.paper.siege.survival;
