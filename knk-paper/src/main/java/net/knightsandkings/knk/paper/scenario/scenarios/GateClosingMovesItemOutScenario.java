package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A closing door moves a dropped item out of its opening before its blocks land on it (gate smoke test § 2 step 11,
 * the dropped item, KNG-106): a diamond lies in the opening of an open portcullis. While the door closes, the item is
 * never inside a placed door block (vanilla would only push it out afterwards), and once the door is closed the item
 * still exists, outside the door.
 */
public final class GateClosingMovesItemOutScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "gate-closing-moves-item-out";
    }

    @Override
    public String description() {
        return "A closing portcullis moves a dropped item out of its opening before its blocks land on it (KNG-106)";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<ScenarioGates.Portcullis> gate = new AtomicReference<>();
        AtomicReference<Item> item = new AtomicReference<>();
        AtomicInteger ticksInsideABlock = new AtomicInteger();
        plan.act("build a floor and load an open 3x3 portcullis", () -> {
                ScenarioGates.floor(scene, 10, 10, 20, 22, 1, Material.STONE);
                gate.set(ScenarioGates.loadPortcullis(scene, 14, 2, 16, 3, 3, Material.STONE_BRICKS, true, 20));
            })
            .cleanup("unload the portcullis", () -> {
                if (gate.get() != null) gate.get().unload();
            })
            .act("drop a diamond in the opening", () -> {
                Item dropped = scene.world().dropItem(scene.location(15.5, 2, 16.3), new ItemStack(Material.DIAMOND));
                dropped.setVelocity(new Vector(0, 0, 0));
                dropped.setPickupDelay(Integer.MAX_VALUE);
                item.set(dropped);
            })
            .await("the diamond lies in the opening", 40, () -> item.get().isOnGround()
                && ScenarioGates.overlaps(item.get(), gate.get().cells(false)))
            .expect("the gate manager starts closing the door", () -> gate.get().gates().closeGate(gate.get().doorId()))
            .await("the door is CLOSED", 100, () -> {
                if (insidePlacedBlock(item.get(), gate.get().cells(false))) {
                    ticksInsideABlock.incrementAndGet();
                }
                return gate.get().door().getCurrentState() == AnimationState.CLOSED;
            })
            .waitTicks(5)
            .check("the doorway is filled", () -> gate.get().cellsProblem(false, true))
            .expectEquals("ticks the diamond spent inside a placed door block", ticksInsideABlock::get, 0)
            .expect("the diamond still exists", () -> item.get().isValid())
            .check("the diamond is outside the door", () -> !ScenarioGates.overlaps(item.get(), gate.get().cells(false))
                ? null : "the diamond at " + item.get().getLocation().toVector() + " overlaps the closed door");
    }

    /** Whether the item's box overlaps a door cell that holds a block right now. */
    private static boolean insidePlacedBlock(Item item, List<Block> cells) {
        if (!item.isValid()) {
            return false;
        }
        var box = item.getBoundingBox();
        for (Block cell : cells) {
            if (!cell.getType().isAir() && box.overlaps(org.bukkit.util.BoundingBox.of(cell))) {
                return true;
            }
        }
        return false;
    }
}
