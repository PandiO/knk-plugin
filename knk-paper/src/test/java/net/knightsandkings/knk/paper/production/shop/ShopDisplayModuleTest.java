package net.knightsandkings.knk.paper.production.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import net.knightsandkings.knk.core.ports.api.ShopDisplaySpotsApi;
import net.knightsandkings.knk.core.production.shop.ShopItemRef;
import net.knightsandkings.knk.core.production.shop.ShopMenuKeys;
import net.knightsandkings.knk.core.production.shop.display.DisplaySettings;
import net.knightsandkings.knk.core.production.shop.display.DisplaySpot;
import net.knightsandkings.knk.paper.menu.MenuService;
import net.knightsandkings.knk.paper.production.terminal.BlueprintItems;

/**
 * Display stalls P12 (PandiO/knk-workspace#250, shops DESIGN § 7) with MockBukkit: the registered spots show the bakery's
 * items as non-persistent item displays with an Interaction and a price label (pinned bread first, the in-stock sword,
 * then the sold-out flour greyed out); right-click opens the item information menu and shift-right-click the purchase
 * confirmation (the quick buy, never a purchase); stall entities that went away are spawned again and stray copies are
 * removed; a trade makes the stalls choose again; spots are removed and pinned through the API.
 */
class ShopDisplayModuleTest {

    private ServerMock server;
    private WorldMock world;
    private PlayerMock player;
    private Plugin plugin;
    private final FakeShopsApi shopsApi = new FakeShopsApi();
    private final FakeSpots spotsApi = new FakeSpots();
    private final MenuService menus = mock(MenuService.class);
    private final List<String> opened = new ArrayList<>();
    private final MutableClock clock = new MutableClock();
    private ShopService shops;
    private ShopDisplayModule module;

    private final BlueprintItems items = new BlueprintItems(null, null) {
        @Override
        public Template cached(int blueprintId) {
            Material m = blueprintId == 11 ? Material.BREAD : blueprintId == 15 ? Material.WHEAT : Material.IRON_SWORD;
            return new Template(new ItemStack(m), 64, true);
        }

        @Override
        public CompletableFuture<Template> template(int blueprintId, String fallbackName) {
            return CompletableFuture.completedFuture(cached(blueprintId));
        }
    };

    /** The spots in memory, recording calls. */
    static final class FakeSpots implements ShopDisplaySpotsApi {
        final Map<Integer, DisplaySpot> spots = new HashMap<>();
        final List<Integer> deleted = new ArrayList<>();
        final List<Integer> soldReads = new ArrayList<>();
        Map<Integer, Long> unitsSold = Map.of();

        void add(int id, int x, Integer pin, int order) {
            spots.put(id, new DisplaySpot(id, 5, "world", x, 64, 0, DisplaySpot.Facing.EAST, pin, order, null, true));
        }

        @Override
        public CompletableFuture<List<DisplaySpot>> list(Integer shopId) {
            return CompletableFuture.completedFuture(new ArrayList<>(spots.values()));
        }

        @Override
        public CompletableFuture<DisplaySpot> create(SpotDraft d) {
            DisplaySpot spot = new DisplaySpot(50, d.shopId(), d.world(), d.x(), d.y(), d.z(), d.facing(), null, 9, d.label(), true);
            spots.put(spot.id(), spot);
            return CompletableFuture.completedFuture(spot);
        }

        @Override
        public CompletableFuture<DisplaySpot> pin(int spotId, Integer entryId) {
            DisplaySpot s = spots.get(spotId);
            DisplaySpot pinned = new DisplaySpot(s.id(), s.shopId(), s.world(), s.x(), s.y(), s.z(), s.facing(), entryId, s.sortOrder(),
                    s.label(), s.enabled());
            spots.put(spotId, pinned);
            return CompletableFuture.completedFuture(pinned);
        }

