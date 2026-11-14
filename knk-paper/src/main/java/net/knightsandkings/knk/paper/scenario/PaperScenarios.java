package net.knightsandkings.knk.paper.scenario;

import net.knightsandkings.knk.core.scenario.ScenarioRegistry;
import net.knightsandkings.knk.paper.scenario.scenarios.GateClosingMovesEntityOutScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.GateOpenCloseScenario;
import net.knightsandkings.knk.paper.scenario.scenarios.GlobalRegionExplosionScenario;

/**
 * Every scenario the plugin ships (PandiO/knk-workspace#421). Add a new one here; {@code run all}
 * runs them in this order, and CI runs {@code run all} after the Paper boot check.
 */
public final class PaperScenarios {

    private PaperScenarios() {
    }

    public static ScenarioRegistry<ScenarioScene> registry() {
        return new ScenarioRegistry<ScenarioScene>()
            .register(new GateOpenCloseScenario())
            .register(new GateClosingMovesEntityOutScenario())
            .register(new GlobalRegionExplosionScenario());
    }
}
