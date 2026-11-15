package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A portcullis opens and closes (gate smoke test § 20 "opening, closing ... behave as before"): a 3 × 3
 * door loaded from fake API data is opened through the live gate manager, the animation task lifts
 * it over the ticks that follow, and closing it puts every block back.
 */
public final class GateOpenCloseScenario implements Scenario<ScenarioScene> {

    private static final Material DOOR = Material.STONE_BRICKS;

    @Override
    public String name() {
        return "gate-open-close";
    }

    @Override
    public String description() {
        return "A 3x3 portcullis from fake API data opens and closes through the live gate animation";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<ScenarioGates.Portcullis> gate = new AtomicReference<>();
        plan.act("load a closed 3x3 portcullis", () -> gate.set(
                ScenarioGates.loadPortcullis(scene, 14, 2, 16, 3, 3, DOOR, false, 20)))
            .cleanup("unload the portcullis", () -> {
                if (gate.get() != null) gate.get().unload();
            })
            .check("the closed door is the 3x3 that was built", () -> footprintProblem(scene, gate.get()))
            .expect("the gate manager starts opening it", () -> gate.get().gates().openGate(gate.get().doorId()))
            .await("the door is OPEN", 100, () -> gate.get().door().getCurrentState() == AnimationState.OPEN)
            .check("the doorway is empty", () -> gate.get().cellsProblem(false, false))
            .check("the door hangs above the doorway", () -> gate.get().cellsProblem(true, true))
            .expect("the gate manager starts closing it", () -> gate.get().gates().closeGate(gate.get().doorId()))
            .await("the door is CLOSED", 100, () -> gate.get().door().getCurrentState() == AnimationState.CLOSED)
            .check("the doorway is filled again", () -> gate.get().cellsProblem(false, true))
            .check("nothing is left above the doorway", () -> gate.get().cellsProblem(true, false));
    }

    /** The plugin's closed footprint must be exactly the scene cells (14..16, 2..4, 16). */
    private static String footprintProblem(ScenarioScene scene, ScenarioGates.Portcullis gate) {
        Set<String> expected = new HashSet<>();
        for (int x = 14; x <= 16; x++) {
            for (int y = 2; y <= 4; y++) {
                expected.add(scene.worldX(x) + "," + scene.worldY(y) + "," + scene.worldZ(16));
            }
        }
        Set<String> actual = new HashSet<>();
        for (Block block : gate.cells(false)) {
            actual.add(block.getX() + "," + block.getY() + "," + block.getZ());
        }
        return expected.equals(actual) ? null : "closed footprint " + actual + " is not the built cells " + expected;
    }
}