        @Override
        public CompletableFuture<Void> delete(int spotId) {
            deleted.add(spotId);
            spots.remove(spotId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Map<Integer, Long>> unitsSold(int shopId, Instant since) {
            soldReads.add(shopId);
            return CompletableFuture.completedFuture(unitsSold);
        }
    }

    static final class MutableClock extends Clock {
        long millis = 1_000_000L;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        world.getChunkAt(0, 0).load();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        shops = new ShopService(shopsApi, items, uuid -> 3, uuid -> 1000L, uuid -> false, Runnable::run, clock, () -> menus);
        spotsApi.add(1, 0, null, 1);
        spotsApi.add(2, 2, null, 2);
        spotsApi.add(3, 4, null, 3);
    }

    @AfterEach
    void tearDown() {
        if (module != null) {
            module.stop();
        }
        MockBukkit.unmock();
    }

    private ShopDisplayModule start(ShopDisplayModule.Opener opener) {
        module = new ShopDisplayModule(plugin, DisplaySettings.defaults(true), spotsApi, shops, opener, Runnable::run, clock);
        module.start();
        module.reload("test").join();
        return module;
    }

    private ShopDisplayModule startRecording() {
        return start((p, shopId, ref, quickBuy) -> {
            opened.add(shopId + " " + ref + " " + (quickBuy ? "quick" : "info"));
            return CompletableFuture.completedFuture(null);
        });
    }

    private <T extends Entity> List<T> stall(Class<T> type, int spotId) {
        List<T> out = new ArrayList<>();
        for (Entity e : world.getEntities()) {
            Integer id = e.getPersistentDataContainer().get(ShopDisplayPresenter.SPOT_KEY, PersistentDataType.INTEGER);
            if (type.isInstance(e) && id != null && id == spotId && e.isValid()) {
                out.add(type.cast(e));
            }
        }
        return out;
    }

    private static String text(TextDisplay label) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(label.text());
    }

    @Test
    void theSpotsShowPinsFirst_thenInStock_thenSoldOutGreyed_asNonPersistentEntities() {
        startRecording();

        ItemDisplay bread = stall(ItemDisplay.class, 1).get(0);
        assertEquals(Material.BREAD, bread.getItemStack().getType());
        assertEquals(Material.IRON_SWORD, stall(ItemDisplay.class, 2).get(0).getItemStack().getType());
        ItemDisplay flour = stall(ItemDisplay.class, 3).get(0);
        assertEquals(Material.WHEAT, flour.getItemStack().getType());
        assertEquals(ShopDisplayPresenter.GREYED, flour.getBrightness());
        assertNull(bread.getBrightness());
        assertEquals(ItemDisplay.ItemDisplayTransform.FIXED, bread.getItemDisplayTransform());
        assertEquals(DisplaySpot.Facing.EAST.yaw(), bread.getLocation().getYaw());
        assertTrue(text(stall(TextDisplay.class, 1).get(0)).contains("4 coins"));
        assertTrue(text(stall(TextDisplay.class, 3).get(0)).contains("Sold out"));
        assertEquals(1, stall(Interaction.class, 2).size());
        for (Entity e : world.getEntities()) {
            if (ShopDisplayPresenter.isStallEntity(e)) {
                assertFalse(e.isPersistent(), "stall entities are non-persistent");
            }
        }
        assertEquals(64 + 1.0, stall(Interaction.class, 1).get(0).getLocation().getY());
    }

