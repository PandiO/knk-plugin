package net.knightsandkings.knk.paper.kit;

import net.knightsandkings.knk.core.domain.item.KnkKitClaimResult;
import net.knightsandkings.knk.core.domain.item.KnkKitDelivery;
import net.knightsandkings.knk.core.ports.api.KitsCommandApi;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PandiO/knk-workspace#261: a kit granted from the web app is a pending delivery at the API. The plugin takes each one
 * before it places the items, so an online player gets them, a delivery another call or server took is never placed
 * twice, and one that can't be taken or resolved stays pending for the next join.
 */
class KitGrantDeliveryTest {

    private static final int USER = 31;

    private final KitsCommandApi api = mock(KitsCommandApi.class);
    private final Player player = mock(Player.class);
    private final UUID uuid = UUID.randomUUID();
    private final AtomicBoolean inSiege = new AtomicBoolean(false);
    private final List<List<KitGrantPlacer.ResolvedItem>> placed = new ArrayList<>();
    private final List<KitGrantPlacer.ResolvedItem> items = List.of();
    private KitGrantPlacer.PlacementSummary summary = new KitGrantPlacer.PlacementSummary(3, 0, 0, 0, 0);
    private final BiFunction<Player, List<KitGrantPlacer.ResolvedItem>, KitGrantPlacer.PlacementSummary> placer = (p, i) -> {
        placed.add(i);
        return summary;
    };
    private KitGrantDelivery.Resolver resolver = loadout -> CompletableFuture.completedFuture(items);

    KitGrantDeliveryTest() {
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("Alice");
        when(player.isOnline()).thenReturn(true);
    }

    private KitGrantDelivery delivery() {
        return new KitGrantDelivery(Runnable::run, api, p -> USER, id -> inSiege.get(), loadout -> resolver.resolve(loadout), placer);
    }

    private static KnkKitDelivery pending(int claimId, String kitName) {
        return new KnkKitDelivery(claimId, 10, kitName, new KnkKitClaimResult(10, 5, null, null, null, null, null, List.of()));
    }

    private void pendingAre(KnkKitDelivery... deliveries) {
        when(api.pendingDeliveriesAsync(USER)).thenReturn(CompletableFuture.completedFuture(List.of(deliveries)));
    }

    @Test
    void anOnlinePlayerGetsEachPendingKit_AfterItWasTaken() {
        pendingAre(pending(501, "Knight"), pending(502, "Archer"));
        when(api.markDeliveredAsync(USER, 501)).thenReturn(CompletableFuture.completedFuture(true));
        when(api.markDeliveredAsync(USER, 502)).thenReturn(CompletableFuture.completedFuture(true));

        assertEquals(2, delivery().deliverPending(player).join());

        assertEquals(2, placed.size());
        verify(player).sendMessage("§aA staff member gave you the \"Knight\" kit.");
        verify(player).sendMessage("§aA staff member gave you the \"Archer\" kit.");
    }

    @Test
    void aDeliveryTakenElsewhere_IsNeverPlacedAgain() {
        pendingAre(pending(501, "Knight"));
        when(api.markDeliveredAsync(USER, 501)).thenReturn(CompletableFuture.completedFuture(false));

        assertEquals(0, delivery().deliverPending(player).join());

        assertEquals(0, placed.size());
        verify(player, never()).sendMessage(anyString());
    }

    @Test
    void aFailedTake_PlacesNothing_AndGoesOnWithTheNextOne() {
        pendingAre(pending(501, "Knight"), pending(502, "Archer"));
        when(api.markDeliveredAsync(USER, 501)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("HTTP 500")));
        when(api.markDeliveredAsync(USER, 502)).thenReturn(CompletableFuture.completedFuture(true));

        assertEquals(1, delivery().deliverPending(player).join());

