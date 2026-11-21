package net.knightsandkings.knk.paper.scenario.scenarios;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.production.shop.SellMenuLayout;
import net.knightsandkings.knk.core.production.shop.ShopMenuKeys;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseRequest;
import net.knightsandkings.knk.core.production.shop.ShopSellLine;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.production.shop.SellModule;
import net.knightsandkings.knk.paper.production.shop.SellView;
import net.knightsandkings.knk.paper.production.shop.ShopService;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * A sale and a purchase at a shop (PandiO/knk-workspace#687, A18) on a real server with a scripted player and a stand-in
 * shops API. The player opens the bakery's sell menu, clicks the 20 bread in their hotbar (a container-click packet, so
 * the server's click handling and the module's listener run): the sell quote asks for one line of 20 bread and the menu
 * shows it; a click on the Sell chest sells it at the total shown (40 coins) and the bread leaves the inventory only
 * then. Then the purchase confirmation's action buys 8 bread into the inventory at the quoted 4 coins each, and exactly 8
 * bread land in it.
 */
public final class PlayerShopSellAndBuyScenario implements Scenario<ScenarioScene> {

    /** Raw slot of hotbar slot 0 under a 36-slot menu (36 menu slots, then 27 main-inventory slots). */
    static final int HOTBAR_0 = SellMenuLayout.SIZE + 27;

    @Override
    public String name() {
        return "player-shop-sell-and-buy";
    }

    @Override
    public String description() {
        return "The sell menu sells the clicked bread at the quoted total; the confirmation buys 8 bread into the inventory";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        int userId = scene.fakeId(1);
        ScenarioPlayer player = scene.player("ScnShopTrade", p -> p.account(userId));
        ScenarioShops shops = new ScenarioShops();
        AtomicReference<ShopService> service = new AtomicReference<>();
        AtomicReference<SellModule> module = new AtomicReference<>();
        MenuContextParams confirm = MenuContextParams.of(Map.of(ShopMenuKeys.CTX_SHOP_ID, String.valueOf(ScenarioShops.SHOP),
                ShopMenuKeys.CTX_ITEM, "e12", ShopMenuKeys.CTX_AMOUNT, "8"));
        plan.act("start the shop service and the sell menu on a stand-in API", () -> {
                ShopService s = new ShopService(shops.api, ScenarioInbox.bread(), uuid -> userId, uuid -> 100L, uuid -> false,
                    Runnable::run, Clock.systemUTC(), () -> null);
                SellModule m = new SellModule(scene.services().plugin(), s, uuid -> userId, uuid -> 100L, uuid -> false,
                    ScenarioShops::identity, Runnable::run, () -> null);
                service.set(s);
                module.set(m);
                m.start();
                s.offer(ScenarioShops.SHOP);
            })
            .cleanup("stop the stand-in sell menu", () -> {
                SellModule m = module.get();
                if (m != null) {
                    m.stop();
                    HandlerList.unregisterAll(m);
                }
            })
            .act("the player joins", player::join)
            .await("they are online", 200, player::online)
            .await("they have left the join's loading hold", 200, () -> player.received("You're all set!"))
            .act("they hold 20 bread and nothing else", () -> {
                player.teleport(16.5, 2, 16.5);
                player.inventory().clear();
                player.inventory().setItem(0, new ItemStack(Material.BREAD, 20));
            })
            .act("they open the bakery's sell menu", () -> module.get().open(player.player(), ScenarioShops.SHOP))
            .await("they see the sell menu", 100,
                () -> player.openInventory().getTopInventory().getHolder() instanceof SellView)
            .act("they click the bread in their hotbar", () -> player.click(HOTBAR_0))
            .await("the shop was asked for a price", 100, () -> !shops.sellQuotes.isEmpty())
            .expectEquals("the quoted line", () -> shops.sellQuotes.get(0).lines(),
                java.util.List.of(ShopSellLine.blueprint(ScenarioShops.BREAD, 20)))
            .await("the picked bread shows in the drop zone", 40, () -> {
                ItemStack shown = player.openInventory().getTopInventory().getItem(SellMenuLayout.pickSlot(0));
                return shown != null && shown.getType() == Material.BREAD;
            })
            .expectEquals("bread still in their inventory before the sale", () -> ScenarioInbox.breadIn(player), 20)
            .act("they click the Sell chest", () -> player.click(SellMenuLayout.SELL_SLOT))
            .await("the sale was sent", 100, () -> !shops.sales.isEmpty())
            .expectEquals("the total they saw", () -> shops.sales.get(0).expectedTotal(), 40L)
            .await("the bread left their inventory", 40, () -> ScenarioInbox.breadIn(player) == 0)
            .await("they are told", 40, () -> player.received("You sold 1 item stack for 40 coins"))
            .act("they read the confirmation of 8 bread", () -> {
                service.get().view(player.player(), confirm);
                service.get().view(player.player(), confirm);
            })
            .act("they confirm the purchase into their inventory",
                () -> service.get().purchase(player.player(), confirm, ShopPurchaseRequest.DeliverTo.INVENTORY))
            .await("the purchase was sent", 100, () -> !shops.purchases.isEmpty())
            .expectEquals("the price they saw", () -> shops.purchases.get(0).expectedUnitPrice(), 4L)
            .expectEquals("confirmed", () -> shops.purchases.get(0).confirmed(), true)
            .await("8 bread are in their inventory", 40, () -> ScenarioInbox.breadIn(player) == 8)
            .await("they are told", 40, () -> player.received("You bought 8 x Bread for 32 coins"));
    }
}