    @Test
    void rightClickOpensTheItemInformation_shiftRightClickTheQuickBuy_andTheOffHandIsIgnored() {
        startRecording();
        Interaction hitbox = stall(Interaction.class, 1).get(0);

        PlayerInteractEntityEvent click = new PlayerInteractEntityEvent(player, hitbox, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(click);
        assertTrue(click.isCancelled());
        assertEquals(List.of("5 e12 info"), opened);

        PlayerInteractEntityEvent offHand = new PlayerInteractEntityEvent(player, hitbox, EquipmentSlot.OFF_HAND);
        server.getPluginManager().callEvent(offHand);
        assertTrue(offHand.isCancelled());

        clock.millis += 1_000;
        player.setSneaking(true);
        server.getPluginManager().callEvent(new PlayerInteractEntityEvent(player, stall(Interaction.class, 2).get(0), EquipmentSlot.HAND));
        assertEquals(List.of("5 e12 info", "5 i900 quick"), opened);

        server.getPluginManager().callEvent(new PlayerInteractEntityEvent(player, stall(Interaction.class, 1).get(0), EquipmentSlot.HAND));
        assertEquals(2, opened.size(), "a second click within the debounce is the same click");
    }

    @Test
    void theQuickBuyOpensTheConfirmation_withTheAmount_andNeverBuys() {
        start(null); // the real opener: ShopService.openItem
        player.setSneaking(true);
        server.getPluginManager().callEvent(new PlayerInteractEntityEvent(player, stall(Interaction.class, 1).get(0), EquipmentSlot.HAND));

        verify(menus).openMenu(eq(player), eq(ShopMenuKeys.CONFIRM_MENU), argThat(ctx -> "5".equals(ctx.get(ShopMenuKeys.CTX_SHOP_ID))
                && "e12".equals(ctx.get(ShopMenuKeys.CTX_ITEM)) && "1".equals(ctx.get(ShopMenuKeys.CTX_AMOUNT))));
        assertTrue(shopsApi.purchases.isEmpty(), "a quick buy only opens the confirmation");

        clock.millis += 1_000;
        player.setSneaking(false);
        server.getPluginManager().callEvent(new PlayerInteractEntityEvent(player, stall(Interaction.class, 1).get(0), EquipmentSlot.HAND));
        verify(menus).openMenu(eq(player), eq(ShopMenuKeys.ITEM_MENU), argThat(ctx -> "e12".equals(ctx.get(ShopMenuKeys.CTX_ITEM))
                && !ctx.has(ShopMenuKeys.CTX_AMOUNT)));
    }

    @Test
    void aQuickBuyOfASoldOutOrUniqueItem_isRefusedWithoutAMenu() {
        start(null);
        shops.openItem(player, 5, ShopItemRef.parse("b15"), true).join();
        assertTrue(player.nextMessage().contains("sold out"));
        shops.openItem(player, 5, ShopItemRef.parse("i900"), true).join();
        assertTrue(player.nextMessage().contains("Unique items"));
        verify(menus, never()).openMenu(eq(player), eq(ShopMenuKeys.CONFIRM_MENU), any());
        shops.openItem(player, 5, ShopItemRef.parse("b15"), false).join(); // the information is still shown
        verify(menus).openMenu(eq(player), eq(ShopMenuKeys.ITEM_MENU), any());
    }

    @Test
    void entitiesThatWentAway_areSpawnedAgain_andStrayCopiesAreRemoved() {
        startRecording();
        ItemDisplay before = stall(ItemDisplay.class, 1).get(0);
        stall(Entity.class, 1).forEach(Entity::remove);
        assertTrue(stall(ItemDisplay.class, 1).isEmpty());

        module.respawn();

        ItemDisplay after = stall(ItemDisplay.class, 1).get(0);
        assertNotEquals(before.getUniqueId(), after.getUniqueId());
        assertEquals(Material.BREAD, after.getItemStack().getType());

        ItemDisplay stray = world.spawn(after.getLocation(), ItemDisplay.class,
                e -> e.getPersistentDataContainer().set(ShopDisplayPresenter.SPOT_KEY, PersistentDataType.INTEGER, 1));
        assertEquals(1, module.presenter().purgeOrphans(world.getEntities()));
        assertFalse(stray.isValid());
        assertTrue(after.isValid());
    }

    @Test
    void bestSellersFillTheFreeSpots_andATradeMakesTheStallsChooseAgain() {
        spotsApi.spots.remove(3);
        startRecording();
        assertEquals(Material.IRON_SWORD, stall(ItemDisplay.class, 2).get(0).getItemStack().getType());

        shopsApi.unitPrice = 6;
        shops.forget(5); // what a purchase does
        assertTrue(text(stall(TextDisplay.class, 1).get(0)).contains("6 coins"));
        assertEquals(1, spotsApi.soldReads.size(), "best sellers are read again only after their TTL");
    }

    @Test
    void aRemovedSpotsEntitiesGo_andAPinMovesAnItem() {
        startRecording();
        module.pin(3, 12).join(); // spot 3 now always shows the bread
        assertEquals(Material.BREAD, stall(ItemDisplay.class, 3).get(0).getItemStack().getType());
        assertEquals(Material.IRON_SWORD, stall(ItemDisplay.class, 1).get(0).getItemStack().getType());

        module.remove(3).join();
        assertEquals(List.of(3), spotsApi.deleted);
        assertTrue(stall(Entity.class, 3).isEmpty());
        assertTrue(module.index().byId(3).isEmpty());
        assertEquals(Material.BREAD, stall(ItemDisplay.class, 1).get(0).getItemStack().getType());
        assertFalse(module.describe(5).isEmpty());
    }

    @Test
    void aClickOnAnEmptySpot_saysSo() {
        spotsApi.add(4, 6, null, 4); // a fourth spot: the offer has only three items
        startRecording();
        assertTrue(stall(Entity.class, 4).isEmpty());
        assertFalse(module.click(player, 4, false));
        assertTrue(player.nextMessage().contains("Nothing is on display"));
        assertTrue(opened.isEmpty());
    }

    @Test
    void stoppingRemovesEveryStallEntity() {
        startRecording();
        module.stop();
        module = null;
        for (Entity e : world.getEntities()) {
            assertFalse(ShopDisplayPresenter.isStallEntity(e) && e.isValid());
        }
    }
}
