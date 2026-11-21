package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.production.inbox.DeliveryRequest;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.kit.KitGrantPlacer;
import net.knightsandkings.knk.paper.production.inbox.RewardInbox;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * A kit for a player with a full inventory, and for a player who is offline (PandiO/knk-workspace#684, A15, production
 * DESIGN § 4.6), on a real server with a scripted player. With the reward inbox (the router {@code production.enabled}
 * builds) nothing lands on the ground: the leftovers go to {@code POST api/ItemDeliveries} (a stand-in) under the kit
 * claim's key and the player is told. The control is the flag-off path, {@code KitGrantPlacer.place} without a router:
 * the same kit drops at the player's feet as before (#261, A21). Once the player has quit, the whole kit goes to the
 * inbox.
 */
public final class PlayerKitOverflowInboxScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "player-kit-overflow-inbox";
    }

    @Override
    public String description() {
        return "A kit that doesn't fit goes to the reward inbox, not the ground; offline, the whole kit does; flag off, it drops";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        int userId = scene.fakeId(1);
        String key = "kit-claim-" + (-scene.fakeId(2));
        ScenarioPlayer player = scene.player("ScnInboxKit", p -> p.account(userId));
        ScenarioInbox inbox = new ScenarioInbox();
        AtomicReference<RewardInbox> rewards = new AtomicReference<>();
        AtomicReference<KitGrantPlacer.PlacementSummary> summary = new AtomicReference<>();
        plan.act("lay a floor and build the reward inbox on a stand-in API", () -> {
                ScenarioInbox.floor(scene);
                rewards.set(new RewardInbox(inbox.api, Runnable::run, () -> null));
            })
            .act("the player joins", player::join)
            .await("they are online", 200, player::online)
            .await("they have left the join's loading hold", 200, () -> player.received("You're all set!"))
            .act("they stand on the floor with a full inventory", () -> {
                player.teleport(16.5, 2, 16.5);
                ScenarioInbox.fill(player, 0);
            })
            .act("a kit of 24 bread and a sword is placed through the reward inbox",
                () -> summary.set(rewards.get().place(player.player(), userId, kit(), key, "Scenario kit")))
            .expectEquals("leftovers the inbox took", () -> summary.get().inboxed(), 3)
            .expectEquals("deliveries sent", inbox.deliveries::size, 1)
            .check("the delivery carries the kit claim's key and the leftovers", () -> {
                DeliveryRequest request = inbox.deliveries.get(0);
                if (!key.equals(request.idempotencyKey())) {
                    return "key " + request.idempotencyKey();
                }
                List<DeliveryRequest.Line> expected = List.of(DeliveryRequest.Line.of(ScenarioInbox.BREAD, 24),
                    DeliveryRequest.Line.of(12, 1));
                return request.lines().equals(expected) && request.userId() == userId
                    && request.reason() == DeliveryRequest.Reason.KIT ? null : "request " + request;
            })
            .await("they are told the items went to their reward inbox", 40,
                () -> player.received("25 items didn't fit and went to your reward inbox (/inbox)."))
            .waitTicks(20)
            .expectEquals("items on the ground", () -> ScenarioInbox.dropsNear(player), 0L)
            .act("control (flag off): the same kit placed without the router", () ->
                summary.set(KitGrantPlacer.place(player.player(), kit())))
            .expectEquals("leftovers dropped", () -> summary.get().dropped(), 3)
            .await("the kit lies at their feet", 40, () -> ScenarioInbox.dropsNear(player) == 3)
            .expectEquals("deliveries sent (unchanged by the control)", inbox.deliveries::size, 1)
            .act("the player quits", player::quit)
            .act("the kit is granted while they are offline",
                () -> rewards.get().deliverOffline(userId, kit(), key + "-offline", "Scenario kit"))
            .expectEquals("deliveries sent", inbox.deliveries::size, 2)
            .check("the whole kit went to the inbox", () -> {
                DeliveryRequest request = inbox.deliveries.get(1);
                return request.totalAmount() == 25 && request.userId() == userId ? null : "request " + request;
            });
    }

    private static List<KitGrantPlacer.ResolvedItem> kit() {
        return List.of(KitGrantPlacer.ResolvedItem.content(0, new ItemStack(Material.BREAD, 16), 64, ScenarioInbox.BREAD),
            KitGrantPlacer.ResolvedItem.content(1, new ItemStack(Material.BREAD, 8), 64, ScenarioInbox.BREAD),
            KitGrantPlacer.ResolvedItem.content(2, new ItemStack(Material.IRON_SWORD), 1, 12));
    }
}
