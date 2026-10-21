package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.duel.DuelEndReason;
import net.knightsandkings.knk.core.duel.DuelEscrowPlan;
import net.knightsandkings.knk.core.duel.DuelEscrowState;
import net.knightsandkings.knk.core.duel.DuelResult;
import net.knightsandkings.knk.core.duel.DuelSide;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arena duels Phase 4 (KNG-217): the escrow spool's round trips (plan test list): a crash in LOCKING gives the
 * items back, ESCROWED + Aborted gives them back, Completed pays the winner, HELD keeps them until release/void;
 * spoils per side; the delivery chain inventory → ender chest → CLAIM_PENDING; files written before every change.
 */
class DuelEscrowSpoolTest {

    @TempDir
    Path dataFolder;

    private final DuelTestItems items = new DuelTestItems();
    private final UUID token = UUID.randomUUID();
    private final ItemStack sword = items.item("sword");
    private final ItemStack gold = items.item("gold");
    private final ItemStack helmet = items.item("helmet");
    private final DuelTestItems.TestPlayer alice = items.player("Alice");
    private final DuelTestItems.TestPlayer bob = items.player("Bob");

    private File directory() {
        return dataFolder.resolve("duel-escrow").toFile();
    }

    private DuelEscrowSpool spool() {
        return new DuelEscrowSpool(directory(), Logger.getLogger("test"), items.codec());
    }

    private DuelEscrowSpool.Entry entry() {
        return new DuelEscrowSpool.Entry(token, 7, new DuelEscrowSpool.Party(alice.id(), 1, "Alice"),
            new DuelEscrowSpool.Party(bob.id(), 2, "Bob"), List.of(sword), List.of(gold));
    }

    private File file() {
        return new File(directory(), token + ".yml");
    }

    @Test
    void theFileIsWrittenInLockingBeforeAnythingIsTaken() throws Exception {
        DuelEscrowSpool spool = spool();

        assertTrue(spool.open(entry()));

        assertTrue(file().isFile());
        assertTrue(Files.readString(file().toPath()).contains("state: LOCKING"));
        assertFalse(spool.open(entry()), "a token is opened once");
    }

    @Test
    void anUnwritableDirectoryRefusesTheLockIn() throws Exception {
        Files.writeString(dataFolder.resolve("duel-escrow"), "not a directory");

        assertFalse(spool().open(entry()));
    }

    @Test
    void aCrashInLockingGivesEachPlayerTheirOwnItems() {
        assertTrue(spool().open(entry()));

        DuelEscrowSpool afterRestart = spool();
        assertEquals(1, afterRestart.loadAll().size());
        assertTrue(afterRestart.settle(token, DuelRecovery.planFor(afterRestart.find(token).orElseThrow(), null)));

        assertEquals(DuelEscrowState.REFUNDED, afterRestart.find(token).orElseThrow().state());
        afterRestart.deliver(alice.player());
        afterRestart.deliver(bob.player());
        assertEquals(List.of(sword), alice.received());
        assertEquals(List.of(gold), bob.received());
        assertFalse(file().exists(), "an empty settled file goes");
    }

    @Test
    void escrowedThenAbortedGivesTheItemsBack() {
        DuelEscrowSpool spool = spool();
        spool.open(entry());
        assertTrue(spool.escrowed(token, 41));

        spool.settle(token, DuelEscrowPlan.forResult(DuelResult.noWinner(DuelEndReason.SERVER_RESTART), null, null));

        spool.deliver(alice.player());
        spool.deliver(bob.player());
        assertEquals(List.of(sword), alice.received());
        assertEquals(List.of(gold), bob.received());
    }

    @Test
    void aCompletedMatchPaysTheWinnerBothStakesAndTheSpoils() {
        DuelEscrowSpool spool = spool();
        spool.open(entry());
        spool.escrowed(token, 41);
        assertTrue(spool.addSpoils(token, DuelSide.OPPONENT, List.of(helmet)));

        spool.settle(token, DuelEscrowPlan.forResult(DuelResult.win(DuelSide.CHALLENGER, DuelEndReason.WIN),
            DuelEscrowPlan.Decision.PAID, DuelSide.OPPONENT));
        spool.deliver(bob.player());
        spool.deliver(alice.player());

        assertEquals(List.of(), bob.received());
        assertEquals(List.of(sword, gold, helmet), alice.received());
    }

    @Test
    void aHeldResultKeepsEverythingUntilReleased() {
        DuelEscrowSpool spool = spool();
        spool.open(entry());
        spool.escrowed(token, 41);
        spool.addSpoils(token, DuelSide.OPPONENT, List.of(helmet));

        spool.settle(token, DuelEscrowPlan.hold());
        assertTrue(spool.isHeld(token));
        spool.deliver(alice.player());
        assertEquals(List.of(), alice.received(), "nothing leaves a held file");

        DuelEscrowSpool afterRestart = spool();
        afterRestart.loadAll();
        assertTrue(afterRestart.isHeld(token), "held survives a restart");
        afterRestart.settle(token, DuelEscrowPlan.payWinner(DuelSide.CHALLENGER));
        afterRestart.deliver(alice.player());
        assertEquals(List.of(sword, gold, helmet), alice.received());
    }

