package net.knightsandkings.knk.paper.scenario.scenarios;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.ports.api.ShopDisplaySpotsApi;
import net.knightsandkings.knk.core.production.shop.ShopMenuKeys;
import net.knightsandkings.knk.core.production.shop.ShopPurchaseRequest;
import net.knightsandkings.knk.core.production.shop.display.DisplaySettings;
import net.knightsandkings.knk.core.production.shop.display.DisplaySpot;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.production.shop.ShopDisplayModule;
import net.knightsandkings.knk.paper.production.shop.ShopDisplayPresenter;
import net.knightsandkings.knk.paper.production.shop.ShopService;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * A display stall on a real server (display stalls P12, PandiO/knk-workspace#250, shops DESIGN § 7) with a stand-in API:
 * a barrel registered as the bakery's display spot shows its bread as a non-persistent item display with an Interaction
 * and a price label; when its entities are removed they are spawned again; a right-click (the server's entity interact
 * event) asks for the item information and a shift-right-click for the quick buy, which goes through the purchase
 * confirmation; confirming it buys exactly one bread into the inventory.
 */
public final class ShopDisplayStallScenario implements Scenario<ScenarioScene> {

    static final int SPOT = 701;

    @Override
    public String name() {
        return "shop-display-stall";
    }

    @Override
    public String description() {
        return "A display spot shows the shop's bread, respawns its entities, opens the item info and a confirmed quick buy";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        int userId = scene.fakeId(1);
        ScenarioPlayer player = scene.player("ScnShopStall", p -> p.account(userId));
        ScenarioShops shops = new ScenarioShops();
        AtomicReference<ShopService> service = new AtomicReference<>();
        AtomicReference<ShopDisplayModule> module = new AtomicReference<>();
        List<String> opened = new CopyOnWriteArrayList<>();
        List<String> firstEntities = new ArrayList<>();
        AtomicLong lastClick = new AtomicLong();
        DisplaySpot spot = new DisplaySpot(SPOT, ScenarioShops.SHOP, scene.world().getName(), scene.worldX(18), scene.worldY(1),
                scene.worldZ(16), DisplaySpot.Facing.WEST, null, 1, "Scenario shelf", true);
        ShopDisplaySpotsApi spots = new ShopDisplaySpotsApi() {
            public CompletableFuture<List<DisplaySpot>> list(Integer shopId) {
                return CompletableFuture.completedFuture(List.of(spot));
            }

            public CompletableFuture<DisplaySpot> create(SpotDraft draft) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }

            public CompletableFuture<DisplaySpot> pin(int spotId, Integer catalogueEntryId) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }

            public CompletableFuture<Void> delete(int spotId) {
                return CompletableFuture.completedFuture(null);
            }

            public CompletableFuture<Map<Integer, Long>> unitsSold(int shopId, Instant since) {
                return CompletableFuture.completedFuture(Map.of(ScenarioShops.BREAD, 20L));
            }
        };
        MenuContextParams confirm = MenuContextParams.of(Map.of(ShopMenuKeys.CTX_SHOP_ID, String.valueOf(ScenarioShops.SHOP),
                ShopMenuKeys.CTX_ITEM, "e12", ShopMenuKeys.CTX_AMOUNT, "1"));

        plan.act("build the stall and the floor", () -> {
                ScenarioInbox.floor(scene);
                scene.block(18, 1, 16).setType(Material.BARREL, false);
            })
            .act("start the shop service and the display stalls on a stand-in API", () -> {
                ShopService s = new ShopService(shops.api, ScenarioInbox.bread(), uuid -> userId, uuid -> 100L, uuid -> false,
                    Runnable::run, Clock.systemUTC(), () -> null);
                ShopDisplayModule m = new ShopDisplayModule(scene.services().plugin(), DisplaySettings.defaults(true), spots, s,
                    (p, shopId, ref, quickBuy) -> {
                        opened.add((quickBuy ? "quick " : "info ") + ref);
                        return s.openItem(p, shopId, ref, quickBuy);
                    }, Runnable::run, Clock.systemUTC());
                service.set(s);
                module.set(m);
                m.start();
                m.reload("scenario");
            })
            .cleanup("stop the stand-in display stalls", () -> {
                ShopDisplayModule m = module.get();
                if (m != null) {
                    m.stop();
                    HandlerList.unregisterAll(m);
                }
            })
            .await("the stall shows the bread", 100, () -> stall(scene, ItemDisplay.class).stream()
                .anyMatch(d -> d.getItemStack().getType() == Material.BREAD))
            .expect("with a hitbox for the clicks", () -> stall(scene, Interaction.class).size() == 1)
            .expect("and a price label", () -> stall(scene, TextDisplay.class).stream()
                .anyMatch(t -> plain(t).contains("Bread") && plain(t).contains("4 coins")))
            .expect("on top of the barrel", () -> stall(scene, Interaction.class).get(0).getLocation().getBlockY() == scene.worldY(2))
            .expect("none of them saved with the chunk", () -> stall(scene, Entity.class).stream().noneMatch(Entity::isPersistent))
            .act("something removes the stall's entities", () -> {
                for (Entity e : stall(scene, Entity.class)) {
                    firstEntities.add(e.getUniqueId().toString());
                    e.remove();
                }
            })
            .await("they are spawned again", 60, () -> stall(scene, ItemDisplay.class).stream()
                .anyMatch(d -> !firstEntities.contains(d.getUniqueId().toString())))
            .expect("once", () -> stall(scene, ItemDisplay.class).size() == 1 && stall(scene, Interaction.class).size() == 1)
            .act("the player joins", player::join)
            .await("they are online", 200, player::online)
            .await("they have left the join's loading hold", 200, () -> player.received("You're all set!"))
            .act("they stand at the stall with an empty inventory", () -> {
                player.teleport(16.5, 2, 16.5);
                player.inventory().clear();
            })
            .act("they right-click the bread", () -> {
                lastClick.set(System.currentTimeMillis());
                click(player, stall(scene, Interaction.class).get(0));
            })
            .await("the item information is asked for", 40, () -> opened.contains("info e12"))
            .await("a moment passes", 40, () -> System.currentTimeMillis() - lastClick.get() > 400)
            .act("they shift-right-click it", () -> {
                player.player().setSneaking(true);
                click(player, stall(scene, Interaction.class).get(0));
                player.player().setSneaking(false);
            })
            .await("the quick buy is asked for", 40, () -> opened.contains("quick e12"))
            .expectEquals("nothing is bought without the confirmation", () -> shops.purchases.size(), 0)
            .act("they read the confirmation of 1 bread", () -> {
                service.get().view(player.player(), confirm);
                service.get().view(player.player(), confirm);
            })
            .act("they confirm it into their inventory",
                () -> service.get().purchase(player.player(), confirm, ShopPurchaseRequest.DeliverTo.INVENTORY))
            .await("the purchase was sent", 100, () -> !shops.purchases.isEmpty())
            .expectEquals("confirmed", () -> shops.purchases.get(0).confirmed(), true)
            .expectEquals("one bread", () -> shops.purchases.get(0).amount(), 1L)
            .await("1 bread is in their inventory", 40, () -> ScenarioInbox.breadIn(player) == 1);
    }

    private static void click(ScenarioPlayer player, Entity hitbox) {
        org.bukkit.Bukkit.getPluginManager().callEvent(new PlayerInteractEntityEvent(player.player(), hitbox, EquipmentSlot.HAND));
    }

    private static <T extends Entity> List<T> stall(ScenarioScene scene, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Entity e : scene.world().getEntities()) {
            Integer id = e.getPersistentDataContainer().get(ShopDisplayPresenter.SPOT_KEY, PersistentDataType.INTEGER);
            if (type.isInstance(e) && id != null && id == SPOT && e.isValid()) {
                out.add(type.cast(e));
            }
        }
        return out;
    }

    private static String plain(TextDisplay label) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(label.text());
    }
}
