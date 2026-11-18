package net.knightsandkings.knk.paper.scenario;

/**
 * A scenario whose scene needs a box of another size than {@link ScenarioScene.Box#DEFAULT} (PandiO/knk-workspace#670:
 * the developer's whole stair house is 12 × 31 × 63 blocks). {@link PaperScenarioHost} force-loads, clears and
 * removes entities over that box instead. Keep it as small as the scene allows: every block is cleared twice per run.
 */
public interface SizedScene {

    /** The box this scenario's scene gets. */
    ScenarioScene.Box box();
}
