package net.knightsandkings.knk.paper.siege.survival.kits;

import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.KitsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.item.KnkKit;
import net.knightsandkings.knk.core.domain.item.KnkKitClaimResult;
import net.knightsandkings.knk.core.domain.item.KnkKitContent;
import net.knightsandkings.knk.paper.siege.survival.items.MatchItems;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The survival kit grant: the kit's slots as a claim result without a claim, and the empty/missing-kit paths. */
class SurvivalKitGrantsTest {

    private final KitsDataAccess kits = mock(KitsDataAccess.class);
    private final SurvivalKitGrants grants = new SurvivalKitGrants(kits, mock(ItemBlueprintsDataAccess.class),
        mock(MinecraftMaterialRefsDataAccess.class), new MatchItems(new NamespacedKey("knk", "survival_match")), Runnable::run, null);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final Player player = mock(Player.class);

    SurvivalKitGrantsTest() {
        when(player.getInventory()).thenReturn(inventory);
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("alice");
    }

    @Test
    void theKitsSlotsBecomeAClaimResultFieldForField() {
        KnkKit kit = new KnkKit(5, "Knight", null, 1, 2, 3, 4, 5, 6, List.of(new KnkKitContent(0, 7, 16)),
            null, null, "knk.premium", false, 3600, 500, "Coins", true, 100);
        KnkKitClaimResult claim = SurvivalKitGrants.claimOf(kit);
        assertEquals(5, claim.kitId());
        assertEquals(1, claim.helmetId());
        assertEquals(2, claim.chestplateId());
        assertEquals(3, claim.leggingsId());
        assertEquals(4, claim.bootsId());
        assertEquals(5, claim.shieldId());
        assertEquals(6, claim.handId());
        assertEquals(1, claim.contents().size());
        assertEquals(7, claim.contents().get(0).itemBlueprintId());
        KnkKit bare = new KnkKit(null, "Bare", null, null, null, null, null, null, null, null, null, null, null, false, 0, null, null, false, null);
        assertEquals(0, SurvivalKitGrants.claimOf(bare).kitId());
        assertTrue(SurvivalKitGrants.claimOf(bare).contents().isEmpty());
    }

    @Test
    void noKitClearsTheInventoryOnly() throws Exception {
        SurvivalKitGrants.Outcome outcome = grants.grant(player, OptionalInt.empty(), "m-1").get();
        assertEquals(SurvivalKitGrants.Outcome.EMPTY, outcome);
        verify(inventory).clear();
        verify(inventory).setItemInOffHand(null);
        verify(player).setItemOnCursor(null);
    }

    @Test
    void aMissingKitClearsTheInventoryAndReportsIt() throws Exception {
        when(kits.getByIdAsync(42)).thenReturn(CompletableFuture.completedFuture(FetchResult.notFound()));
        SurvivalKitGrants.Outcome outcome = grants.grant(player, OptionalInt.of(42), "m-1").get();
        assertEquals(42, outcome.kitId());
        assertFalse(outcome.kitFound());
        assertEquals(0, outcome.placed());
        verify(inventory).clear();
    }

    @Test
    void aFailedLookupIsReportedAsMissingNotThrown() throws Exception {
        when(kits.getByIdAsync(43)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("api down")));
        SurvivalKitGrants.Outcome outcome = grants.grant(player, OptionalInt.of(43), "m-1").get();
        assertFalse(outcome.kitFound());
    }

    @Test
    void withoutAKitDataAccessTheInventoryIsClearedAndTheKitCountsAsMissing() throws Exception {
        SurvivalKitGrants none = new SurvivalKitGrants(null, mock(ItemBlueprintsDataAccess.class), mock(MinecraftMaterialRefsDataAccess.class),
            new MatchItems(new NamespacedKey("knk", "survival_match")), Runnable::run, null);
        assertFalse(none.grant(player, OptionalInt.of(1), "m-1").get().kitFound());
        verify(inventory).clear();
    }
}
