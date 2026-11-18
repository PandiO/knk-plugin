package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.Material;
import org.bukkit.entity.Pig;
import org.bukkit.util.Vector;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Opening a door doesn't move what stands next to it (gate smoke test § 2 step 15, KNG-106): a pig stands right in
 * front of a closed portcullis and one right behind it. While the door opens, neither is moved (each stays within a
 * tenth of a block of where it stood).
 */
public final class GateOpeningLeavesNeighboursScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "gate-opening-leaves-neighbours";
    }

    @Override
    public String description() {
        return "Opening a portcullis leaves the mobs standing right in front of and behind it in place";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<ScenarioGates.Portcullis> gate = new AtomicReference<>();
        AtomicReference<Pig> front = new AtomicReference<>();
        AtomicReference<Pig> back = new AtomicReference<>();
        AtomicReference<Vector> frontAt = new AtomicReference<>();
        AtomicReference<Vector> backAt = new AtomicReference<>();
        plan.act("build a floor and load a closed 3x3 portcullis", () -> {
                ScenarioGates.floor(scene, 10, 10, 20, 22, 1, Material.STONE);
                gate.set(ScenarioGates.loadPortcullis(scene, 14, 2, 16, 3, 3, Material.STONE_BRICKS, false, 20));
            })
            .cleanup("unload the portcullis", () -> {
                if (gate.get() != null) gate.get().unload();
            })
            .act("a pig right in front of the door and one right behind it", () -> {
                // Off the block centres, so a move to a "safe spot" in the same block would still show.
                front.set(ScenarioGates.stillPig(scene, 15.2, 2, 15.3));
                back.set(ScenarioGates.stillPig(scene, 15.8, 2, 17.7));
            })
            .waitTicks(10)
            .act("note where they stand", () -> {
                frontAt.set(front.get().getLocation().toVector());
                backAt.set(back.get().getLocation().toVector());
            })
            .expect("the gate manager starts opening the door", () -> gate.get().gates().openGate(gate.get().doorId()))
            .await("the door is OPEN", 100, () -> gate.get().door().getCurrentState() == AnimationState.OPEN)
            .waitTicks(5)
            .check("the doorway is empty", () -> gate.get().cellsProblem(false, false))
            .check("the front pig was not moved", () -> movedProblem(front.get().getLocation().toVector(), frontAt.get()))
            .check("the back pig was not moved", () -> movedProblem(back.get().getLocation().toVector(), backAt.get()));
    }

    private static String movedProblem(Vector now, Vector before) {
        return now.distance(before) <= 0.1 ? null : "moved from " + before + " to " + now;
    }
}
