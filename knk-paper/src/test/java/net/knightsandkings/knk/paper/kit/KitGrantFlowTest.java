package net.knightsandkings.knk.paper.kit;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.item.KnkKitClaimResult;
import net.knightsandkings.knk.core.domain.item.KnkKitPurchaseResult;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.KitsCommandApi;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Content port CP2: the shared kit grant path used by {@code /kit} and {@code kits.overview}. */
class KitGrantFlowTest {

    private final KitsCommandApi api = mock(KitsCommandApi.class);
    private final KnkPermissible permissible = mock(KnkPermissible.class);
    private final UserCache userCache = new UserCache(Duration.ofMinutes(5));
    private final KitGrantFlow flow = new KitGrantFlow(Runnable::run, api, mock(ItemBlueprintsDataAccess.class),
            mock(MinecraftMaterialRefsDataAccess.class), permissible, userCache);
    private final Player player = mock(Player.class);

    KitGrantFlowTest() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Alice");
        when(player.isOnline()).thenReturn(true);
    }

    @Test
    void claimWithoutTheNodeNeverCallsTheServer() {
        when(permissible.hasPermission(player, KitGrantFlow.GET_NODE)).thenReturn(false);

        assertFalse(flow.claim(player, 1, 7, "Starter").join());

        verify(api, never()).claimAsync(anyInt(), anyInt());
        verify(player).sendMessage("§cYou don't have permission to do that.");
    }

    @Test
    void successfulClaimPlacesAndConfirms() {
        when(permissible.hasPermission(player, KitGrantFlow.GET_NODE)).thenReturn(true);
        when(api.claimAsync(1, 7)).thenReturn(CompletableFuture.completedFuture(
                new KnkKitClaimResult(7, null, null, null, null, null, null, List.of())));

        assertTrue(flow.claim(player, 1, 7, "Starter").join());

        verify(player).sendMessage("§aKit \"Starter\" claimed to §bAlice§a.");
    }

    @Test
    void serverDenialIsShownAsItsMessage() {
        when(permissible.hasPermission(player, KitGrantFlow.GET_NODE)).thenReturn(true);
        // Wrapped exactly as KitsCommandApiImpl does: RuntimeException("Failed to claim …", ApiException).
        when(api.claimAsync(1, 7)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("Failed to claim kit 7",
                new ApiException("/api/kits/7/claim", 409, "Conflict",
                        "{\"code\":\"ClaimDenied\",\"message\":\"Kit is on cooldown for 4 more minutes.\"}"))));

        assertFalse(flow.claim(player, 1, 7, "Starter").join());

        verify(player).sendMessage("§cKit is on cooldown for 4 more minutes.");
    }

    @Test
    void otherErrorsKeepTheStatusAndBody() {
        when(permissible.hasPermission(player, KitGrantFlow.PURCHASE_NODE)).thenReturn(true);
        when(api.purchaseAsync(1, 7)).thenReturn(CompletableFuture.failedFuture(new RuntimeException("Failed to purchase",
                new ApiException("/api/kits/7/purchase", 400, "Bad Request", "Not a premium kit"))));

        assertFalse(flow.purchase(player, 1, 7, "Starter").join());

        verify(player).sendMessage("§cHTTP 400");
        verify(player).sendMessage("§cNot a premium kit");
    }

    @Test
    void purchaseReportsGemsPaid() {
        when(permissible.hasPermission(player, KitGrantFlow.PURCHASE_NODE)).thenReturn(true);
        when(api.purchaseAsync(1, 7)).thenReturn(CompletableFuture.completedFuture(
                new KnkKitPurchaseResult(7, 1, 300, OffsetDateTime.now())));

        assertTrue(flow.purchase(player, 1, 7, "Starter").join());

        verify(player).sendMessage("§aPurchased \"Starter\" for §6300 gems§a. Use /kit get Starter to claim it.");
    }

    @Test
    void denialMessageOnlyParsesConflictBodies() {
        assertEquals("a \"quoted\" reason", KitGrantFlow.denialMessage(new ApiException("u", 409, "m",
                "{\"code\":\"PurchaseDenied\",\"message\":\"a \\\"quoted\\\" reason\"}")));
        assertNull(KitGrantFlow.denialMessage(new ApiException("u", 500, "m", "{\"message\":\"x\"}")));
        assertNull(KitGrantFlow.denialMessage(new ApiException("u", 409, "m", "plain")));
    }

    @Test
    void userIdResolvesFromTheStaleCacheEntry() {
        assertNull(flow.resolveUserId(player));
        userCache.put(new net.knightsandkings.knk.core.domain.users.UserSummary(42, "Alice", player.getUniqueId(), null,
                0, 0, 0, true, false, null, null, null, null, 0, null, null, null, false, null));
        assertEquals(42, flow.resolveUserId(player));
    }
}
