package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.Material;
import org.bukkit.entity.Pig;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A mob found inside a closed door's blocks is moved out without suffocation damage, while an ordinary wall still
 * suffocates (gate smoke test § 2 steps 16 and 18, KNG-106, {@code gates.safety.door-suffocation-damage} off): one
 * pig is put into the middle of a closed portcullis, another into a solid stone block away from any gate. Within two
 * seconds the first is outside the door and at full health (every suffocation damage it got was cancelled); the
 * second has taken suffocation damage (the control: the guard only acts on gate blocks).
 */
public final class GateBlocksNeverSuffocateScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "gate-blocks-never-suffocate";
    }

    @Override
    public String description() {
        return "A mob inside a closed door is moved out unharmed; a mob in an ordinary wall still suffocates";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<ScenarioGates.Portcullis> gate = new AtomicReference<>();
        AtomicReference<Pig> inDoor = new AtomicReference<>();
        AtomicReference<Pig> inWall = new AtomicReference<>();
        AtomicInteger doorDamageTaken = new AtomicInteger();
        AtomicInteger wallDamageTaken = new AtomicInteger();
        plan.expect("gates.safety.door-suffocation-damage is off",
                () -> !scene.services().config().getBoolean("gates.safety.door-suffocation-damage", false))
            .act("build a floor, a closed 3x3 portcullis and a stone block elsewhere", () -> {
                ScenarioGates.floor(scene, 10, 10, 22, 22, 1, Material.STONE);
                gate.set(ScenarioGates.loadPortcullis(scene, 14, 2, 16, 3, 3, Material.STONE_BRICKS, false, 20));
                for (int y = 2; y <= 4; y++) {
                    scene.block(4, y, 4).setType(Material.STONE, false);
                }
                ScenarioGates.floor(scene, 3, 3, 5, 5, 1, Material.STONE);
            })
            .cleanup("unload the portcullis", () -> {
                if (gate.get() != null) gate.get().unload();
            })
            .act("count suffocation damage that goes through", () -> scene.onEvent(EntityDamageEvent.class, event -> {
                if (event.isCancelled() || event.getCause() != EntityDamageEvent.DamageCause.SUFFOCATION) {
                    return;
                }
                if (event.getEntity().equals(inDoor.get())) doorDamageTaken.incrementAndGet();
                if (event.getEntity().equals(inWall.get())) wallDamageTaken.incrementAndGet();
            }))
            .act("put a pig into the middle of the door and one into the stone block", () -> {
                inDoor.set(ScenarioGates.stillPig(scene, 15.5, 3, 16.5));
                inWall.set(ScenarioGates.stillPig(scene, 4.5, 3, 4.5));
            })
            .await("the door's pig is out of the door's blocks", 40,
                () -> !ScenarioGates.overlaps(inDoor.get(), gate.get().cells(false)))
            .await("the wall's pig took suffocation damage (control)", 60, () -> wallDamageTaken.get() > 0)
            .expectEquals("suffocation damage the door's pig took", doorDamageTaken::get, 0)
            .check("the door's pig is unharmed", () -> ScenarioGates.unharmedProblem(inDoor.get()))
            .check("the door is still closed and whole", () -> gate.get().cellsProblem(false, true));
    }
}
