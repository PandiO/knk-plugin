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
 * A closing door moves each mob in its opening to the side it was standing on (gate smoke test § 2 steps 8 and 10,
 * KNG-106, with mobs standing in for players): one pig stands in the north half of an open portcullis's opening, one
 * in the south half. A wall three blocks high right in front of the door makes the nearest free spot for the north
 * pig the one behind the door, so only the side rule keeps it north. After the door has closed, the first is north of
 * the door (beyond the wall's row), the second south of it, both unharmed, neither ever caught by the door
 * (no suffocation event) and neither pushed through to the other side.
 */
public final class GateClosingKeepsSidesScenario implements Scenario<ScenarioScene> {

    private static final int DOOR_Z = 16;

    @Override
    public String name() {
        return "gate-closing-keeps-mobs-on-their-side";
    }

    @Override
    public String description() {
        return "A closing portcullis moves each mob in its opening out on the side it stood on (KNG-106)";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<ScenarioGates.Portcullis> gate = new AtomicReference<>();
        AtomicReference<Pig> north = new AtomicReference<>();
        AtomicReference<Pig> south = new AtomicReference<>();
        AtomicReference<AtomicInteger> suffocations = new AtomicReference<>();
        plan.act("build a floor, an open 3x3 portcullis and a wall right in front of it", () -> {
                ScenarioGates.floor(scene, 10, 10, 20, 22, 1, Material.STONE);
                gate.set(ScenarioGates.loadPortcullis(scene, 14, 2, DOOR_Z, 3, 3, Material.STONE_BRICKS, true, 20));
                for (int x = 12; x <= 18; x++) {
                    for (int y = 2; y <= 4; y++) {
                        scene.block(x, y, DOOR_Z - 1).setType(Material.COBBLESTONE, false);
                    }
                }
            })
            .cleanup("unload the portcullis", () -> {
                if (gate.get() != null) gate.get().unload();
            })
            .act("a pig in the north half of the opening, one in the south half", () -> {
                // z + 0.46: its box (0.9 wide) starts just inside the opening, clear of the wall's row
                north.set(ScenarioGates.stillPig(scene, 14.5, 2, DOOR_Z + 0.46));
                south.set(ScenarioGates.stillPig(scene, 16.5, 2, DOOR_Z + 0.8));
                suffocations.set(ScenarioGates.countSuffocation(scene, north.get(), south.get()));
            })
            .waitTicks(5)
            .expect("both pigs stand in the opening", () -> ScenarioGates.overlaps(north.get(), gate.get().cells(false))
                && ScenarioGates.overlaps(south.get(), gate.get().cells(false)))
            .expect("the gate manager starts closing the door", () -> gate.get().gates().closeGate(gate.get().doorId()))
            .await("the door is CLOSED", 100, () -> gate.get().door().getCurrentState() == AnimationState.CLOSED)
            .waitTicks(5)
            .check("the doorway is filled", () -> gate.get().cellsProblem(false, true))
            .expectEquals("suffocation damage events on the pigs", () -> suffocations.get().get(), 0)
            .check("the north pig is north of the door, beyond the wall", () ->
                north.get().getBoundingBox().getMaxZ() <= scene.worldZ(DOOR_Z - 1) ? null
                    : "its box reaches z " + north.get().getBoundingBox().getMaxZ() + " (the wall's row starts at "
                        + scene.worldZ(DOOR_Z - 1) + ")")
            .check("the south pig is south of the door", () ->
                south.get().getBoundingBox().getMinZ() >= scene.worldZ(DOOR_Z + 1) ? null
                    : "its box starts at z " + south.get().getBoundingBox().getMinZ() + " (door face " + scene.worldZ(DOOR_Z + 1) + ")")
            .check("the north pig is unharmed", () -> ScenarioGates.unharmedProblem(north.get()))
            .check("the south pig is unharmed", () -> ScenarioGates.unharmedProblem(south.get()));
    }
}
