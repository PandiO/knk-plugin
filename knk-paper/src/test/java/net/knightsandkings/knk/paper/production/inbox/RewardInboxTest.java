package net.knightsandkings.knk.paper.production.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.items.identity.ItemIdentityResolution;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.LootboxDeliveryMethod;
import net.knightsandkings.knk.core.ports.api.ItemDeliveryApi;
import net.knightsandkings.knk.core.production.inbox.ClaimResult;
import net.knightsandkings.knk.core.production.inbox.DeliveryRequest;
import net.knightsandkings.knk.core.production.inbox.DeliveryResult;
import net.knightsandkings.knk.core.production.inbox.InboxView;
import net.knightsandkings.knk.paper.kit.KitGrantPlacer;

/**
 * PandiO/knk-workspace#684 (A15, DESIGN § 4.6): what doesn't fit goes to the reward inbox under a stable key; a refusal
 * (409 ProductionDisabled) falls back to today's drop; unique items never go; a lost answer is sent again with the same
 * key; an offline player's kit goes to the inbox whole; lootbox leftovers are confirmed after the inbox answered.
 */
class RewardInboxTest {

    private ServerMock server;
    private WorldMock world;
    private PlayerMock player;
    private final List<DeliveryRequest> sent = new ArrayList<>();
    private final List<CompletableFuture<DeliveryResult>> answers = new ArrayList<>();
    private Function<ItemStack, ItemIdentityResolution> resolver = null;

    private final ItemDeliveryApi api = new ItemDeliveryApi() {
        public CompletableFuture<DeliveryResult> deliver(DeliveryRequest request) {
            sent.add(request);
            if (!answers.isEmpty()) {
                return answers.remove(0);
            }
            return CompletableFuture.completedFuture(new DeliveryResult(55, "Inboxed", request.totalAmount(), 0, 2304, null, false));
        }

        public CompletableFuture<InboxView> inbox(int userId) {
            return CompletableFuture.completedFuture(InboxView.empty(userId));
        }

        public CompletableFuture<ClaimResult> claim(int userId, String key, List<DeliveryRequest.Line> lines) {
            throw new UnsupportedOperationException();
        }
    };

    private RewardInbox rewards;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        player = server.addPlayer();
        player.teleport(world.getSpawnLocation());
        rewards = new RewardInbox(api, Runnable::run, () -> resolver);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Every slot, armour and off hand too (MockBukkit's firstEmpty looks at all of them). */
    private void fillInventory() {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        }
    }

    private List<KitGrantPlacer.ResolvedItem> kit() {
        return List.of(KitGrantPlacer.ResolvedItem.content(0, new ItemStack(Material.BREAD, 16), 64, 11),
                KitGrantPlacer.ResolvedItem.content(1, new ItemStack(Material.BREAD, 8), 64, 11),
                KitGrantPlacer.ResolvedItem.content(2, new ItemStack(Material.IRON_SWORD), 1, 12));
    }

    private long droppedItems() {
        return world.getEntities().stream().filter(e -> e instanceof Item).count();
    }

    @Test
    void kitWithAFullInventory_leftoversGoToTheInbox_notTheGround() {
        fillInventory();

        KitGrantPlacer.PlacementSummary summary = rewards.place(player, 3, kit(), "kit-claim-12", "Starter");

        assertEquals(3, summary.inboxed());
        assertEquals(0, summary.dropped());
        assertEquals(0, droppedItems());
        assertEquals(1, sent.size());
        DeliveryRequest request = sent.get(0);
        assertEquals(3, request.userId());
        assertEquals(DeliveryRequest.Reason.KIT, request.reason());
        assertEquals("kit-claim-12", request.idempotencyKey());
        assertEquals(List.of(DeliveryRequest.Line.of(11, 24), DeliveryRequest.Line.of(12, 1)), request.lines());
        assertTrue(player.nextMessage().contains("25 items didn't fit and went to your reward inbox"));
    }

    @Test
    void kitThatFits_sendsNothing() {
        KitGrantPlacer.PlacementSummary summary = rewards.place(player, 3, kit(), "kit-claim-12", "Starter");

        assertEquals(0, summary.inboxed());
        assertTrue(sent.isEmpty());
        assertEquals(16 + 8, player.getInventory().all(Material.BREAD).values().stream().mapToInt(ItemStack::getAmount).sum());
    }

    @Test
    void withoutAnOverflow_theKitDropsAtTheFeetAsBefore() {
        fillInventory();

        KitGrantPlacer.PlacementSummary summary = KitGrantPlacer.place(player, kit());

        assertEquals(3, summary.dropped());
        assertEquals(0, summary.inboxed());
        assertEquals(3, droppedItems());
        assertTrue(sent.isEmpty());
    }

    @Test
    void productionDisabled_fallsBackToTodaysDrop() {
        fillInventory();
        answers.add(CompletableFuture.failedFuture(new CompletionException(new ApiException("http://api/ItemDeliveries", 409,
                "Conflict", "{\"code\":\"ProductionDisabled\"}"))));

        rewards.place(player, 3, kit(), "kit-claim-12", "Starter");

        assertEquals(1, sent.size(), "a refusal is not retried");
        assertEquals(3, droppedItems());
        assertEquals("§7Some of it didn't fit and was dropped at your feet.", player.nextMessage());
    }

