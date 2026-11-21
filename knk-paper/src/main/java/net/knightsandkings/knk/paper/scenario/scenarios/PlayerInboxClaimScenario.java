package net.knightsandkings.knk.paper.scenario.scenarios;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.production.inbox.DeliveryRequest;
import net.knightsandkings.knk.core.production.inbox.InboxSettings;
import net.knightsandkings.knk.core.production.inbox.InboxView;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.production.inbox.InboxModule;
import net.knightsandkings.knk.paper.production.inbox.InboxScreen;
import net.knightsandkings.knk.paper.production.inbox.RewardInbox;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * Claim with partial space (PandiO/knk-workspace#684, A15) on a real server with a scripted player: the inbox holds 100
 * bread (a stand-in API), the player's inventory has room for 10. The inbox view opens; the player's shift-click (a
 * container-click packet, so the server's click handling and the module's listener run) claims exactly 10, which land in
 * the inventory; nothing lands on the cursor. A second shift-click with a full inventory asks the API for nothing.
 */
public final class PlayerInboxClaimScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "player-inbox-claim-partial";
    }

    @Override
    public String description() {
        return "A shift-click in the reward inbox claims exactly what fits and places it; a full inventory claims nothing";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        int userId = scene.fakeId(1);
        ScenarioPlayer player = scene.player("ScnInboxClaim", p -> p.account(userId));
        ScenarioInbox inbox = new ScenarioInbox();
        inbox.view = new InboxView(userId, 1, 100, 2304, 4, null, null, false, List.of(new InboxView.Entry(ScenarioInbox.BREAD,
            "Bread", null, null, 100, Instant.now().plus(Duration.ofDays(29)), 100)));
        AtomicReference<InboxModule> module = new AtomicReference<>();
        plan.act("start an inbox module on a stand-in API", () -> {
                InboxModule m = new InboxModule(scene.services().plugin(), new InboxSettings(true, false), inbox.api,
                    ScenarioInbox.bread(), uuid -> userId, uuid -> false, new RewardInbox(inbox.api, Runnable::run, () -> null),
                    Runnable::run, Clock.systemUTC());
                module.set(m);
                m.start();
            })
            .cleanup("stop the stand-in inbox module", () -> {
                InboxModule m = module.get();
                if (m != null) {
                    m.stop();
                    HandlerList.unregisterAll(m);
                }
            })
            .act("the player joins", player::join)
            .await("they are online", 200, player::online)
            .await("they have left the join's loading hold", 200, () -> player.received("You're all set!"))
            .act("their inventory has room for 10 bread", () -> {
                player.teleport(16.5, 2, 16.5);
                ScenarioInbox.fill(player, 54);
            })
            .act("their reward inbox is opened", () -> module.get().open(player.player()))
            .await("they see the inbox", 100,
                () -> player.openInventory().getTopInventory().getHolder() instanceof InboxScreen)
            .check("the inbox shows the bread", () -> {
                ItemStack first = player.openInventory().getTopInventory().getItem(0);
                return first != null && first.getType() == Material.BREAD ? null : "slot 0 holds " + first;
            })
            .act("they shift-click the bread", () -> player.shiftClick(0))
            .await("10 bread were claimed", 100, () -> !inbox.claims.isEmpty())
            .expectEquals("the claim", () -> inbox.claims.get(0), DeliveryRequest.Line.of(ScenarioInbox.BREAD, 10))
            .await("the claimed bread is in their inventory", 40, () -> ScenarioInbox.breadIn(player) == 64)
            .check("nothing landed on their cursor", () -> {
                ItemStack cursor = player.player().getItemOnCursor();
                return cursor.getType() == Material.AIR ? null : "the cursor holds " + cursor;
            })
            .await("they are told", 40, () -> player.received("Claimed 10 items Bread"))
            .act("with a full inventory they shift-click again", () -> player.shiftClick(0))
            .await("they are told the inventory is full", 40,
                () -> player.received("Your inventory is full. Make room, then claim again."))
            .expectEquals("claims sent", inbox.claims::size, 1)
            .expectEquals("bread in their inventory", () -> ScenarioInbox.breadIn(player), 64);
    }
}
