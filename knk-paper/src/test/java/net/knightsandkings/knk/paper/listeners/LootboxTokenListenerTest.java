package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import net.knightsandkings.knk.core.lootbox.TokenOpenGuard;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import net.knightsandkings.knk.paper.lootbox.LootboxOpening;
import net.knightsandkings.knk.paper.lootbox.LootboxRuntime;
import net.knightsandkings.knk.paper.lootbox.LootboxSettings;
import net.knightsandkings.knk.paper.lootbox.LootboxTokenDelivery;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.knightsandkings.knk.paper.mapper.LootboxTokenTagTest.itemWithPdc;
import static net.knightsandkings.knk.paper.mapper.LootboxTokenTagTest.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Lootboxes Phase 5: opening a token item. Only the PDC tag makes an item a token (a renamed item does nothing), the
 * checks before the API call, and what each answer does to the copies in the inventory.
 */
class LootboxTokenListenerTest {

    private static final UUID TOKEN = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final LootboxRuntime runtime = mock(LootboxRuntime.class);
    private final LootboxesCommandApi api = mock(LootboxesCommandApi.class);
    private final LootboxOpening opening = mock(LootboxOpening.class);
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final UUID playerId = UUID.randomUUID();

    private boolean allowed = true;
    private ActiveMode mode = ActiveMode.NONE;
    private boolean inSiege;
    private Integer userId = 9;
    private ItemStack held;
    private LootboxTokenListener listener;

    @BeforeEach
    void setUp() {
        when(runtime.settings()).thenReturn(LootboxSettings.defaults());
        when(runtime.config()).thenReturn(KnkLootboxRuntimeConfig.empty());
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Steve");
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.firstEmpty()).thenReturn(3);
        held = token(TOKEN, 1);
        when(inventory.getContents()).thenReturn(new ItemStack[]{held});
        when(inventory.getHeldItemSlot()).thenReturn(0);

