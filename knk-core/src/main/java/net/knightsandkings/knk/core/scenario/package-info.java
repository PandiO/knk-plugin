/**
 * In-plugin test scenarios (PandiO/knk-workspace#421, DESIGN § 6.3.2 in knk-workspace
 * {@code docs/specs/ai-agent-workflow/}): a scenario builds a small scene on a test server, plays it
 * out over server ticks and asserts the result, so CI can prove a feature on a real Paper server.
 *
 * <p>This package is the platform-free part and must stay Bukkit-free (ArchitectureGuardTest):
 * <ul>
 *   <li>{@link net.knightsandkings.knk.core.scenario.Scenario}: one named scenario, generic over the
 *       scene type the platform gives it;</li>
 *   <li>{@link net.knightsandkings.knk.core.scenario.ScenarioPlan}: the steps a scenario defines
 *       (act, wait, await, expect, cleanup);</li>
 *   <li>{@link net.knightsandkings.knk.core.scenario.ScenarioRun}: plays one plan, one call to
 *       {@code tick()} per server tick;</li>
 *   <li>{@link net.knightsandkings.knk.core.scenario.ScenarioSuiteRun}: runs several scenarios one
 *       after the other through a {@link net.knightsandkings.knk.core.scenario.ScenarioHost} and
 *       reports one machine-readable line per scenario plus a summary
 *       ({@link net.knightsandkings.knk.core.scenario.ScenarioReport}).</li>
 * </ul>
 * The Bukkit glue (scenes, the console command, the scenarios themselves) is in knk-paper's
 * {@code net.knightsandkings.knk.paper.scenario}. Guide: knk-workspace
 * {@code docs/guides/developer/plugin-scenarios.md}.
 */
package net.knightsandkings.knk.core.scenario;
