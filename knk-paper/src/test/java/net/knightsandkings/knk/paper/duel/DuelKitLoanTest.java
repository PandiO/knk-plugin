package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.DuelStateMachine;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.Stake;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arena duels Phase 4 (KNG-217), the plan's kit-loan test: in the kit modes the duelist's inventory comes back
 * identical - slot for slot, the very same stacks - after a fight, a failed lobby teleport, a quit, an API outage and
 * a crash, and no loaned kit item survives any of them. Also the KIT_CHOICE swap and its lock at the countdown, and
 * the warmup block on kit consumables.
 */
class DuelKitLoanTest {

    @TempDir
    Path dataFolder;

    private DuelHarness h;
    private ItemStack[] aliceBefore;
    private ItemStack[] bobBefore;

    private DuelHarness harness() {
        h = new DuelHarness(dataFolder);
        h.alice.give(h.items.item("alice-sword"), h.items.item("alice-bread"));
        h.alice.contents[39] = h.items.item("alice-helmet");
        h.alice.contents[40] = h.items.item("alice-shield");
        h.bob.give(h.items.item("bob-axe"));
        aliceBefore = h.alice.contents.clone();
        bobBefore = h.bob.contents.clone();
        return h;
    }

    private DuelMatch fixedKitDuel() {
        return h.duel(h.contract(GearMode.FIXED_KIT, DuelHarness.KIT_A, Stake.NONE, Stake.NONE)).match();
    }

    private boolean holdsLoan(DuelTestItems.TestPlayer who) {
        return Arrays.stream(who.contents).anyMatch(h.loans::contains);
    }

    private File vaultFile(DuelTestItems.TestPlayer who) {
        return dataFolder.resolve("duel-vault").resolve(who.id() + ".yml").toFile();
    }

    private void assertOwnGearBack() {
        assertArrayEquals(aliceBefore, h.alice.contents, "Alice: the same stacks in the same slots");
        assertArrayEquals(bobBefore, h.bob.contents, "Bob: the same stacks in the same slots");
        assertFalse(holdsLoan(h.alice) || holdsLoan(h.bob), "no loaned item survives");
    }

    @Test
    void theVaultIsWrittenInTheCommitStepAndTheKitIsLoanedOnArrival() {
        harness();
        DuelMatch match = fixedKitDuel();

        assertEquals(DuelPhase.WARMUP, match.phase());
        assertTrue(vaultFile(h.alice).isFile(), "written before the inventory was cleared");
        assertTrue(holdsLoan(h.alice) && holdsLoan(h.bob));
        assertEquals(1, h.alice.received().size(), "only the kit");
    }

    @Test
    void afterAFightTheInventoryIsIdenticalAndTheLoanIsGone() {
        harness();
        fixedKitDuel();
        h.toFight();

        h.kill(h.bob);
        h.respawn(h.bob);
        h.seconds(1);

        assertOwnGearBack();
        assertFalse(vaultFile(h.alice).exists() || vaultFile(h.bob).exists());
        assertTrue(h.vaultTeleports.isEmpty(), "the runtime sends them to the exit itself");
        assertEquals(h.exit, h.alice.location);
    }

    @Test
    void aLobbyTeleportThatFailsAfterTheCommitStepRestoresAtOnce() {
        harness();
        h.failAfterCommit.add(h.alice.id());

        DuelService.LockOutcome lock = h.duel(h.contract(GearMode.FIXED_KIT, DuelHarness.KIT_A, Stake.NONE, Stake.NONE));

        assertTrue(lock.ok());
        assertTrue(h.service.matches().isEmpty(), "cancelled");
        assertOwnGearBack();
        assertFalse(vaultFile(h.alice).exists());
    }

    @Test
    void aQuitGivesTheInventoryBackInTheQuitEventAndTheSpotOnTheNextJoin() {
        harness();
        fixedKitDuel();
        h.toFight();

        h.alice.online = false;
        h.service.onQuit(h.alice.player());
        assertArrayEquals(aliceBefore, h.alice.contents, "restored inside the quit event");
        assertTrue(vaultFile(h.alice).isFile(), "kept for the location until the next join");
        h.runNextTicks();
        h.seconds(1);

        h.alice.online = true;
        h.service.onJoin(h.alice.player());
        assertOwnGearBack();
        assertEquals(List.of("Alice"), h.vaultTeleports, "back to where she was before the duel");
        assertFalse(vaultFile(h.alice).exists());
    }

    @Test
    void anOutageGivesTheInventoryBackAtOnce() {
        harness();
        fixedKitDuel();
        h.toFight();
        h.apiDown = true;

        assertTrue(h.registry.releaseForOutage(h.alice.id()));

        assertOwnGearBack();
        assertTrue(h.service.matches().isEmpty());
    }

    @Test
    void afterACrashTheNextJoinSweepsTheLoanAndRestoresTheVault() {
        harness();
        fixedKitDuel();
        ItemStack[] aliceInTheKit = h.alice.contents.clone();

        // The server dies: no shutdown, a new runtime starts on the same files.
        DuelHarness restarted = new DuelHarness(dataFolder);
        restarted.loans.addAll(h.loans);
        restarted.alice.contents = aliceInTheKit;
        restarted.service.onJoin(restarted.alice.player());

        assertArrayEquals(aliceBefore, restarted.alice.contents);
        assertFalse(Arrays.stream(restarted.alice.contents).anyMatch(restarted.loans::contains));
        assertEquals(List.of("Alice"), restarted.vaultTeleports);
        assertFalse(vaultFile(restarted.alice).exists());
    }

    @Test
    void kitChoiceSwapsThePlaceholderInWarmupAndLocksItAtTheCountdown() {
        harness();
        DuelMatch match = h.duel(h.contract(GearMode.KIT_CHOICE, null, Stake.NONE, Stake.NONE)).match();
        assertEquals(DuelHarness.KIT_A, match.kitOf(DuelSide.CHALLENGER), "the first eligible kit as a placeholder");
        assertTrue(h.alice.received().getFirst().toString().startsWith("kit-11"));

        assertEquals(DuelStateMachine.KitChoice.CHOSEN, h.service.chooseKit(h.alice.player(), DuelHarness.KIT_B));

        assertEquals(1, h.alice.received().size(), "the placeholder was taken back");
        assertTrue(h.alice.received().getFirst().toString().startsWith("kit-12"));
        assertTrue(h.service.blocksKitConsumables(h.alice.id()), "kit consumables wait for the fight");
        h.seconds(1);
        assertEquals(DuelPhase.BATTLE_COUNTDOWN, match.phase());
        assertEquals(DuelStateMachine.KitChoice.NOT_IN_WARMUP, h.service.chooseKit(h.alice.player(), DuelHarness.KIT_A));
        assertFalse(h.service.blocksKitConsumables(h.alice.id()));
        assertTrue(h.service.blocksProjectiles(h.alice.id()), "no projectiles in the countdown");
        assertEquals(h.spawnA, h.service.frozenAt(h.alice.id()).orElseThrow(), "frozen to the spawn");
    }

    @Test
    void aLoanedItemCanNotBeStaked() {
        harness();
        ItemStack loaned = h.items.item("stray-loan");
        h.loans.add(loaned);

        assertTrue(h.duelItems.stakeRefusal(loaned).isPresent());
    }
}
