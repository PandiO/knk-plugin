package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.duels.DuelRefusedException;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Settlement;
import net.knightsandkings.knk.core.duel.DuelEndReason;
import net.knightsandkings.knk.core.duel.DuelEscrowState;
import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.Stake;
import net.knightsandkings.knk.core.duel.StakeContract;
import net.knightsandkings.knk.core.minigame.Participation;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.core.teleport.WarmupCancelReason;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Arena duels Phase 4 (KNG-217): the runtime end to end around the P3 rules - lock-in into the escrow, both lobby
 * teleports together (either fails → cancelled and refunded), the phases and the registry stages, a win paid after
 * the restore, a held result, a quit by phase, an outage, and the siege refusal.
 */
class DuelServiceTest {

    @TempDir
    Path dataFolder;

    private DuelHarness h;
    private ItemStack aliceSword;
    private ItemStack bobGold;

    private DuelHarness harness() {
        h = new DuelHarness(dataFolder);
        aliceSword = h.items.item("alice-sword");
        bobGold = h.items.item("bob-gold");
        h.alice.give(aliceSword);
        h.bob.give(bobGold);
        return h;
    }

    private StakeContract stakedKeep() {
        return h.contract(GearMode.OWN_GEAR_KEEP, null, new Stake(100, List.of(h.stake(h.alice, aliceSword))),
            new Stake(100, List.of(h.stake(h.bob, bobGold))));
    }

    @Test
    void aWinIsPaidToTheWinnerOnlyAfterTheMatchReleasedThem() {
        harness();
        DuelService.LockOutcome lock = h.duel(stakedKeep());

        assertTrue(lock.ok(), lock.message());
        DuelMatch match = lock.match();
        assertEquals(DuelPhase.WARMUP, match.phase());
        assertEquals(List.of(), h.alice.received(), "the stake left the inventory at lock-in");
        assertEquals(DuelEscrowState.ESCROWED, h.spool.find(match.token()).orElseThrow().state());
        assertEquals(List.of("Alice", "Bob"), h.lobbyMoves, "both lobby teleports start together");
        assertEquals(Optional.of(Participation.Stage.STAGING), h.registry.participationOf(h.alice.id()).map(Participation::stage));

        h.toFight();
        assertEquals(DuelPhase.FIGHT, match.phase());
        assertEquals(Optional.of(Participation.Stage.PLAYING), h.registry.participationOf(h.bob.id()).map(Participation::stage));
        assertTrue(h.registry.allowsCombat(h.alice.id(), h.bob.id()));
        assertEquals(h.spawnA, h.alice.location);
        assertEquals(h.spawnB, h.bob.location);

        h.kill(h.bob);
        assertEquals(DuelPhase.ENDING, match.phase());
        assertEquals(Settlement.PAID, match.settlement());
        assertEquals(List.of(), h.alice.received(), "no payout while the winner is still in the match");
        h.respawn(h.bob);
        assertEquals(h.exit, h.bob.location, "the loser respawns at the exit");
        h.seconds(1); // linger

        assertTrue(h.service.matches().isEmpty());
        assertEquals(h.exit, h.alice.location);
        assertEquals(List.of(aliceSword, bobGold), h.alice.received());
        assertEquals(List.of(), h.bob.received());
        assertFalse(h.registry.isMember(h.alice.id()));
        verify(h.api).complete(eq(41), eq(match.token()), any());
    }

    @Test
    void aHeldResultKeepsTheStakesAndTellsNobodyTheyWon() {
        harness();
        h.completeSettlement = Settlement.HELD;
        DuelMatch match = h.duel(stakedKeep()).match();
        h.toFight();

        h.kill(h.bob);
        h.respawn(h.bob);
        h.seconds(1);

        assertTrue(h.spool.isHeld(match.token()));
        assertEquals(List.of(), h.alice.received());
        assertEquals(List.of(), h.bob.received());
    }

    @Test
    void ifEitherLobbyTeleportFailsTheMatchIsCancelledAndBothAreRefunded() {
        harness();
        h.heldLobby.put(h.alice.id(), new CompletableFuture<>());
        h.lobbyOutcome.put(h.bob.id(), TeleportOutcome.cancelled(WarmupCancelReason.MOVED));

        DuelService.LockOutcome lock = h.duel(stakedKeep());

        assertTrue(lock.ok());
        assertTrue(h.service.matches().isEmpty(), "cancelled, and the other warmup was stopped");
        verify(h.api).cancel(anyInt(), eq(lock.match().token()), eq(DuelEndReason.TELEPORT_FAILED), any());
        assertEquals(List.of(aliceSword), h.alice.received());
        assertEquals(List.of(bobGold), h.bob.received());
        assertEquals(h.home, h.alice.location, "nobody moved");
    }

