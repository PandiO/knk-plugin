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
 * Being moved out of one door never puts a mob into the opening of the door next to it (gate smoke test § 2 step 14,
 * KNG-106): two open portcullises side by side, A (x 10 to 12) and B (x 13 to 15). Walls three rows deep in front of
 * and behind both doors leave B's opening as the best free spot for a pig in A's opening at the side next to B
 * (without the rule it is put there; with it, beyond the walls). After A has closed, the
 * pig overlaps neither A's blocks nor B's opening, is unharmed and was never caught by A (no suffocation event).
 * B stays open throughout.
 */
public final class GateSideBySideScenario implements Scenario<ScenarioScene> {

    private static final int DOOR_Z = 16;

    @Override
    public String name() {
        return "gate-side-by-side-doors";
    }

    @Override
    public String description() {
        return "A mob moved out of a closing door is never put into the opening of the door beside it (KNG-106)";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<ScenarioGates.Portcullis> doorA = new AtomicReference<>();
        AtomicReference<ScenarioGates.Portcullis> doorB = new AtomicReference<>();
        AtomicReference<Pig> pig = new AtomicReference<>();
        AtomicReference<AtomicInteger> suffocations = new AtomicReference<>();
        plan.act("build a floor, two open portcullises side by side and walls in front of and behind both", () -> {
                ScenarioGates.floor(scene, 6, 10, 20, 22, 1, Material.STONE);
                doorA.set(ScenarioGates.loadPortcullis(scene, 0, 10, 2, DOOR_Z, 3, 3, 1, Material.STONE_BRICKS, true, 20));
                doorB.set(ScenarioGates.loadPortcullis(scene, 1, 13, 2, DOOR_Z, 3, 3, 1, Material.OAK_PLANKS, true, 20));
                for (int x = 8; x <= 16; x++) {
                    for (int y = 2; y <= 4; y++) {
                        for (int row = 1; row <= 3; row++) {
                            scene.block(x, y, DOOR_Z - row).setType(Material.COBBLESTONE, false);
                            scene.block(x, y, DOOR_Z + row).setType(Material.COBBLESTONE, false);
                        }
                    }
                }
            })
            .cleanup("unload the portcullises", () -> {
                if (doorA.get() != null) doorA.get().unload();
                if (doorB.get() != null) doorB.get().unload();
            })
            .act("a pig in A's opening, next to B", () -> pig.set(ScenarioGates.stillPig(scene, 12.5, 2, DOOR_Z + 0.5)))
            .act("watch for suffocation damage", () -> suffocations.set(ScenarioGates.countSuffocation(scene, pig.get())))
            .waitTicks(5)
            .expect("the pig stands in A's opening", () -> ScenarioGates.overlaps(pig.get(), doorA.get().cells(false)))
            .expect("the gate manager starts closing A", () -> doorA.get().gates().closeGate(doorA.get().doorId()))
            .await("A is CLOSED", 100, () -> doorA.get().door().getCurrentState() == AnimationState.CLOSED)
            .waitTicks(5)
            .check("A's doorway is filled", () -> doorA.get().cellsProblem(false, true))
            .expectEquals("suffocation damage events (the door caught it)", () -> suffocations.get().get(), 0)
            .check("the pig is out of A", () -> !ScenarioGates.overlaps(pig.get(), doorA.get().cells(false)) ? null
                : "the pig at " + pig.get().getLocation().toVector() + " overlaps door A")
            .check("the pig is not in B's opening", () -> !ScenarioGates.overlaps(pig.get(), doorB.get().cells(false)) ? null
                : "the pig at " + pig.get().getLocation().toVector() + " was put into door B's opening")
            .check("B is still open", () -> doorB.get().cellsProblem(false, false))
            .check("the pig is unharmed", () -> ScenarioGates.unharmedProblem(pig.get()));
    }
}
