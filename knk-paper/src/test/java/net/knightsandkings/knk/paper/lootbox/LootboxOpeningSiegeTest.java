package net.knightsandkings.knk.paper.lootbox;

import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.LootboxDeliveryMethod;
import net.knightsandkings.knk.core.ports.api.LootboxesQueryApi;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An item that comes up while the player is in a siege (hub or match) is held until the siege restores their own
 * inventory, never handed into the siege inventory (which is replaced afterwards), and never confirmed meanwhile.
 * Mirrors the held-back token coverage.
 */
class LootboxOpeningSiegeTest {

    private final UUID playerId = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final LootboxDelivery delivery = mock(LootboxDelivery.class);
    private final LootboxAnnouncer announcer = mock(LootboxAnnouncer.class);
    private final ItemStack item = mock(ItemStack.class);
    private final KnkLootboxClaimResult claim = LootboxDeliveryTest.claim(500L, 1, List.of(), 2, 3);
    private final LootboxDelivery.Prepared prepared = new LootboxDelivery.Prepared(claim, item, List.of());
    private boolean inSiege;
    private LootboxOpening opening;

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(delivery.wasHandedOver(claim.claimId())).thenReturn(false);
        when(delivery.prepare(claim)).thenReturn(CompletableFuture.completedFuture(prepared));
        // "Already held" skips the celebration: org.bukkit.Sound is registry-backed and needs a live server to load.
        when(delivery.handOver(any(), any(), anyBoolean()))
                .thenReturn(new LootboxDelivery.Outcome(false, true, null, LootboxDeliveryMethod.INVENTORY, List.of()));

        MemoryConfiguration section = new MemoryConfiguration();
        section.set("opening.style", "instant");
        section.set("opening.public-effects", false);
        LootboxSettings instant = LootboxSettings.from(section);
        opening = new LootboxOpening(mock(Plugin.class), delivery, announcer, mock(LootboxesQueryApi.class),
                () -> instant, () -> null, () -> 0.5, id -> inSiege);
    }

    @Test
    void outsideASiege_theItemIsHandedOverAtOnce() {
        opening.open(player, claim, null);

        verify(delivery).handOver(player, prepared, false);
        verify(player, never()).sendMessage(LootboxMessages.HELD_DURING_SIEGE);
    }

    @Test
    void inASiege_theItemIsHeld_notGivenAndNotConfirmed() {
        inSiege = true;

        opening.open(player, claim, null);

        verify(delivery, never()).handOver(any(), any(), anyBoolean());
        verify(delivery, never()).acknowledge(any(), any(), any());
        verify(player).sendMessage(LootboxMessages.HELD_DURING_SIEGE);
    }

    @Test
    void afterTheRestore_theHeldItemIsHandedOverOnce_andThePlayerIsTold() {
        inSiege = true;
        opening.open(player, claim, null);
        inSiege = false;

        opening.deliverWaiting(player);
        opening.deliverWaiting(player); // a second restore hook (or the join delivery) must not give it twice

        verify(player).sendMessage(LootboxMessages.ARRIVED_AFTER_SIEGE);
        verify(delivery, times(1)).handOver(player, prepared, false);
    }

    @Test
    void stillInASiege_theItemStaysHeld() {
        inSiege = true;
        opening.open(player, claim, null);

        opening.deliverWaiting(player); // e.g. the next siege started right after the restore

        verify(delivery, never()).handOver(any(), any(), anyBoolean());
        inSiege = false;
        opening.deliverWaiting(player);
        verify(delivery, times(1)).handOver(player, prepared, false);
    }

    @Test
    void anOfflinePlayer_keepsTheHeldItemForTheirNextJoin() {
        inSiege = true;
        opening.open(player, claim, null);
        inSiege = false;
        when(player.isOnline()).thenReturn(false);

        opening.deliverWaiting(player);
        verify(delivery, never()).handOver(any(), any(), anyBoolean());

        when(player.isOnline()).thenReturn(true);
        opening.deliverWaiting(player);
        verify(delivery, times(1)).handOver(player, prepared, false);
    }

    @Test
    void nothingWaiting_doesNothing() {
        opening.deliverWaiting(player);

        verify(delivery, never()).handOver(any(), any(), anyBoolean());
        verify(player, never()).sendMessage(LootboxMessages.ARRIVED_AFTER_SIEGE);
    }
}