    @Test
    void aRefusedLockInTakesNothingAndClosesTheNegotiation() {
        harness();
        org.mockito.Mockito.doReturn(CompletableFuture.failedFuture(
            new DuelRefusedException("url", 422, "PairCooldown", "You two dueled too recently.", "{}"))).when(h.api).lock(any());

        DuelService.LockOutcome lock = h.duel(stakedKeep());

        assertFalse(lock.ok());
        assertEquals("You two dueled too recently.", lock.message());
        assertEquals(List.of(aliceSword), h.alice.received());
        assertEquals(List.of(bobGold), h.bob.received());
        assertTrue(h.service.matches().isEmpty());
        assertFalse(h.service.offers().isNegotiating(h.alice.id()));
        assertTrue(h.spool.entries().isEmpty());
    }

    @Test
    void aLockInWithAMissingItemIsRefusedBeforeAnythingIsTaken() {
        harness();
        StakeContract contract = stakedKeep();
        h.bob.contents = new ItemStack[41]; // Bob dropped his gold before Alice's offer was accepted

        DuelService.LockOutcome lock = h.duel(contract);

        assertFalse(lock.ok());
        assertEquals(List.of(aliceSword), h.alice.received());
        verify(h.api, never()).lock(any());
    }

    @Test
    void aQuitBeforeTheCountdownCancelsWithARefund() {
        harness();
        DuelMatch match = h.duel(stakedKeep()).match();

        h.bob.online = false;
        h.service.onQuit(h.bob.player());
        h.runNextTicks();

        assertTrue(h.service.matches().isEmpty());
        verify(h.api).cancel(anyInt(), eq(match.token()), eq(DuelEndReason.PLAYER_LEFT_LOBBY), any());
        assertEquals(List.of(aliceSword), h.alice.received());
        assertEquals(h.exit, h.alice.location);
        h.bob.online = true;
        h.service.onJoin(h.bob.player());
        assertEquals(List.of(bobGold), h.bob.received(), "the quitter's stake comes back on join");
    }

    @Test
    void aQuitInTheFightForfeitsAndTheOpponentIsPaid() {
        harness();
        DuelMatch match = h.duel(stakedKeep()).match();
        h.toFight();

        h.bob.online = false;
        h.service.onQuit(h.bob.player());
        h.runNextTicks();
        h.seconds(1);

        assertEquals(DuelEndReason.FORFEIT, match.result().reason());
        assertEquals(List.of(aliceSword, bobGold), h.alice.received());
    }

    @Test
    void dropModeTakesTheLosersDropsIntoTheSpoolBeforeTheyAreCleared() {
        harness();
        ItemStack bobArmour = h.items.item("bob-armour");
        h.bob.give(bobArmour);
        DuelMatch match = h.duel(h.contract(GearMode.OWN_GEAR_DROP, null, Stake.NONE, Stake.NONE)).match();
        h.toFight();

        DuelService.DeathHandling death = h.kill(h.bob);

        assertFalse(death.keepInventory());
        assertTrue(death.clearDrops());
        h.respawn(h.bob);
        h.seconds(1);
        assertEquals(List.of(), h.bob.received(), "the loser lost what was taken");
        assertEquals(List.of(aliceSword, bobGold, bobArmour), h.alice.received(), "own gear plus the spoils");
        assertNull(h.spool.find(match.token()).orElse(null));
    }

    @Test
    void aForfeitInDropModeTakesTheQuittersInventory() {
        harness();
        h.duel(h.contract(GearMode.OWN_GEAR_DROP, null, Stake.NONE, Stake.NONE));
        h.toFight();

        h.bob.online = false;
        h.service.onQuit(h.bob.player());
        h.runNextTicks();
        h.seconds(1);

        assertEquals(List.of(), h.bob.received());
        assertEquals(List.of(aliceSword, bobGold), h.alice.received());
    }