    @Test
    void noAnswer_isSentOnceMoreWithTheSameKey() throws Exception {
        fillInventory();
        answers.add(CompletableFuture.failedFuture(new CompletionException(new java.io.IOException("connection reset"))));

        CompletableFuture<RewardInbox.Outcome> outcome = rewards.send(player, 3, DeliveryRequest.Reason.KIT, "Kit", "Starter",
                "kit-claim-12", List.of(new RewardInbox.Leftover(new ItemStack(Material.BREAD, 5), 11)), stacks -> { });

        RewardInbox.Outcome done = outcome.get(RewardInbox.RETRY_DELAY_SECONDS + 5, TimeUnit.SECONDS);
        assertTrue(done.inboxed());
        assertEquals(2, sent.size());
        assertEquals(sent.get(0).idempotencyKey(), sent.get(1).idempotencyKey());
    }

    @Test
    void itemIdentityDecidesTheBlueprint_andAUniqueItemNeverGoes() {
        resolver = item -> item.getType() == Material.IRON_SWORD
                ? new ItemIdentityResolution(ItemIdentityResolution.Kind.INSTANCE, 12, 900L, null, false)
                : new ItemIdentityResolution(ItemIdentityResolution.Kind.VANILLA_MATCH, 501, null, null, false);
        List<ItemStack> fellBack = new ArrayList<>();

        RewardInbox.Outcome outcome = rewards.send(player, 3, DeliveryRequest.Reason.KIT, "Kit", "Starter", "k",
                List.of(new RewardInbox.Leftover(new ItemStack(Material.BREAD, 5), 11),
                        new RewardInbox.Leftover(new ItemStack(Material.IRON_SWORD), 12)), fellBack::addAll).join();

        assertEquals(List.of(DeliveryRequest.Line.of(501, 5)), sent.get(0).lines());
        assertEquals(1, fellBack.size());
        assertEquals(Material.IRON_SWORD, fellBack.get(0).getType());
        assertTrue(outcome.inboxed());
    }

    @Test
    void offlineKit_goesToTheInboxWhole() {
        rewards.deliverOffline(3, kit(), "kit-first-join-u3", "starter kit");

        assertEquals(1, sent.size());
        assertEquals("kit-first-join-u3", sent.get(0).idempotencyKey());
        assertEquals(25, sent.get(0).totalAmount());
    }

    @Test
    void theLoss_isToldFirst_andTheDeliveryIsRemembered() {
        fillInventory();
        answers.add(CompletableFuture.completedFuture(new DeliveryResult(77, "PartlyLost", 20, 5, 2304, null, false)));

        rewards.place(player, 3, kit(), "kit-claim-12", "Starter");

        assertEquals("§cYour reward inbox is full: 5 items were lost.", player.nextMessage());
        assertTrue(player.nextMessage().contains("20 items didn't fit"));
        assertTrue(rewards.wasReported(77L));
        assertFalse(rewards.wasReported(78L));
    }

    private static KnkLootboxClaimResult lootboxClaim(Long instanceId) {
        return new KnkLootboxClaimResult(41, false, 3, 12, 3, 5, "Food Lootbox", instanceId, 11, "Bread",
                null, null, 16, false, List.of(), false, null, null);
    }

    @Test
    void lootboxLeftovers_areConfirmedAfterTheInboxAnswered() {
        AtomicReference<LootboxDeliveryMethod> method = new AtomicReference<>();
        AtomicReference<String> note = new AtomicReference<>();

        rewards.route(player, lootboxClaim(null), List.of(new ItemStack(Material.BREAD, 10)), LootboxDeliveryMethod.INVENTORY,
                (m, n) -> {
                    method.set(m);
                    note.set(n);
                });

        assertEquals("lootbox-claim-41", sent.get(0).idempotencyKey());
        assertEquals(DeliveryRequest.Reason.LOOTBOX, sent.get(0).reason());
        assertEquals(LootboxDeliveryMethod.INVENTORY, method.get());
        assertEquals("10 to the reward inbox (delivery #55)", note.get());
        assertEquals(0, droppedItems());
    }

    @Test
    void lootboxLeftovers_refused_dropOwnerLocked() {
        answers.add(CompletableFuture.failedFuture(new CompletionException(new ApiException("x", 409, "Conflict",
                "{\"code\":\"ProductionDisabled\"}"))));
        AtomicReference<LootboxDeliveryMethod> method = new AtomicReference<>();

        rewards.route(player, lootboxClaim(null), List.of(new ItemStack(Material.BREAD, 10)), LootboxDeliveryMethod.INVENTORY,
                (m, n) -> method.set(m));

        assertEquals(LootboxDeliveryMethod.DROPPED_OWNED, method.get());
        // The drop itself is LootboxDelivery.dropOwned; LootboxDeliveryTest.leftovers_dropOwnerLocked pins the owner lock
        // (MockBukkit's ItemMock does not implement getOwner or canMobPickup).
        assertEquals(1, droppedItems());
        assertTrue(sent.size() == 1, "a refusal is not retried");
    }
}