    @Test
    void aVoidedHeldResultRefundsAndGivesTheSpoilsBackToTheLoser() {
        DuelEscrowSpool spool = spool();
        spool.open(entry());
        spool.escrowed(token, 41);
        spool.addSpoils(token, DuelSide.OPPONENT, List.of(helmet));
        spool.settle(token, DuelEscrowPlan.hold());

        spool.settle(token, DuelEscrowPlan.refund(DuelSide.OPPONENT));
        spool.deliver(alice.player());
        spool.deliver(bob.player());

        assertEquals(List.of(sword), alice.received());
        assertEquals(List.of(gold, helmet), bob.received());
    }

    @Test
    void aDrawWhereBothDiedGivesEachSideItsOwnSpoils() {
        DuelEscrowSpool spool = spool();
        spool.open(entry());
        spool.escrowed(token, 41);
        ItemStack bobsBoots = items.item("boots");
        spool.addSpoils(token, DuelSide.CHALLENGER, List.of(helmet));
        spool.addSpoils(token, DuelSide.OPPONENT, List.of(bobsBoots));

        spool.settle(token, DuelEscrowPlan.forResult(DuelResult.noWinner(DuelEndReason.SIMULTANEOUS_DEATH), null, null));
        spool.deliver(alice.player());
        spool.deliver(bob.player());

        assertEquals(List.of(sword, helmet), alice.received());
        assertEquals(List.of(gold, bobsBoots), bob.received());
    }

    @Test
    void spoilsAreOnDiskBeforeTheCallerClearsTheDrops() throws Exception {
        DuelEscrowSpool spool = spool();
        spool.open(entry());
        spool.escrowed(token, 41);

        assertTrue(spool.addSpoils(token, DuelSide.OPPONENT, List.of(helmet)));

        DuelEscrowSpool reread = spool();
        reread.loadAll();
        assertEquals(List.of(helmet), reread.find(token).orElseThrow().spoilsFrom(DuelSide.OPPONENT));
    }

    @Test
    void whatFitsNowhereWaitsAsClaimPendingAndIsClaimedLater() {
        DuelEscrowSpool spool = spool();
        spool.open(entry());
        spool.escrowed(token, 41);
        spool.settle(token, DuelEscrowPlan.payWinner(DuelSide.CHALLENGER));
        alice.inventoryFull(true);
        alice.enderChestFull(true);

        DuelEscrowSpool.Delivery first = spool.deliver(alice.player());

        assertEquals(new DuelEscrowSpool.Delivery(0, 0, 2), first);
        assertEquals(DuelEscrowState.CLAIM_PENDING, spool.find(token).orElseThrow().state());
        assertEquals(2, spool.owedCount(alice.id()));

        alice.inventoryFull(false);
        assertEquals(new DuelEscrowSpool.Delivery(2, 0, 0), spool.deliver(alice.player()));
        assertFalse(file().exists());
    }

    @Test
    void aFullInventorySpillsIntoTheEnderChest() {
        DuelEscrowSpool spool = spool();
        spool.open(entry());
        spool.escrowed(token, 41);
        spool.settle(token, DuelEscrowPlan.payWinner(DuelSide.CHALLENGER));
        alice.inventoryFull(true);

        assertEquals(new DuelEscrowSpool.Delivery(0, 2, 0), spool.deliver(alice.player()));
        assertEquals(List.of(sword, gold), alice.enderChestReceived());
    }

    @Test
    void settlingTwiceChangesNothingAndASettledFileTakesNoSpoils() {
        DuelEscrowSpool spool = spool();
        spool.open(entry());
        spool.escrowed(token, 41);
        spool.settle(token, DuelEscrowPlan.payWinner(DuelSide.CHALLENGER));

        assertTrue(spool.settle(token, DuelEscrowPlan.refund(null)), "idempotent");
        assertFalse(spool.addSpoils(token, DuelSide.OPPONENT, List.of(helmet)));
        spool.deliver(bob.player());
        assertEquals(List.of(), bob.received());
    }

    @Test
    void anIllegalMoveIsRefusedAndTheFileStaysAsItWas() {
        DuelEscrowSpool spool = spool();
        spool.open(entry());

        assertFalse(spool.settle(token, DuelEscrowPlan.hold()), "LOCKING can't be held");
        assertEquals(DuelEscrowState.LOCKING, spool.find(token).orElseThrow().state());
        assertEquals(List.of(sword), spool.find(token).orElseThrow().stakeOf(DuelSide.CHALLENGER));
    }

    @Test
    void anUndeliveredDestroyedFileIsGone() {
        DuelEscrowSpool spool = spool();
        spool.open(entry());
        spool.escrowed(token, 41);

        assertTrue(spool.destroy(token, "test"));

        assertFalse(file().exists());
        assertTrue(spool.find(token).isEmpty());
    }
}