        listener = new LootboxTokenListener(mock(Plugin.class), runtime, new TokenOpenGuard(), api, opening,
                mock(LootboxTokenDelivery.class), (p, node) -> allowed, null, p -> mode, id -> inSiege, p -> userId, Runnable::run);
    }

    private static KnkLootboxClaimResult claim(boolean delivered) {
        return new KnkLootboxClaimResult(41, false, 9, null, 3, 5, "Legendary Weapons Lootbox", 5L, 77,
                "Steel Sword", 3, 3, 1, false, null, false, null, delivered ? java.time.Instant.EPOCH : null);
    }

    private void answer(CompletableFuture<KnkLootboxClaimResult> result) {
        when(api.redeemToken(any(), anyInt(), anyString())).thenReturn(result);
    }

    private static CompletableFuture<KnkLootboxClaimResult> refused(int status, String code) {
        return CompletableFuture.failedFuture(new LootboxRejectedException(status, code, "x", null, null, null));
    }

    @Test
    void aRenamedItemWithoutTheTag_doesNothing() {
        ItemStack renamed = itemWithPdc(1);
        when(renamed.getItemMeta().getDisplayName()).thenReturn("Rare Sword Box");
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getItem()).thenReturn(renamed);
        when(event.getPlayer()).thenReturn(player);

        listener.onInteract(event);

        verify(event, never()).setCancelled(true);
        verifyNoInteractions(api);
    }

    @Test
    void aRightClickWithAToken_isCancelled_andRedeemsWithAFreshKeyPerClick() {
        answer(new CompletableFuture<>());
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getItem()).thenReturn(held);
        when(event.getPlayer()).thenReturn(player);

        listener.onInteract(event);

        verify(event).setCancelled(true);
        verify(api).redeemToken(eq(TOKEN), eq(9), startsWith("token-open:" + TOKEN + ":"));
    }

    @Test
    void aSecondClickWhileInFlight_isIgnored() {
        answer(new CompletableFuture<>());

        assertEquals(LootboxTokenListener.Attempt.OPENING, listener.attemptOpen(player, TOKEN, held));
        assertEquals(LootboxTokenListener.Attempt.IN_FLIGHT, listener.attemptOpen(player, TOKEN, held));

        verify(api, times(1)).redeemToken(any(), anyInt(), anyString());
    }

    @Test
    void checks_refuseBeforeAnyApiCall() {
        allowed = false;
        assertEquals(LootboxTokenListener.Attempt.NO_PERMISSION, listener.attemptOpen(player, TOKEN, held));
        allowed = true;
        mode = ActiveMode.STAFF;
        assertEquals(LootboxTokenListener.Attempt.STAFF_MODE, listener.attemptOpen(player, TOKEN, held));
        mode = ActiveMode.NONE;
        userId = null;
        assertEquals(LootboxTokenListener.Attempt.NO_ACCOUNT, listener.attemptOpen(player, TOKEN, held));
        verifyNoInteractions(api);
    }

    @Test
    void inASiege_isRefusedBeforeAnyApiCall_andTheTokenIsKept() {
        inSiege = true;

        assertEquals(LootboxTokenListener.Attempt.IN_SIEGE, listener.attemptOpen(player, TOKEN, held));

        verify(player).sendMessage(contains("during a siege"));
        verifyNoInteractions(api);
        verify(inventory, never()).setItem(anyInt(), any());
    }

    @Test
    void fullInventory_isRefused_unlessTheTokenIsTheLastOfItsStack() {
        when(inventory.firstEmpty()).thenReturn(-1);
        answer(new CompletableFuture<>());

        assertEquals(LootboxTokenListener.Attempt.INVENTORY_FULL, listener.attemptOpen(player, TOKEN, token(TOKEN, 2)));
        assertEquals(LootboxTokenListener.Attempt.OPENING, listener.attemptOpen(player, TOKEN, held), "its own slot frees up");
    }

    @Test
    void opened_takesOneCopy_thenShowsTheOpening() {
        KnkLootboxClaimResult claim = claim(false);
        answer(CompletableFuture.completedFuture(claim));

        listener.attemptOpen(player, TOKEN, held);

        verify(inventory).setItem(0, null);
        verify(opening).open(player, claim, null);
    }

    @Test
    void aReplayOfADeliveredOpen_givesNothingMore() {
        answer(CompletableFuture.completedFuture(claim(true)));

        listener.attemptOpen(player, TOKEN, held);

        verify(opening, never()).open(any(), any(), any());
    }

    @Test
    void anAlreadyOpenedToken_isDead_everyCopyIsRemoved() {
        ItemStack dupe = token(TOKEN, 5);
        when(inventory.getContents()).thenReturn(new ItemStack[]{held, dupe});
        answer(refused(409, LootboxRejectedException.ALREADY_REDEEMED));

        listener.attemptOpen(player, TOKEN, held);

        verify(inventory).setItem(0, null);
        verify(inventory).setItem(1, null);
        verify(player).sendMessage(contains("already opened"));
        verifyNoInteractions(opening);
    }

    @Test
    void theDailyLimit_orAnUnknownToken_keepsTheItem() {
        answer(CompletableFuture.failedFuture(new LootboxRejectedException(429, "DailyLimit", "x", "Global", 10, null)));
        listener.attemptOpen(player, TOKEN, held);
        verify(player).sendMessage(contains("You've opened 10 lootboxes today"));

        answer(refused(409, LootboxRejectedException.INVALID_TOKEN));
        listener.attemptOpen(player, TOKEN, held);
        verify(player).sendMessage(contains("isn't recognised"));

        answer(CompletableFuture.failedFuture(new RuntimeException("down")));
        listener.attemptOpen(player, TOKEN, held);
        verify(player).sendMessage(contains("stuck"));

        verify(inventory, never()).setItem(anyInt(), any());
    }
}
