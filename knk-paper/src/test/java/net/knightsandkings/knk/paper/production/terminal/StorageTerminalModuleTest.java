package net.knightsandkings.knk.paper.production.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.items.identity.ItemIdentityResolution;
import net.knightsandkings.knk.core.ports.api.StorageContainersApi;
import net.knightsandkings.knk.core.production.terminal.BindRequest;
import net.knightsandkings.knk.core.production.terminal.DisplaySample;
import net.knightsandkings.knk.core.production.terminal.StockMoveResult;
import net.knightsandkings.knk.core.production.terminal.StorageContainerInfo;
import net.knightsandkings.knk.core.production.terminal.TerminalLayout;
import net.knightsandkings.knk.core.production.terminal.TerminalPage;
import net.knightsandkings.knk.core.production.terminal.TerminalSettings;

/**
 * PandiO/knk-workspace#74 (A14): a bound chest opens as a terminal; take gives items, put holds them back until the API
 * answers and returns what it refused; a lost answer is sent again with the same key; a display holds a tagged sample;
 * bound blocks can't be broken.
 */
class StorageTerminalModuleTest {

    private ServerMock server;
    private WorldMock world;
    private PlayerMock player;
    private Plugin plugin;
    private StorageTerminalModule module;
    private final List<String> calls = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private CompletableFuture<StockMoveResult> nextPut;
    private int putFailures;
    private long takeAccepts = 64;
    private Function<ItemStack, ItemIdentityResolution> resolver =
            item -> new ItemIdentityResolution(ItemIdentityResolution.Kind.VANILLA_MATCH, 3, null, null, false);

    static final StorageContainerInfo TERMINAL = new StorageContainerInfo(7, 12, "North delivery",
            StorageContainerInfo.Mode.TERMINAL, "Chest", false, 27, "world", 1, 64, 0, null);
    static final StorageContainerInfo DISPLAY = new StorageContainerInfo(8, 12, "North delivery",
            StorageContainerInfo.Mode.DISPLAY, "Barrel", false, 27, "world", 3, 64, 0, null);

