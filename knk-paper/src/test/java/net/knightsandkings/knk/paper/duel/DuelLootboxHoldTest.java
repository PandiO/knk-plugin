package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.Stake;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.LootboxDeliveryMethod;
import net.knightsandkings.knk.core.ports.api.LootboxesQueryApi;
import net.knightsandkings.knk.paper.lootbox.LootboxAnnouncer;
import net.knightsandkings.knk.paper.lootbox.LootboxDelivery;
import net.knightsandkings.knk.paper.lootbox.LootboxOpening;
import net.knightsandkings.knk.paper.lootbox.LootboxSettings;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Arena duels Phase 4 (KNG-217), the plan's lootbox test: a reel that finishes while its player is in a duel (here a
 * kit-mode fight) holds its item - the real {@link LootboxOpening} asks the minigame registry's
 * {@code holdsLootDrops}, which the duel answers from LOCKING until the restore - and hands it over once, after the
 * restore: never into the loaned kit (which the restore wipes) or the spoils.
 */
class DuelLootboxHoldTest {

    @TempDir
    Path dataFolder;

    @Test
    void aReelFinishingMidMatchIsDeliveredOnlyAfterTheRestore() {
        DuelHarness h = new DuelHarness(dataFolder);
        ItemStack sword = h.items.item("alice-sword");
        h.alice.give(sword);
        ItemStack[] before = h.alice.contents.clone();
        LootboxDelivery delivery = mock(LootboxDelivery.class);
        ItemStack reelItem = h.items.item("reel-item");
        KnkLootboxClaimResult claim = new KnkLootboxClaimResult(41, false, 9, 12, 3, 5, "Lootbox", 500L, 77,
            "Steel Sword", 2, 3, 1, false, List.of(), false, null, null);
        LootboxDelivery.Prepared prepared = new LootboxDelivery.Prepared(claim, reelItem, List.of());
        when(delivery.wasHandedOver(claim.claimId())).thenReturn(false);
        when(delivery.prepare(claim)).thenReturn(CompletableFuture.completedFuture(prepared));
        when(delivery.handOver(any(), any(), anyBoolean())).thenAnswer(inv -> {
            h.alice.give(reelItem);
            return new LootboxDelivery.Outcome(false, true, reelItem, LootboxDeliveryMethod.INVENTORY, List.of());
        });
        MemoryConfiguration section = new MemoryConfiguration();
        section.set("opening.style", "instant");
        section.set("opening.public-effects", false);
        LootboxSettings instant = LootboxSettings.from(section);
        LootboxOpening opening = new LootboxOpening(mock(Plugin.class), delivery, mock(LootboxAnnouncer.class),
            mock(LootboxesQueryApi.class), () -> instant, () -> null, () -> 0.5, h.registry::holdsLootDrops);

        h.duel(h.contract(GearMode.FIXED_KIT, DuelHarness.KIT_A, Stake.NONE, Stake.NONE));
        h.toFight();
        opening.open(h.alice.player(), claim, null); // the reel stops mid-fight

        verify(delivery, never()).handOver(any(), any(), anyBoolean());
        assertFalse(Arrays.asList(h.alice.contents).contains(reelItem), "not in the loaned kit");

        h.kill(h.bob);
        h.respawn(h.bob);
        opening.deliverWaiting(h.alice.player()); // a restore hook firing while she still lingers
        verify(delivery, never()).handOver(any(), any(), anyBoolean());
        h.seconds(1); // linger over: restored, then the held deliveries
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, h.alice.contents, "her own gear first");

        h.restored.stream().filter(p -> p == h.alice.player()).findFirst()
            .ifPresent(p -> opening.deliverWaiting(p));
        opening.deliverWaiting(h.alice.player());

        verify(delivery, times(1)).handOver(h.alice.player(), prepared, false);
        org.junit.jupiter.api.Assertions.assertTrue(Arrays.asList(h.alice.contents).contains(reelItem));
    }
}
