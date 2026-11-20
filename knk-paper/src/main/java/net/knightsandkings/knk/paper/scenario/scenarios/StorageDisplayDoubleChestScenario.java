package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Chest;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.ports.api.StorageContainersApi;
import net.knightsandkings.knk.core.production.terminal.BindRequest;
import net.knightsandkings.knk.core.production.terminal.DisplaySample;
import net.knightsandkings.knk.core.production.terminal.StockMoveResult;
import net.knightsandkings.knk.core.production.terminal.StorageContainerInfo;
import net.knightsandkings.knk.core.production.terminal.TerminalPage;
import net.knightsandkings.knk.core.production.terminal.TerminalSettings;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.production.terminal.BlueprintItems;
import net.knightsandkings.knk.paper.production.terminal.ContainerBlocks;
import net.knightsandkings.knk.paper.production.terminal.DisplayWriter;
import net.knightsandkings.knk.paper.production.terminal.StorageTerminalModule;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * A display double chest on a real server (PandiO/knk-workspace#74, production DESIGN § 6.2-6.3): both halves of a double
 * chest find the same binding (its left half); the display's 54 slots get the tagged sample; a hopper under it pulls
 * nothing out for two seconds, while the same hopper under an unbound chest does (the control). The API is a stand-in
 * that lists one display and serves its sample; nothing else of the production feature runs.
 */
public final class StorageDisplayDoubleChestScenario implements Scenario<ScenarioScene> {

    private static final int Y = 3;
    private static final int DISPLAY_X = 4;
    private static final int CONTROL_X = 12;
    private static final int Z = 8;

    @Override
    public String name() {
        return "storage-display-double-chest";
    }

    @Override
    public String description() {
        return "A display double chest is bound by its left half, holds a tagged sample and keeps it from a hopper";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<StorageTerminalModule> module = new AtomicReference<>();
        AtomicReference<Block> canonical = new AtomicReference<>();
        plan.act("build a double chest with a hopper under its right half, and a single chest with a hopper as the control", () -> {
                doubleChest(scene);
                scene.block(DISPLAY_X + 1, Y - 1, Z).setType(Material.HOPPER, false);
                scene.block(CONTROL_X, Y, Z).setType(Material.CHEST, false);
                scene.block(CONTROL_X, Y - 1, Z).setType(Material.HOPPER, false);
            })
            .expect("the chest is a double chest", () -> ContainerBlocks.isDouble(scene.block(DISPLAY_X, Y, Z)))
            .act("find its canonical half", () -> canonical.set(ContainerBlocks.canonical(scene.block(DISPLAY_X, Y, Z))))
            .check("both halves have the same canonical block", () -> {
                Block other = ContainerBlocks.canonical(scene.block(DISPLAY_X + 1, Y, Z));
                return other.getLocation().equals(canonical.get().getLocation()) ? null
                    : "left half gives " + canonical.get() + ", right half gives " + other;
            })
            .check("the canonical block is one of the halves", () -> {
                Block block = canonical.get();
                return block != null && block.getY() == scene.worldY(Y) && block.getZ() == scene.worldZ(Z)
                    && (block.getX() == scene.worldX(DISPLAY_X) || block.getX() == scene.worldX(DISPLAY_X + 1))
                    ? null : "canonical block " + block;
            })
            .act("bind it as a display (stand-in API) and write its sample", () -> {
                Block block = canonical.get();
                StorageContainerInfo display = new StorageContainerInfo(1, 1, "Scenario storage",
                    StorageContainerInfo.Mode.DISPLAY, "Chest", true, 54, scene.world().getName(), block.getX(), block.getY(),
                    block.getZ(), null);
                StorageTerminalModule terminals = new StorageTerminalModule(scene.services().plugin(),
                    TerminalSettings.defaults(true), api(display), stone(), uuid -> null, () -> null);
                module.set(terminals);
                terminals.start();
                terminals.reload("scenario").join();
                terminals.refreshDisplay(display).join();
            })
            .cleanup("stop the stand-in terminal module", () -> {
                StorageTerminalModule terminals = module.get();
                if (terminals != null) {
                    terminals.stop();
                    HandlerList.unregisterAll(terminals);
                }
            })
            .act("put one stone in the control chest", () ->
                ContainerBlocks.inventory(scene.block(CONTROL_X, Y, Z)).addItem(new ItemStack(Material.STONE)))
            .expectEquals("tagged sample stacks in the display", () -> samples(scene), 30)
            .waitTicks(40)
            .expectEquals("tagged sample stacks after two seconds of hopper ticks", () -> samples(scene), 30)
            .expectEquals("items in the display's hopper", () -> count(ContainerBlocks.inventory(scene.block(DISPLAY_X + 1, Y - 1, Z))), 0)
            .expectEquals("items in the control's hopper (it does pull from an unbound chest)",
                () -> count(ContainerBlocks.inventory(scene.block(CONTROL_X, Y - 1, Z))), 1);
    }

    private static void doubleChest(ScenarioScene scene) {
        Block left = scene.block(DISPLAY_X, Y, Z);
        Block right = scene.block(DISPLAY_X + 1, Y, Z);
        left.setType(Material.CHEST, false);
        right.setType(Material.CHEST, false);
        // facing north, a LEFT chest's partner is to its east (clockwise of north)
        Chest leftData = (Chest) left.getBlockData();
        leftData.setFacing(BlockFace.NORTH);
        leftData.setType(Chest.Type.LEFT);
        left.setBlockData(leftData, false);
        Chest rightData = (Chest) right.getBlockData();
        rightData.setFacing(BlockFace.NORTH);
        rightData.setType(Chest.Type.RIGHT);
        right.setBlockData(rightData, false);
    }

    private static int samples(ScenarioScene scene) {
        Inventory inventory = ContainerBlocks.inventory(scene.block(DISPLAY_X, Y, Z));
        return inventory == null ? -1 : (int) Arrays.stream(inventory.getContents()).filter(DisplayWriter::isSample).count();
    }

    private static int count(Inventory inventory) {
        return inventory == null ? -1 : Arrays.stream(inventory.getContents()).filter(i -> i != null).mapToInt(ItemStack::getAmount).sum();
    }

    private static BlueprintItems stone() {
        return new BlueprintItems(null, null) {
            @Override
            public Template cached(int blueprintId) {
                return new Template(new ItemStack(Material.STONE), 64, true);
            }

            @Override
            public CompletableFuture<Template> template(int blueprintId, String fallbackName) {
                return CompletableFuture.completedFuture(cached(blueprintId));
            }
        };
    }

    /** Lists the one display and serves a sample of 30 stacks (fill 55%). */
    private static StorageContainersApi api(StorageContainerInfo display) {
        return new StorageContainersApi() {
            public CompletableFuture<Optional<StorageContainerInfo>> byBlock(String w, int x, int y, int z) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            public CompletableFuture<List<StorageContainerInfo>> list(StorageContainerInfo.Mode mode, String worldName) {
                return CompletableFuture.completedFuture(List.of(display));
            }

            public CompletableFuture<StorageContainerInfo> bind(BindRequest request) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("scenario"));
            }

            public CompletableFuture<Void> unbind(int containerId) {
                return CompletableFuture.completedFuture(null);
            }

            public CompletableFuture<TerminalPage> terminal(int containerId, int page, int pageSize, Integer actingUserId) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("scenario"));
            }

            public CompletableFuture<StockMoveResult> put(int c, int u, Integer b, Long i, long a, String k) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("scenario"));
            }

            public CompletableFuture<StockMoveResult> take(int c, int u, Integer b, Long i, long a, String k) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("scenario"));
            }

            public CompletableFuture<DisplaySample> display(int containerId) {
                return CompletableFuture.completedFuture(new DisplaySample(containerId, 1, 54, 0.55, 30,
                    List.of(new DisplaySample.Item(1, "Stone", 20), new DisplaySample.Item(2, "Stone", 10))));
            }
        };
    }
}
