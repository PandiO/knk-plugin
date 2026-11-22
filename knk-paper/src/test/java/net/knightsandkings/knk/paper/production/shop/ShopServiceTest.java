package net.knightsandkings.knk.paper.production.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.production.shop.ShopMenuKeys;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseRequest;
import net.knightsandkings.knk.core.production.shop.ShopRefusal;
import net.knightsandkings.knk.paper.menu.MenuService;
import net.knightsandkings.knk.paper.production.terminal.BlueprintItems;

/**
 * PandiO/knk-workspace#687 (shops DESIGN § 3.1, § 8, A18): the purchase confirmation sends the price the player saw,
 * {@code confirmed}, the channel and one key per click; places exactly what was bought; asks again on
 * {@code PriceChanged}; tells every other refusal; refuses a full inventory, a unique item and a siege without a call.
 */
class ShopServiceTest {

    private ServerMock server;
    private PlayerMock player;
    private final FakeShopsApi api = new FakeShopsApi();
    private final MenuService menus = mock(MenuService.class);
    private final Set<UUID> inSiege = new HashSet<>();
    private ShopService service;

    private final BlueprintItems items = new BlueprintItems(null, null) {
        @Override
        public Template cached(int blueprintId) {
            return new Template(new ItemStack(Material.BREAD), 64, true);
        }

        @Override
        public CompletableFuture<Template> template(int blueprintId, String fallbackName) {
            return CompletableFuture.completedFuture(cached(blueprintId));
        }
    };

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        player = server.addPlayer();
        service = new ShopService(api, items, uuid -> 3, uuid -> 1000L, inSiege::contains, Runnable::run, Clock.systemUTC(),
                () -> menus);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static MenuContextParams ctx(String item, String amount) {
        Map<String, String> values = new java.util.HashMap<>();
        values.put(ShopMenuKeys.CTX_SHOP_ID, "5");
        if (item != null) {
            values.put(ShopMenuKeys.CTX_ITEM, item);
        }
        if (amount != null) {
            values.put(ShopMenuKeys.CTX_AMOUNT, amount);
        }
        return MenuContextParams.of(values);
    }

    /** Opens the confirmation once so the offer and the quote are read (the menus read before a click). */
    private void readConfirmation(String item, String amount) {
        service.view(player, ctx(item, amount));
        service.view(player, ctx(item, amount));
    }

    private int bread() {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == Material.BREAD) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    @Test
    void open_readsTheOfferAndOpensTheShopMenu() {
        assertTrue(service.open(player, 5).join());
        verify(menus).openMenu(eq(player), eq(ShopMenuKeys.SHOP_MENU), eq(MenuContextParams.of(Map.of("shopId", "5"))));
        assertEquals(3, service.offerRows(player, ctx(null, null), null).join().items().size());
    }

    @Test
    void view_cyclesTheAmountAndShowsTheTotal() {
        service.view(player, ctx("e12", null));
        assertEquals("1", service.view(player, ctx("e12", null)).getAmount());
        service.cycleAmount(player, ctx("e12", null));
        ShopView view = service.view(player, ctx("e12", null));
        assertEquals("8", view.getAmount());
        assertTrue(view.getPriceLines().contains("&7Total: &632 coins"));
        assertEquals("1", service.view(player, ctx("i900", null)).getAmount()); // unique: one
    }

    @Test
    void purchase_toTheInventory_sendsThePriceSeenAndPlacesWhatWasBought() {
        readConfirmation("e12", "8");

        var result = service.purchase(player, ctx("e12", "8"), ShopPurchaseRequest.DeliverTo.INVENTORY).join();

        assertEquals(8, result.amount());
        ShopPurchaseRequest sent = api.purchases.get(0);
        assertEquals(4L, sent.expectedUnitPrice());
        assertTrue(sent.confirmed());
        assertEquals(ShopPurchaseRequest.DeliverTo.INVENTORY, sent.deliverTo());
        assertEquals(12, sent.item().catalogueEntryId());
        assertTrue(sent.idempotencyKey().startsWith("buy-s5-u3-"));
        assertEquals(8, bread());
        assertTrue(player.nextMessage().contains("You bought"));
        verify(menus).goBack(player);
    }

