package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Pig;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A closing door moves a mob out of its opening instead of trapping it (gate smoke test § 2 step 11,
 * KNG-106): a pig stands in the doorway of an open portcullis; after the door has closed, the pig
 * is alive, unharmed and outside the door's blocks, and the doorway is filled. The pig must never
 * have suffocated (not even a damage event the safety guard cancelled): that would mean the closing
 * door caught it and only the fallback guard (GateSuffocationGuard) got it out.
 */
public final class GateClosingMovesEntityOutScenario implements Scenario<ScenarioScene> {

    private static final Material DOOR = Material.STONE_BRICKS;

    @Override
    public String name() {
        return "gate-closing-moves-entity-out";
    }

    @Override
    public String description() {
        return "A pig in the doorway of a closing portcullis is moved out, unharmed (KNG-106)";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<ScenarioGates.Portcullis> gate = new AtomicReference<>();
        AtomicReference<Pig> pig = new AtomicReference<>();
        AtomicInteger suffocations = new AtomicInteger();
        plan.act("build a floor and load an open 3x3 portcullis", () -> {
                ScenarioGates.floor(scene, 10, 10, 20, 22, 1, Material.STONE);
                gate.set(ScenarioGates.loadPortcullis(scene, 14, 2, 16, 3, 3, DOOR, true, 20));
            })
            .cleanup("unload the portcullis", () -> {
                if (gate.get() != null) gate.get().unload();
            })
            .act("put a pig in the doorway", () -> {
                Pig spawned = scene.spawn(15.5, 2, 16.5, Pig.class);
                spawned.setAI(false);
                spawned.setSilent(true);
                pig.set(spawned);
                scene.onEvent(EntityDamageEvent.class, event -> {
                    if (event.getEntity().equals(spawned) && event.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION) {
                        suffocations.incrementAndGet();
                    }
                });
            })
            .waitTicks(5)
            .expect("the pig stands in the doorway", () -> insideDoor(gate.get(), pig.get().getLocation()))
            .expect("the gate manager starts closing the door", () -> gate.get().gates().closeGate(gate.get().doorId()))
            .await("the door is CLOSED", 100, () -> gate.get().door().getCurrentState() == AnimationState.CLOSED)
            .waitTicks(5)
            .check("the doorway is filled", () -> gate.get().cellsProblem(false, true))
            .expectEquals("suffocation damage events on the pig", suffocations::get, 0)
            .expect("the pig is still alive", () -> pig.get().isValid() && !pig.get().isDead())
            .expect("the pig is outside the door's blocks", () -> !insideDoor(gate.get(), pig.get().getLocation())
                && !insideDoor(gate.get(), pig.get().getEyeLocation()))
            .check("the pig is unharmed", () -> pig.get().getHealth() >= maxHealth(pig.get()) ? null
                : "the pig has " + pig.get().getHealth() + " of " + maxHealth(pig.get()) + " health");
    }

    private static boolean insideDoor(ScenarioGates.Portcullis gate, Location location) {
        for (Block cell : gate.cells(false)) {
            if (cell.getX() == location.getBlockX() && cell.getY() == location.getBlockY()
                && cell.getZ() == location.getBlockZ()) {
                return true;
            }
        }
        return false;
    }

    private static double maxHealth(Pig pig) {
        var attribute = pig.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? pig.getHealth() : attribute.getValue();
    }
}
