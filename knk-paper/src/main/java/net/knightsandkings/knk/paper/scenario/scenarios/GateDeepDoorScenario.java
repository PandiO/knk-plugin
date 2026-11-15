package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.Material;
import org.bukkit.entity.Pig;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A door two blocks deep moves a mob out of its whole depth, not between its layers (gate smoke test § 2 step 9,
 * KNG-106): a pig stands across both layers of an open 3 × 3 × 2 portcullis's opening. After the door has closed, the
 * pig's box overlaps none of the door's 18 blocks, the pig is unharmed and was never caught by the door (no
 * suffocation event, not even one the fallback guard cancelled).
 */
public final class GateDeepDoorScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "gate-deep-door-moves-mob-out";
    }

    @Override
    public String description() {
        return "A closing portcullis two blocks deep moves a mob out of its whole depth (KNG-106)";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<ScenarioGates.Portcullis> gate = new AtomicReference<>();
        AtomicReference<Pig> pig = new AtomicReference<>();
        AtomicReference<AtomicInteger> suffocations = new AtomicReference<>();
        plan.act("build a floor and load an open 3x3x2 portcullis", () -> {
                ScenarioGates.floor(scene, 10, 10, 20, 24, 1, Material.STONE);
                gate.set(ScenarioGates.loadPortcullis(scene, 0, 14, 2, 16, 3, 3, 2, Material.STONE_BRICKS, true, 20));
            })
            .cleanup("unload the portcullis", () -> {
                if (gate.get() != null) gate.get().unload();
            })
            .expectEquals("blocks in the closed door", () -> gate.get().cells(false).size(), 18)
            .act("a pig across both layers of the opening", () -> pig.set(ScenarioGates.stillPig(scene, 15.5, 2, 17.0)))
            .act("watch for suffocation damage", () -> suffocations.set(ScenarioGates.countSuffocation(scene, pig.get())))
            .waitTicks(5)
            .expect("the pig stands in the opening", () -> ScenarioGates.overlaps(pig.get(), gate.get().cells(false)))
            .expect("the gate manager starts closing the door", () -> gate.get().gates().closeGate(gate.get().doorId()))
            .await("the door is CLOSED", 100, () -> gate.get().door().getCurrentState() == AnimationState.CLOSED)
            .waitTicks(5)
            .check("the doorway is filled", () -> gate.get().cellsProblem(false, true))
            .expectEquals("suffocation damage events (the door caught it)", () -> suffocations.get().get(), 0)
            .check("the pig is outside the door's whole depth", () -> !ScenarioGates.overlaps(pig.get(), gate.get().cells(false))
                ? null : "the pig at " + pig.get().getLocation().toVector() + " overlaps the closed door")
            .check("the pig is unharmed", () -> ScenarioGates.unharmedProblem(pig.get()));
    }
}