    @Test
    void purchase_toTheInbox_placesNothing() {
        readConfirmation("e12", "16");

        service.purchase(player, ctx("e12", "16"), ShopPurchaseRequest.DeliverTo.INBOX).join();

        assertEquals(ShopPurchaseRequest.DeliverTo.INBOX, api.purchases.get(0).deliverTo());
        assertEquals(0, bread());
        assertTrue(player.nextMessage().contains("/inbox"));
    }

    @Test
    void purchase_priceChanged_asksAgainWithTheNewPrice() {
        readConfirmation("e12", "1");
        api.unitPrice = 5;
        api.failures.add(FakeShopsApi.conflict("PriceChanged"));

        assertNull(service.purchase(player, ctx("e12", "1"), ShopPurchaseRequest.DeliverTo.INVENTORY).join());

        assertEquals(0, bread());
        assertEquals("§e" + ShopRefusal.PRICE_CHANGED.message(), player.nextMessage());
        readConfirmation("e12", "1");
        ShopView view = service.view(player, ctx("e12", "1"));
        assertTrue(view.getConfirmLines().contains("&eThe price changed from 4 coins. Confirm again to buy."),
                view.getConfirmLines().toString());
        verify(menus, never()).goBack(any());

        service.purchase(player, ctx("e12", "1"), ShopPurchaseRequest.DeliverTo.INVENTORY).join();
        assertEquals(5L, api.purchases.get(1).expectedUnitPrice());
        assertNotEquals(api.purchases.get(0).idempotencyKey(), api.purchases.get(1).idempotencyKey());
        assertEquals(1, bread());
    }

    @Test
    void purchase_everyOtherRefusalIsToldAndNothingIsPlaced() {
        for (String code : List.of("InsufficientFunds", "SoldOut", "NotEnoughStock", "InboxFull", "StorageLocked",
                "NoTown", "ProductionDisabled", "NotForSale", "NotConfirmed", "IdempotencyKeyReuse")) {
            readConfirmation("e12", "1");
            api.failures.add(FakeShopsApi.conflict(code));
            service.purchase(player, ctx("e12", "1"), ShopPurchaseRequest.DeliverTo.INVENTORY).join();
            assertEquals("§c" + ShopRefusal.of(code).message() + " Nothing was charged.", player.nextMessage(), code);
        }
        assertEquals(0, bread());
    }

    @Test
    void purchase_lostAnswer_isSentOnceMoreWithTheSameKey() {
        readConfirmation("e12", "1");
        api.failures.add(FakeShopsApi.lost());

        service.purchase(player, ctx("e12", "1"), ShopPurchaseRequest.DeliverTo.INVENTORY).join();

        assertEquals(2, api.purchases.size());
        assertEquals(api.purchases.get(0).idempotencyKey(), api.purchases.get(1).idempotencyKey());
        assertEquals(1, bread());
    }

    @Test
    void purchase_refusedWithoutACall_fullInventoryUniqueItemSiege() {
        readConfirmation("e12", "64");
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        service.purchase(player, ctx("e12", "64"), ShopPurchaseRequest.DeliverTo.INVENTORY).join();
        assertTrue(player.nextMessage().contains("no room"));

        readConfirmation("i900", "1");
        service.purchase(player, ctx("i900", "1"), ShopPurchaseRequest.DeliverTo.INBOX).join();
        assertTrue(player.nextMessage().contains("Unique items"));

        inSiege.add(player.getUniqueId());
        service.purchase(player, ctx("e12", "1"), ShopPurchaseRequest.DeliverTo.INBOX).join();
        assertTrue(player.nextMessage().contains("siege"));

        assertTrue(api.purchases.isEmpty());
    }

    @Test
    void conditions_buysAndBuyable() {
        service.offer(5);
        assertTrue(service.buys(ctx(null, null)));
        assertNull(service.notBuyable(ctx("e12", null)));
        assertEquals(ShopRefusal.SOLD_OUT.message(), service.notBuyable(ctx("b15", null)));
        assertTrue(service.notBuyable(ctx("i900", null)).contains("Unique"));
        assertTrue(service.notBuyable(ctx("e99", null)).contains("no longer"));
    }
}