    private final StorageContainersApi api = new StorageContainersApi() {
        public CompletableFuture<Optional<StorageContainerInfo>> byBlock(String w, int x, int y, int z) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        public CompletableFuture<List<StorageContainerInfo>> list(StorageContainerInfo.Mode mode, String worldName) {
            return CompletableFuture.completedFuture(List.of(TERMINAL, DISPLAY));
        }

        public CompletableFuture<StorageContainerInfo> bind(BindRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        public CompletableFuture<Void> unbind(int containerId) {
            return CompletableFuture.completedFuture(null);
        }

        public CompletableFuture<TerminalPage> terminal(int containerId, int page, int pageSize, Integer actingUserId) {
            calls.add("terminal " + containerId + " p" + page + " as " + actingUserId);
            return CompletableFuture.completedFuture(new TerminalPage(containerId, 12, "North delivery", 500, 10800L, false,
                    page, pageSize, 2, List.of(new TerminalPage.Entry(3, "Stone", 500, null, null),
                    new TerminalPage.Entry(9, "Sword", 1, 77L, "Old Faithful"))));
        }

        public CompletableFuture<StockMoveResult> put(int containerId, int actingUserId, Integer itemBlueprintId,
                                                      Long itemInstanceId, long amount, String key) {
            calls.add("put " + itemBlueprintId + " x" + amount + " as " + actingUserId);
            keys.add(key);
            if (putFailures > 0) {
                putFailures--;
                return CompletableFuture.failedFuture(new CompletionException(new java.io.IOException("connection reset")));
            }
            return nextPut != null ? nextPut
                    : CompletableFuture.completedFuture(new StockMoveResult("Applied", false, amount, List.of()));
        }

        public CompletableFuture<StockMoveResult> take(int containerId, int actingUserId, Integer itemBlueprintId,
                                                       Long itemInstanceId, long amount, String key) {
            calls.add("take " + itemBlueprintId + " x" + amount + " as " + actingUserId);
            keys.add(key);
            long accepted = Math.min(amount, takeAccepts);
            return CompletableFuture.completedFuture(new StockMoveResult(accepted < amount ? "PartiallyApplied" : "Applied",
                    false, accepted, accepted < amount ? List.of("NotEnoughStock") : List.of()));
        }

        public CompletableFuture<DisplaySample> display(int containerId) {
            calls.add("display " + containerId);
            return CompletableFuture.completedFuture(new DisplaySample(containerId, 12, 27, 0.5, 14,
                    List.of(new DisplaySample.Item(3, "Stone", 14))));
        }
    };

    /** Stone for every blueprint, without the API. */
    private final BlueprintItems items = new BlueprintItems(null, null) {
        @Override
        public Template cached(int blueprintId) {
            return new Template(new ItemStack(Material.STONE), 64, true);
        }

        @Override
        public CompletableFuture<Template> template(int blueprintId, String fallbackName) {
            return CompletableFuture.completedFuture(cached(blueprintId));
        }
    };

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        player = server.addPlayer();
        plugin = MockBukkit.createMockPlugin();
        world.loadChunk(0, 0);
        world.getBlockAt(1, 64, 0).setType(Material.CHEST);
        world.getBlockAt(3, 64, 0).setType(Material.BARREL);
        world.getBlockAt(5, 64, 0).setType(Material.CHEST);
        module = new StorageTerminalModule(plugin, TerminalSettings.defaults(true), api, items, uuid -> 41, () -> resolver);
        module.reload("test").join();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerInteractEvent rightClick(Block block) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND);
    }

    private TerminalView openTerminal() {
        PlayerInteractEvent event = rightClick(world.getBlockAt(1, 64, 0));
        module.onInteract(event);
        server.getScheduler().performTicks(1);
        assertTrue(event.isCancelled());
        return assertInstanceOf(TerminalView.class, player.getOpenInventory().getTopInventory().getHolder(false));
    }

    @Test
    void aBoundChestOpensAsATerminal_anUnboundChestStaysAChest() {
        TerminalView view = openTerminal();

        assertEquals(List.of("terminal 7 p1 as 41"), calls);
        assertEquals(Material.STONE, view.getInventory().getItem(0).getType());
        assertNotNull(view.getInventory().getItem(TerminalLayout.INFO_SLOT));
        assertNull(view.getInventory().getItem(TerminalLayout.NEXT_SLOT));

        PlayerInteractEvent plain = rightClick(world.getBlockAt(5, 64, 0));
        module.onInteract(plain);
        assertFalse(plain.isCancelled());
    }

    @Test
    void take_givesTheAcceptedUnits_withOneKeyPerClick() {
        TerminalView view = openTerminal();

        module.take(player, view, view.entryAt(0), TerminalLayout.Click.LEFT);
        server.getScheduler().performTicks(1);
        takeAccepts = 3;
        module.take(player, view, view.entryAt(0), TerminalLayout.Click.SHIFT);
        server.getScheduler().performTicks(1);

        assertTrue(calls.contains("take 3 x64 as 41"));
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("take 3 x500")));
        assertEquals(67, player.getInventory().all(Material.STONE).values().stream().mapToInt(ItemStack::getAmount).sum());
        assertEquals(2, keys.stream().distinct().count());
    }

    @Test
    void take_ofAUniqueItemIsRefusedInGame() {
        TerminalView view = openTerminal();

        module.take(player, view, view.entryAt(1), TerminalLayout.Click.LEFT);

        assertFalse(calls.stream().anyMatch(c -> c.startsWith("take")));
    }

    @Test
    void put_holdsTheItemsBack_andReturnsWhatTheApiRefused() {
        TerminalView view = openTerminal();
        player.getInventory().setItem(0, new ItemStack(Material.STONE, 10));
        CompletableFuture<StockMoveResult> answer = new CompletableFuture<>();
        nextPut = answer;

        module.put(player, view, 0, TerminalLayout.Click.LEFT);
        assertNull(player.getInventory().getItem(0)); // held back while the API answers
        answer.complete(new StockMoveResult("PartiallyApplied", false, 6, List.of("CapacityFull")));
        server.getScheduler().performTicks(1);

        assertTrue(calls.contains("put 3 x10 as 41"));
        assertEquals(4, player.getInventory().all(Material.STONE).values().stream().mapToInt(ItemStack::getAmount).sum());
    }

    @Test
    void put_aLostAnswerIsSentAgainWithTheSameKey_onlyOnce() {
        TerminalView view = openTerminal();
        player.getInventory().setItem(0, new ItemStack(Material.STONE, 5));
        putFailures = 1;

        module.put(player, view, 0, TerminalLayout.Click.LEFT);
        server.getScheduler().performTicks(1);

        assertEquals(2, keys.size());
        assertEquals(keys.get(0), keys.get(1));
        assertFalse(player.getInventory().contains(Material.STONE));

        player.getInventory().setItem(0, new ItemStack(Material.STONE, 5));
        putFailures = 2;
        module.put(player, view, 0, TerminalLayout.Click.RIGHT);
        server.getScheduler().performTicks(1);
        assertEquals(5, player.getInventory().all(Material.STONE).values().stream().mapToInt(ItemStack::getAmount).sum());
    }

    @Test
    void put_isRefusedWithoutItemIdentity_forUnknownUniqueDamagedOrEnchantedItems() {
        TerminalView view = openTerminal();
        player.getInventory().setItem(0, new ItemStack(Material.STONE, 5));

        resolver = null;
        module.put(player, view, 0, TerminalLayout.Click.LEFT);
        resolver = item -> new ItemIdentityResolution(ItemIdentityResolution.Kind.UNRESOLVED, null, null,
                ItemIdentityResolution.UnresolvedReason.CUSTOM_NAME, false);
        module.put(player, view, 0, TerminalLayout.Click.LEFT);
        resolver = item -> new ItemIdentityResolution(ItemIdentityResolution.Kind.INSTANCE, 3, 77L, null, false);
        module.put(player, view, 0, TerminalLayout.Click.LEFT);

        ItemStack pick = new ItemStack(Material.IRON_PICKAXE);
        Damageable meta = (Damageable) pick.getItemMeta();
        meta.setDamage(10);
        pick.setItemMeta(meta);
        assertNotNull(StorageTerminalModule.refusal(pick));
        ItemStack enchanted = new ItemStack(Material.IRON_PICKAXE);
        enchanted.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.EFFICIENCY, 2);
        assertTrue(StorageTerminalModule.enchanted(enchanted));

        assertFalse(calls.stream().anyMatch(c -> c.startsWith("put")));
        assertEquals(5, player.getInventory().getItem(0).getAmount());
    }

    @Test
    void anApiRefusalOfTheTerminalIsTold() {
        StorageContainersApi refusing = new StorageContainersApi() {
            public CompletableFuture<Optional<StorageContainerInfo>> byBlock(String w, int x, int y, int z) { return null; }
            public CompletableFuture<List<StorageContainerInfo>> list(StorageContainerInfo.Mode m, String w) {
                return CompletableFuture.completedFuture(List.of(TERMINAL));
            }
            public CompletableFuture<StorageContainerInfo> bind(BindRequest r) { return null; }
            public CompletableFuture<Void> unbind(int id) { return null; }
            public CompletableFuture<TerminalPage> terminal(int id, int p, int s, Integer u) {
                return CompletableFuture.failedFuture(new CompletionException(new ApiException("u", 403, "Forbidden", "")));
            }
            public CompletableFuture<StockMoveResult> put(int c, int u, Integer b, Long i, long a, String k) { return null; }
            public CompletableFuture<StockMoveResult> take(int c, int u, Integer b, Long i, long a, String k) { return null; }
            public CompletableFuture<DisplaySample> display(int id) { return null; }
        };
        StorageTerminalModule visitor = new StorageTerminalModule(plugin, TerminalSettings.defaults(true), refusing, items,
                uuid -> 50, () -> resolver);
        visitor.reload("test").join();

        visitor.onInteract(rightClick(world.getBlockAt(1, 64, 0)));
        server.getScheduler().performTicks(1);

        assertTrue(player.nextMessage().contains("Only members of this storage's town"));
        assertFalse(player.getOpenInventory().getTopInventory().getHolder(false) instanceof TerminalView);
    }

    @Test
    void aDisplayHoldsATaggedSample_andOpensReadOnly() {
        assertEquals(14, module.refreshDisplay(DISPLAY).join());
        server.getScheduler().performTicks(1);

        Inventory barrel = ContainerBlocks.inventory(world.getBlockAt(3, 64, 0));
        long stacks = java.util.Arrays.stream(barrel.getContents()).filter(i -> i != null).count();
        assertEquals(14, stacks);
        ItemStack first = java.util.Arrays.stream(barrel.getContents()).filter(i -> i != null).findFirst().orElseThrow();
        assertTrue(DisplayWriter.isSample(first));
        assertEquals(64, first.getAmount());
        assertNotNull(StorageTerminalModule.refusal(first)); // a sample can never be put in

        PlayerInteractEvent event = rightClick(world.getBlockAt(3, 64, 0));
        module.onInteract(event);
        assertTrue(event.isCancelled());
        assertInstanceOf(DisplayView.class, player.getOpenInventory().getTopInventory().getHolder(false));
    }

    @Test
    void boundBlocksCantBeBroken() {
        BlockBreakEvent bound = new BlockBreakEvent(world.getBlockAt(1, 64, 0), player);
        BlockBreakEvent free = new BlockBreakEvent(world.getBlockAt(5, 64, 0), player);

        module.onBreak(bound);
        module.onBreak(free);

        assertTrue(bound.isCancelled());
        assertFalse(free.isCancelled());
    }

    @Test
    void clicksMapToTakeAndPutKinds() {
        assertEquals(TerminalLayout.Click.LEFT, StorageTerminalModule.click(org.bukkit.event.inventory.ClickType.LEFT));
        assertEquals(TerminalLayout.Click.SHIFT, StorageTerminalModule.click(org.bukkit.event.inventory.ClickType.SHIFT_RIGHT));
        assertNull(StorageTerminalModule.click(org.bukkit.event.inventory.ClickType.DROP));
    }
}
