package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.duels.DuelRefusedException;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.MatchRecord;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Result;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Review;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Settlement;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Status;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.UndeliveredItems;
import net.knightsandkings.knk.core.duel.DuelEndReason;
import net.knightsandkings.knk.core.duel.DuelEscrowPlan;
import net.knightsandkings.knk.core.duel.DuelEscrowState;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.DuelMatchesApi;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Arena duels Phase 4 (KNG-217): startup recovery (DESIGN §7.3) - pending results first, then abort-unfinished, then
 * every unsettled spool file from its record - and settling a file from the API's answer.
 */
class DuelRecoveryTest {

    @TempDir
    Path dataFolder;

    private final DuelTestItems items = new DuelTestItems();
    private final DuelMatchesApi api = mock(DuelMatchesApi.class);
    private final ItemStack sword = items.item("sword");
    private final ItemStack gold = items.item("gold");
    private final DuelTestItems.TestPlayer alice = items.player("Alice");
    private final DuelTestItems.TestPlayer bob = items.player("Bob");

    private DuelEscrowSpool spool() {
        return new DuelEscrowSpool(dataFolder.resolve("duel-escrow").toFile(), Logger.getLogger("test"), items.codec());
    }

    private DuelPendingResults results() {
        return new DuelPendingResults(api, dataFolder.resolve("duel-escrow/pending-results").toFile(), Logger.getLogger("test"));
    }

    private UUID open(DuelEscrowSpool spool, boolean escrowed) {
        UUID token = UUID.randomUUID();
        spool.open(new DuelEscrowSpool.Entry(token, 7, new DuelEscrowSpool.Party(alice.id(), 1, "Alice"),
            new DuelEscrowSpool.Party(bob.id(), 2, "Bob"), List.of(sword), List.of(gold)));
        if (escrowed) spool.escrowed(token, 41);
        return token;
    }

    static MatchRecord record(UUID token, Status status, Integer winner, Review review, UndeliveredItems undelivered) {
        return new MatchRecord(41, token, 7, null, 1, 2, GearMode.OWN_GEAR_KEEP, null, null, null, status,
            null, winner, 100, 100, 5, 10, true, review, null, List.of(), undelivered, List.of(), List.of(), null, null);
    }

    private DuelRecovery recovery(DuelEscrowSpool spool, DuelPendingResults results) {
        return new DuelRecovery(api, spool, results, Runnable::run, Logger.getLogger("test"));
    }

    @Test
    void theDecisionFollowsTheRecord() {
        DuelEscrowSpool spool = spool();
        DuelEscrowSpool.Entry entry = spool.find(open(spool, true)).orElseThrow();
        UUID token = entry.token();

        assertEquals(DuelEscrowState.REFUNDED, DuelRecovery.planFor(entry, null).next(), "no record: never landed");
        assertTrue(DuelRecovery.planFor(entry, record(token, Status.COMPLETED, 1, Review.HELD, UndeliveredItems.NONE))
            .keepsEverything());
        assertEquals(DuelEscrowPlan.payWinner(net.knightsandkings.knk.core.duel.DuelSide.OPPONENT),
            DuelRecovery.planFor(entry, record(token, Status.COMPLETED, 2, Review.NONE, UndeliveredItems.NONE)));
        assertEquals(DuelEscrowPlan.payWinner(net.knightsandkings.knk.core.duel.DuelSide.CHALLENGER),
            DuelRecovery.planFor(entry, record(token, Status.COMPLETED, 1, Review.RELEASED, UndeliveredItems.NONE)));
        assertEquals(DuelEscrowState.REFUNDED, DuelRecovery.planFor(entry,
            record(token, Status.COMPLETED, 1, Review.VOIDED, UndeliveredItems.RETURN_TO_OWNERS)).next());
        assertEquals(DuelEscrowState.REFUNDED, DuelRecovery.planFor(entry,
            record(token, Status.ABORTED, null, Review.NONE, UndeliveredItems.NONE)).next());
        assertEquals(DuelEscrowState.REFUNDED, DuelRecovery.planFor(entry,
            record(token, Status.COMPLETED, null, Review.NONE, UndeliveredItems.NONE)).next(), "a draw");
        assertEquals(null, DuelRecovery.planFor(entry,
            record(token, Status.IN_PROGRESS, null, Review.NONE, UndeliveredItems.NONE)), "still open: leave it");
    }

