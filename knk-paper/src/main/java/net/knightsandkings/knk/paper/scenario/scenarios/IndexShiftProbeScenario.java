package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * THROWAWAY PROBE (branch claude/probe-quarry-index-946, never merged): a scenario that does nothing, registered three
 * times before the NPC scenarios so every later scene moves three slots (192 blocks) along x, exactly as the three
 * region scenarios of PandiO/knk-workspace#945/#946 do. It touches no world, region or plugin state.
 */
public final class IndexShiftProbeScenario implements Scenario<ScenarioScene> {

    private final int n;

    public IndexShiftProbeScenario(int n) {
        this.n = n;
    }

    @Override
    public String name() {
        return "probe-index-shift-" + n;
    }

    @Override
    public String description() {
        return "Does nothing; only takes a scene slot";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        plan.check("nothing to check", () -> null);
    }
}
