package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.Material;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gate smoke test § 2 step 17 (simulated players, PandiO/knk-workspace#540): a player logs out while standing in the
 * opening of an open door, the door closes while they are away, and when they log back in (at the spot they left,
 * inside the door's blocks now) the gate safety's join hook moves them out, without suffocation damage. The player
 * holds {@code knk.mode.owner} in-house: owners join where they logged out, while a regular player is sent to the join
 * spawn by the plugin's join handling before the hook looks (so for them the door never matters).
 */
public final class PlayerRejoinInClosedGateScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "player-rejoin-in-closed-gate";
    }

    @Override
    public String description() {
        return "A player who logged out in an opening and joins after the door closed is moved out unharmed";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<ScenarioGates.Portcullis> gate = new AtomicReference<>();
        AtomicInteger suffocation = new AtomicInteger();
        ScenarioPlayer owner = scene.player("ScnRejoiner", p -> p.account(scene.fakeId(1)).grant("knk.mode.owner"));
        plan.expect("gates.safety.door-suffocation-damage is off",
                () -> !scene.services().config().getBoolean("gates.safety.door-suffocation-damage", false))
            .act("build a floor and an open 3x3 portcullis", () -> {
                ScenarioGates.floor(scene, 8, 8, 24, 24, 1, Material.STONE);
                gate.set(ScenarioGates.loadPortcullis(scene, 14, 2, 16, 3, 3, Material.STONE_BRICKS, true, 20));
            })
            .cleanup("unload the portcullis", () -> {
                if (gate.get() != null) gate.get().unload();
            })
            .check("the doorway is open", () -> gate.get().cellsProblem(false, false))
            .act("count suffocation events, cancelled ones included", () -> scene.onEvent(EntityDamageEvent.class, event -> {
                // the guard's damage fallback cancels the damage but still sees the event: the hook must act first
                if (event.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION
                        && event.getEntity().getUniqueId().equals(owner.uuid())) {
                    suffocation.incrementAndGet();
                }
            }))
            .act("the player joins", owner::join)
            .await("they are online", 200, owner::online)
            .act("they stand in the middle of the opening", () -> owner.teleport(15.5, 2, 16.5))
            .waitTicks(5)
            .check("they are in the opening", () -> ScenarioGates.overlaps(owner.player(), gate.get().cells(false)) ? null
                : "they are at " + owner.location())
            .act("they log out", owner::quit)
            .expect("the gate manager starts closing the door", () -> gate.get().gates().closeGate(gate.get().doorId()))
            .await("the door is CLOSED", 100, () -> gate.get().door().getCurrentState() == AnimationState.CLOSED)
            .check("the doorway is filled", () -> gate.get().cellsProblem(false, true))
            .act("they log back in", owner::join)
            .await("they are online again", 200, owner::online)
            .await("they are out of the door's blocks", 40,
                () -> !ScenarioGates.overlaps(owner.player(), gate.get().cells(false)))
            .check("they are next to the door", () -> owner.location().distance(scene.location(15.5, 2, 16.5)) < 4 ? null
                : "they ended at " + owner.location())
            .waitTicks(20)
            .expectEquals("suffocation events for them (the hook moved them before any)", suffocation::get, 0)
            .expectEquals("their health", () -> owner.player().getHealth(), 20.0)
            .check("the door is still closed and whole", () -> gate.get().cellsProblem(false, true));
    }
}
