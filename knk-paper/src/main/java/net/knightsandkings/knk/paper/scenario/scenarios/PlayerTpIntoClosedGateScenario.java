package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gate smoke test § 2 step 16, the player half (simulated players, PandiO/knk-workspace#540): a survival player
 * {@code /tp}s into the blocks of a closed door and is moved out by the gate safety's teleport hook (it only runs for
 * players), without suffocation damage. The same {@code /tp} from a player without the in-house
 * {@code knk.teleport.staff} grant is refused and moves nobody, so the scenario also covers the command's permission
 * check (KNG-17).
 */
public final class PlayerTpIntoClosedGateScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "player-tp-into-closed-gate";
    }

    @Override
    public String description() {
        return "A player who /tps into a closed door is moved out unharmed; without the grant /tp is refused";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<ScenarioGates.Portcullis> gate = new AtomicReference<>();
        AtomicInteger suffocation = new AtomicInteger();
        ScenarioPlayer member = scene.player("ScnNoTp", p -> p.account(scene.fakeId(1)));
        ScenarioPlayer staff = scene.player("ScnTeleporter", p -> p.account(scene.fakeId(2)).grant("knk.teleport.staff"));
        String doorMiddle = String.format(Locale.ROOT, "%.1f %d %.1f",
            scene.worldX(15) + 0.5, scene.worldY(3), scene.worldZ(16) + 0.5);
        plan.expect("gates.safety.door-suffocation-damage is off",
                () -> !scene.services().config().getBoolean("gates.safety.door-suffocation-damage", false))
            .act("build a floor and a closed 3x3 portcullis", () -> {
                ScenarioGates.floor(scene, 8, 8, 24, 24, 1, Material.STONE);
                gate.set(ScenarioGates.loadPortcullis(scene, 14, 2, 16, 3, 3, Material.STONE_BRICKS, false, 20));
            })
            .cleanup("unload the portcullis", () -> {
                if (gate.get() != null) gate.get().unload();
            })
            .act("count suffocation events, cancelled ones included", () -> scene.onEvent(EntityDamageEvent.class, event -> {
                // the guard's damage fallback cancels the damage but still sees the event: the hook must act first
                if (event.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION
                        && event.getEntity().getUniqueId().equals(staff.uuid())) {
                    suffocation.incrementAndGet();
                }
            }))
            .act("both players join", () -> {
                member.join();
                staff.join();
            })
            .await("both are online", 200, () -> member.online() && staff.online())
            .await("both have left the join's loading hold", 200,
                () -> member.received("You're all set!") && staff.received("You're all set!"))
            .act("put them on the floor in front of the door, in survival", () -> {
                member.teleport(12.5, 2, 12.5);
                staff.teleport(18.5, 2, 12.5);
                member.player().setGameMode(GameMode.SURVIVAL);
                staff.player().setGameMode(GameMode.SURVIVAL);
                member.clearMessages();
                staff.clearMessages();
            })
            .act("the player without the grant types /tp into the door", () -> member.command("tp " + doorMiddle))
            .await("they are refused", 60, () -> member.received("permission"))
            .waitTicks(5)
            .check("they did not move", () -> moved(member.location(), scene.location(12.5, 2, 12.5))
                ? "moved to " + member.location() + "; got " + member.recentMessages() : null)
            .act("the staff player types /tp into the door", () -> staff.command("tp " + doorMiddle))
            .await("the teleport is reported", 60, () -> staff.received("Teleported to"))
            .await("they are out of the door's blocks", 40,
                () -> !ScenarioGates.overlaps(staff.player(), gate.get().cells(false)))
            .check("they are next to the door, not back where they started",
                () -> moved(staff.location(), scene.location(18.5, 2, 12.5))
                    && staff.location().distance(scene.location(15.5, 3, 16.5)) < 4 ? null
                    : "ended at " + staff.location() + "; got " + staff.recentMessages())
            .waitTicks(20)
            .expectEquals("suffocation events for the player (the hook moved them before any)", suffocation::get, 0)
            .expectEquals("the player's health", () -> staff.player().getHealth(), 20.0)
            .check("the door is still closed and whole", () -> gate.get().cellsProblem(false, true));
    }

    private static boolean moved(Location now, Location start) {
        return now.getWorld() != start.getWorld() || now.distance(start) > 0.5;
    }
}