    @Test
    void startupReplaysPendingResultsBeforeAbortingAndThenSettlesEveryFile() {
        DuelEscrowSpool spool = spool();
        UUID finished = open(spool, true);
        UUID aborted = open(spool, true);
        UUID neverLanded = open(spool, false);
        DuelPendingResults results = results();
        when(api.complete(eq(41), eq(finished), any())).thenReturn(CompletableFuture.completedFuture(
            new Result(record(finished, Status.COMPLETED, 1, Review.NONE, UndeliveredItems.NONE), Settlement.PAID, false, null)));
        results.report(new DuelPendingResults.Pending(finished, 41, DuelEndReason.WIN, 1, null, List.of(), null));
        // The first send happened before the "crash"; a second one is answered the same (idempotent).
        when(api.abortUnfinished(DuelEndReason.SERVER_RESTART)).thenReturn(CompletableFuture.completedFuture(List.of(41)));
        when(api.findByToken(aborted)).thenReturn(CompletableFuture.completedFuture(
            Optional.of(record(aborted, Status.ABORTED, null, Review.NONE, UndeliveredItems.NONE))));
        when(api.findByToken(neverLanded)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(api.findByToken(finished)).thenReturn(CompletableFuture.completedFuture(
            Optional.of(record(finished, Status.COMPLETED, 1, Review.NONE, UndeliveredItems.NONE))));

        DuelEscrowSpool restarted = spool();
        DuelRecovery recovery = recovery(restarted, results);
        recovery.recoverOnStartup().join();

        assertTrue(recovery.isStartupDone());
        assertEquals(DuelEscrowState.REFUNDED, restarted.find(aborted).orElseThrow().state());
        assertEquals(DuelEscrowState.REFUNDED, restarted.find(neverLanded).orElseThrow().state());
        assertEquals(DuelEscrowState.SETTLED, restarted.find(finished).orElseThrow().state());
        restarted.deliver(alice.player());
        assertEquals(3, alice.received().stream().filter(sword::equals).count(), "won one, got two refunds");
        assertEquals(1, alice.received().stream().filter(gold::equals).count());
    }

    @Test
    void aSpooledResultIsSentBeforeAbortUnfinished() {
        DuelEscrowSpool spool = spool();
        UUID token = open(spool, true);
        DuelPendingResults results = results();
        CompletableFuture<Result> never = new CompletableFuture<>();
        when(api.complete(anyInt(), any(), any())).thenReturn(never);
        results.report(new DuelPendingResults.Pending(token, 41, DuelEndReason.WIN, 1, null, List.of(), null));
        never.completeExceptionally(new ApiException("down"));
        assertTrue(results.has(token), "kept for the replay");

        when(api.complete(anyInt(), any(), any())).thenReturn(CompletableFuture.completedFuture(
            new Result(record(token, Status.COMPLETED, 1, Review.HELD, UndeliveredItems.NONE), Settlement.HELD, true, null)));
        when(api.abortUnfinished(any())).thenReturn(CompletableFuture.completedFuture(List.of()));
        DuelEscrowSpool restarted = spool();
        recovery(restarted, results).recoverOnStartup().join();

        InOrder order = inOrder(api);
        order.verify(api, org.mockito.Mockito.times(2)).complete(eq(41), eq(token), any());
        order.verify(api).abortUnfinished(DuelEndReason.SERVER_RESTART);
        assertFalse(results.has(token));
        assertTrue(restarted.isHeld(token), "a held answer keeps the items");
    }

    @Test
    void anUnreachableApiLeavesEverythingAndAsksForARetry() {
        DuelEscrowSpool spool = spool();
        UUID token = open(spool, true);
        when(api.abortUnfinished(any())).thenReturn(CompletableFuture.failedFuture(new ApiException("down")));

        DuelEscrowSpool restarted = spool();
        DuelRecovery recovery = recovery(restarted, results());
        recovery.recoverOnStartup().join();

        assertFalse(recovery.isStartupDone());
        assertTrue(recovery.needsRetry());
        assertEquals(DuelEscrowState.ESCROWED, restarted.find(token).orElseThrow().state());
        verify(api, never()).findByToken(any());
    }

    @Test
    void aHeldFileIsSettledOnceStaffReleasedOrVoidedIt() {
        DuelEscrowSpool spool = spool();
        UUID released = open(spool, true);
        UUID destroyed = open(spool, true);
        spool.settle(released, DuelEscrowPlan.hold());
        spool.settle(destroyed, DuelEscrowPlan.hold());
        when(api.findByToken(released)).thenReturn(CompletableFuture.completedFuture(
            Optional.of(record(released, Status.COMPLETED, 2, Review.RELEASED, UndeliveredItems.NONE))));
        when(api.findByToken(destroyed)).thenReturn(CompletableFuture.completedFuture(
            Optional.of(record(destroyed, Status.COMPLETED, 2, Review.VOIDED, UndeliveredItems.DESTROY))));
        DuelRecovery recovery = recovery(spool, results());

        assertTrue(recovery.refresh(released).join());
        assertTrue(recovery.refresh(destroyed).join());

        assertEquals(DuelEscrowState.SETTLED, spool.find(released).orElseThrow().state());
        assertTrue(spool.find(destroyed).isEmpty(), "destroyed by staff");
        spool.deliver(bob.player());
        assertEquals(List.of(sword, gold), bob.received());
    }

    @Test
    void aRefusedResultIsSettledFromTheRecord() {
        DuelEscrowSpool spool = spool();
        UUID token = open(spool, true);
        DuelPendingResults results = results();
        when(api.cancel(eq(41), eq(token), eq(DuelEndReason.TIME_LIMIT), any())).thenReturn(CompletableFuture.failedFuture(
            new DuelRefusedException("url", 409, "WrongState", "already done", "{}")));
        when(api.findByToken(token)).thenReturn(CompletableFuture.completedFuture(
            Optional.of(record(token, Status.CANCELLED, null, Review.NONE, UndeliveredItems.NONE))));
        DuelRecovery recovery = recovery(spool, results);

        results.report(new DuelPendingResults.Pending(token, 41, DuelEndReason.TIME_LIMIT, null, null, List.of(), null))
            .exceptionally(error -> null).join();
        recovery.settleFromResult(token, null);

        assertFalse(results.has(token));
        assertEquals(DuelEscrowState.REFUNDED, spool.find(token).orElseThrow().state());
    }
}
