package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Pig;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A closing door moves a vehicle out with its rider still on it (gate smoke test § 2 step 12, KNG-106, a pig standing
 * in for the rider): a minecart with a pig in it stands in the opening of an open portcullis. After the door has
 * closed, the minecart is outside the door, the pig still rides it and is unharmed, and neither was ever caught by
 * the door (no suffocation event).
 */
public final class GateClosingMovesVehicleScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "gate-closing-moves-vehicle-with-rider";
    }

    @Override
    public String description() {
        return "A closing portcullis moves a minecart out of its opening with its rider still in it (KNG-106)";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<ScenarioGates.Portcullis> gate = new AtomicReference<>();
        AtomicReference<Minecart> cart = new AtomicReference<>();
        AtomicReference<Pig> rider = new AtomicReference<>();
        AtomicReference<AtomicInteger> suffocations = new AtomicReference<>();
        plan.act("build a floor and load an open 3x3 portcullis", () -> {
                ScenarioGates.floor(scene, 10, 10, 20, 22, 1, Material.STONE);
                gate.set(ScenarioGates.loadPortcullis(scene, 14, 2, 16, 3, 3, Material.STONE_BRICKS, true, 20));
            })
            .cleanup("unload the portcullis", () -> {
                if (gate.get() != null) gate.get().unload();
            })
            .act("a minecart with a pig in it in the opening", () -> {
                cart.set(scene.spawn(15.5, 2, 16.3, Minecart.class));
                rider.set(ScenarioGates.stillPig(scene, 15.5, 2, 16.3));
                cart.get().addPassenger(rider.get());
            })
            .act("watch for suffocation damage", () -> suffocations.set(ScenarioGates.countSuffocation(scene, cart.get(), rider.get())))
            .waitTicks(5)
            .expect("the pig rides the minecart in the opening", () -> cart.get().equals(rider.get().getVehicle())
                && ScenarioGates.overlaps(cart.get(), gate.get().cells(false)))
            .expect("the gate manager starts closing the door", () -> gate.get().gates().closeGate(gate.get().doorId()))
            .await("the door is CLOSED", 100, () -> gate.get().door().getCurrentState() == AnimationState.CLOSED)
            .waitTicks(5)
            .check("the doorway is filled", () -> gate.get().cellsProblem(false, true))
            .expectEquals("suffocation damage events (the door caught it)", () -> suffocations.get().get(), 0)
            .expect("the minecart still exists", () -> cart.get().isValid())
            .check("the minecart is outside the door", () -> !ScenarioGates.overlaps(cart.get(), gate.get().cells(false))
                ? null : "the minecart at " + cart.get().getLocation().toVector() + " overlaps the closed door")
            .expect("the pig still rides the minecart", () -> cart.get().equals(rider.get().getVehicle()))
            .check("the pig is unharmed", () -> ScenarioGates.unharmedProblem(rider.get()));
    }
}