        verify(player, never()).sendMessage("§aA staff member gave you the \"Knight\" kit.");
        verify(player).sendMessage("§aA staff member gave you the \"Archer\" kit.");
    }

    @Test
    void itemsThatCantBeResolved_AreNotTaken_SoTheyStayPending() {
        pendingAre(pending(501, "Knight"));
        resolver = loadout -> CompletableFuture.failedFuture(new IllegalStateException("blueprint 5 not found"));

        assertEquals(0, delivery().deliverPending(player).join());

        verify(api, never()).markDeliveredAsync(anyInt(), anyInt());
        assertEquals(0, placed.size());
    }

    @Test
    void inASiege_NothingIsReadOrTaken() {
        inSiege.set(true);

        assertEquals(0, delivery().deliverPending(player).join());

        verify(api, never()).pendingDeliveriesAsync(anyInt());
        verify(api, never()).markDeliveredAsync(anyInt(), anyInt());
    }

    @Test
    void aPlayerWhoLeftBeforeTheTake_KeepsTheDeliveryPending() {
        pendingAre(pending(501, "Knight"));
        resolver = loadout -> {
            when(player.isOnline()).thenReturn(false); // logs out while the items resolve
            return CompletableFuture.completedFuture(items);
        };

        assertEquals(0, delivery().deliverPending(player).join());

        verify(api, never()).markDeliveredAsync(anyInt(), anyInt());
    }

    @Test
    void aSiegeEnteredWhileTheTakeIsInFlight_KeepsTheKit_AndPlacesItAfterTheSiege() {
        pendingAre(pending(501, "Knight"));
        CompletableFuture<Boolean> take = new CompletableFuture<>();
        when(api.markDeliveredAsync(USER, 501)).thenReturn(take);
        KitGrantDelivery delivery = delivery();

        CompletableFuture<Integer> run = delivery.deliverPending(player);
        inSiege.set(true); // joins a siege lobby while the API takes the delivery
        take.complete(true);

        assertEquals(0, run.join());
        assertEquals(0, placed.size()); // not into the siege inventory, which the vault restore replaces
        verify(player, never()).sendMessage(anyString());

        inSiege.set(false); // the after-siege hook asks again; the API has nothing pending any more
        pendingAre();

        assertEquals(1, delivery.deliverPending(player).join());
        assertEquals(1, placed.size());
        verify(player).sendMessage("§aA staff member gave you the \"Knight\" kit.");
        verify(api, times(1)).markDeliveredAsync(USER, 501);
    }

    @Test
    void droppedItemsAreMentioned() {
        pendingAre(pending(501, "Knight"));
        when(api.markDeliveredAsync(USER, 501)).thenReturn(CompletableFuture.completedFuture(true));
        summary = new KitGrantPlacer.PlacementSummary(1, 0, 0, 2, 0);

        delivery().deliverPending(player).join();

        verify(player).sendMessage("§7Some of it didn't fit and was dropped at your feet.");
    }

    @Test
    void aCallDuringARun_RunsOnceMoreAfterIt_NeverTwiceAtOnce() {
        CompletableFuture<List<KnkKitDelivery>> first = new CompletableFuture<>();
        when(api.pendingDeliveriesAsync(USER))
            .thenReturn(first)
            .thenReturn(CompletableFuture.completedFuture(List.of(pending(502, "Archer"))));
        when(api.markDeliveredAsync(USER, 501)).thenReturn(CompletableFuture.completedFuture(true));
        when(api.markDeliveredAsync(USER, 502)).thenReturn(CompletableFuture.completedFuture(true));
        KitGrantDelivery delivery = delivery();

        delivery.deliverPending(player);
        delivery.deliverPending(player); // e.g. the join and a pushed notification at the same time
        verify(api, times(1)).pendingDeliveriesAsync(USER);

        first.complete(List.of(pending(501, "Knight")));

        verify(api, times(2)).pendingDeliveriesAsync(USER);
        assertEquals(2, placed.size());
    }

    @Test
    void aPlayerWithoutACachedAccount_IsSkipped() {
        KitGrantDelivery delivery = new KitGrantDelivery(Runnable::run, api, p -> null, id -> false, resolver, placer);

        assertEquals(0, delivery.deliverPending(player).join());

        verify(api, never()).pendingDeliveriesAsync(anyInt());
    }
}