    @Test
    void anOutageAbortsTheMatchAndGivesEverythingBackAtOnce() {
        harness();
        DuelMatch match = h.duel(stakedKeep()).match();
        h.toFight();
        h.apiDown = true;

        assertTrue(h.registry.releaseForOutage(h.alice.id()));

        assertTrue(h.service.matches().isEmpty());
        assertEquals(List.of(aliceSword), h.alice.received());
        assertEquals(List.of(bobGold), h.bob.received());
        assertTrue(h.results.has(match.token()), "the coin refund waits in pending-results");
        h.apiDown = false;
        h.registry.resumeAfterOutage();
        assertFalse(h.results.has(match.token()));
    }

    @Test
    void theSiegeRefusesDuelistsAndNegotiators() {
        harness();
        h.service.offers().invite(h.alice.id(), h.bob.id(), h.settings(),
            h.contract(GearMode.OWN_GEAR_KEEP, null, Stake.NONE, Stake.NONE), h.tick);
        assertTrue(h.service.siegeJoinRefusal(h.alice.id()).isPresent(), "negotiating");

        h.service.accept(h.bob.player(), h.service.offers().outgoingOf(h.alice.id()).currentOffer().offerId()).join();
        h.runNextTicks();

        assertEquals(Optional.of("You are in a duel."), h.service.siegeJoinRefusal(h.bob.id()));
        assertEquals(Optional.of("You are in a duel."), h.registry.hubRefusal(h.bob.id()));
    }

    @Test
    void theTimeLimitDrawRefundsTheStakes() {
        harness();
        h.arena = DuelHarness.arena(true, true, true, true);
        DuelMatch match = h.duel(stakedKeep()).match();
        h.toFight();

        h.service.offerDraw(h.alice.player());
        h.service.offerDraw(h.bob.player());
        h.seconds(1);

        assertEquals(DuelEndReason.AGREED_DRAW, match.result().reason());
        assertEquals(List.of(aliceSword), h.alice.received());
        assertEquals(List.of(bobGold), h.bob.received());
        verify(h.api).cancel(anyInt(), eq(match.token()), eq(DuelEndReason.AGREED_DRAW), any());
    }

    @Test
    void theRuntimesOwnTeleportsPassAndEveryOtherTeleportOfADuelistIsRefused() {
        harness();
        h.duel(stakedKeep());

        var self = new net.knightsandkings.knk.core.minigame.MinigameTeleportAttempt(h.alice.id(), "Alice",
            net.knightsandkings.knk.core.teleport.TeleportKind.SPAWN, true, null, null, node -> false);
        var tpaToHer = new net.knightsandkings.knk.core.minigame.MinigameTeleportAttempt(java.util.UUID.randomUUID(), "Carol",
            net.knightsandkings.knk.core.teleport.TeleportKind.REQUEST, true, h.alice.id(), "Alice", node -> false);
        var arena = new net.knightsandkings.knk.core.minigame.MinigameTeleportAttempt(h.alice.id(), "Alice",
            net.knightsandkings.knk.core.teleport.TeleportKind.ARENA_SYSTEM, true, null, null, node -> false);
        var staff = new net.knightsandkings.knk.core.minigame.MinigameTeleportAttempt(h.alice.id(), "Alice",
            net.knightsandkings.knk.core.teleport.TeleportKind.STAFF, false, null, null, DuelService.ADMIN_NODE::equals);

        assertEquals("You can't teleport during a duel.", h.registry.teleportRefusal(self).orElseThrow().message());
        assertEquals("Alice is in a duel.", h.registry.teleportRefusal(tpaToHer).orElseThrow().message());
        assertTrue(h.registry.teleportRefusal(arena).isEmpty());
        assertTrue(h.registry.teleportRefusal(staff).isEmpty());
    }

    @Test
    void lootDropsAreHeldFromLockingUntilTheRestore() {
        harness();
        h.heldLobby.put(h.alice.id(), new CompletableFuture<>());
        DuelMatch match = h.duel(stakedKeep()).match();
        assertEquals(DuelPhase.TRAVELLING, match.phase());
        assertTrue(h.registry.holdsLootDrops(h.alice.id()), "from the lock-in on");

        h.finishLobby(h.alice, TeleportOutcome.teleported());
        h.toFight();
        h.kill(h.bob);
        assertEquals(DuelPhase.ENDING, match.phase());
        assertTrue(h.registry.holdsLootDrops(h.alice.id()), "still while the winner lingers");
        assertTrue(h.restored.stream().noneMatch(p -> p == h.alice.player()));

        h.respawn(h.bob);
        h.seconds(1);
        assertFalse(h.registry.holdsLootDrops(h.alice.id()));
        assertTrue(h.restored.contains(h.alice.player()), "held deliveries are handed over after the restore");
    }
}
