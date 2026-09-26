package net.knightsandkings.knk.core.teleport;

import net.knightsandkings.knk.core.teleport.TeleportRequestBook.Direction;
import net.knightsandkings.knk.core.teleport.TeleportRequestBook.Request;
import net.knightsandkings.knk.core.teleport.TeleportRequestBook.SendResult;
import net.knightsandkings.knk.core.teleport.TeleportRequestBook.SendStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /tpa, /tpahere request state (docs/specs/teleport/DESIGN.md §3.5, Phase 3). */
class TeleportRequestBookTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("Alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("Bob".getBytes());
    private static final UUID CAROL = UUID.nameUUIDFromBytes("Carol".getBytes());
    private static final UUID DAVE = UUID.nameUUIDFromBytes("Dave".getBytes());

    private final TeleportRequestBook book = new TeleportRequestBook(30, 5);

    // ===== send =====

    @Test
    void sentRequestIsPendingForTheTarget() {
        SendResult result = book.send(ALICE, BOB, Direction.TO_TARGET, 1_000);

        assertTrue(result.isSent());
        assertEquals(ALICE, result.request().requester());
        assertEquals(31_000, result.request().expiresAtMillis());
        assertTrue(result.replaced().isEmpty());
        assertTrue(result.dropped().isEmpty());
        assertEquals(List.of(result.request()), book.incoming(BOB, 1_000));
        assertEquals(result.request(), book.outgoing(ALICE, 1_000).orElseThrow());
    }

    @Test
    void tpaMovesTheRequesterAndTpahereMovesTheTarget() {
        Request tpa = book.send(ALICE, BOB, Direction.TO_TARGET, 0).request();
        Request tpahere = book.send(CAROL, DAVE, Direction.TO_REQUESTER, 0).request();

        assertEquals(ALICE, tpa.mover());
        assertEquals(BOB, tpa.stationary());
        assertEquals(DAVE, tpahere.mover());
        assertEquals(CAROL, tpahere.stationary());
    }

    @Test
    void askingYourselfIsRefused() {
        SendResult result = book.send(ALICE, ALICE, Direction.TO_TARGET, 0);

        assertEquals(SendStatus.SELF, result.status());
        assertNull(result.request());
        assertEquals(0, book.size());
    }

    @Test
    void sameRequestAgainIsADuplicateAndKeepsTheOriginalExpiry() {
        Request first = book.send(ALICE, BOB, Direction.TO_TARGET, 0).request();

        SendResult again = book.send(ALICE, BOB, Direction.TO_TARGET, 10_000);

        assertEquals(SendStatus.DUPLICATE, again.status());
        assertEquals(first, again.request());
        assertEquals(30_000, book.outgoing(ALICE, 10_000).orElseThrow().expiresAtMillis());
    }

    @Test
    void newOutgoingRequestReplacesTheOldOne() {
        Request toBob = book.send(ALICE, BOB, Direction.TO_TARGET, 0).request();

        SendResult toCarol = book.send(ALICE, CAROL, Direction.TO_TARGET, 1_000);

        assertTrue(toCarol.isSent());
        assertEquals(toBob, toCarol.replaced().orElseThrow());
        assertTrue(book.incoming(BOB, 1_000).isEmpty());
        assertEquals(CAROL, book.outgoing(ALICE, 1_000).orElseThrow().target());
    }

    @Test
    void switchingDirectionToTheSameTargetReplacesTheRequest() {
        book.send(ALICE, BOB, Direction.TO_TARGET, 0);

        SendResult here = book.send(ALICE, BOB, Direction.TO_REQUESTER, 1_000);

        assertTrue(here.isSent());
        assertEquals(Direction.TO_TARGET, here.replaced().orElseThrow().direction());
        assertEquals(List.of(here.request()), book.incoming(BOB, 1_000));
    }

    @Test
    void onlyOnePendingRequestPerPairOfPlayers() {
        Request bobsRequest = book.send(BOB, ALICE, Direction.TO_TARGET, 0).request();

        SendResult alicesRequest = book.send(ALICE, BOB, Direction.TO_REQUESTER, 1_000);

        assertEquals(SendStatus.REVERSE_PENDING, alicesRequest.status());
        assertEquals(bobsRequest, alicesRequest.request());
        assertTrue(book.outgoing(ALICE, 1_000).isEmpty());
    }

    @Test
    void reverseRequestIsAllowedOnceTheOtherOneExpired() {
        book.send(BOB, ALICE, Direction.TO_TARGET, 0);

        assertTrue(book.send(ALICE, BOB, Direction.TO_TARGET, 30_000).isSent());
    }

    @Test
    void perTargetCapDropsTheOldestRequest() {
        TeleportRequestBook small = new TeleportRequestBook(30, 2);
        Request fromAlice = small.send(ALICE, DAVE, Direction.TO_TARGET, 0).request();
        Request fromBob = small.send(BOB, DAVE, Direction.TO_TARGET, 1_000).request();

        SendResult fromCarol = small.send(CAROL, DAVE, Direction.TO_TARGET, 2_000);

        assertTrue(fromCarol.isSent());
        assertEquals(List.of(fromAlice), fromCarol.dropped());
        assertEquals(List.of(fromCarol.request(), fromBob), small.incoming(DAVE, 2_000));
        assertTrue(small.outgoing(ALICE, 2_000).isEmpty());
    }

    @Test
    void loweringTheCapDropsEverythingOverIt() {
        book.send(ALICE, DAVE, Direction.TO_TARGET, 0);
        book.send(BOB, DAVE, Direction.TO_TARGET, 1_000);
        book.configure(30, 1);

        SendResult fromCarol = book.send(CAROL, DAVE, Direction.TO_TARGET, 2_000);

        assertEquals(2, fromCarol.dropped().size());
        assertEquals(List.of(fromCarol.request()), book.incoming(DAVE, 2_000));
    }

    // ===== expiry =====

    @Test
    void requestExpiresAfterThirtySeconds() {
        book.send(ALICE, BOB, Direction.TO_TARGET, 1_000);

        assertEquals(1, book.incoming(BOB, 30_999).size());
        assertEquals(1, book.incoming(BOB, 30_999).get(0).secondsLeft(30_999));
        assertTrue(book.incoming(BOB, 31_000).isEmpty());
        assertTrue(book.take(BOB, null, 31_000).isEmpty(), "an expired request can't be accepted");
    }

    @Test
    void sweepHandsOutEachExpiredRequestOnce() {
        Request request = book.send(ALICE, BOB, Direction.TO_TARGET, 0).request();
        book.send(CAROL, BOB, Direction.TO_TARGET, 10_000);

        assertTrue(book.sweepExpired(29_999).isEmpty());
        assertEquals(List.of(request), book.sweepExpired(30_000));
        assertTrue(book.sweepExpired(30_001).isEmpty());
        assertEquals(1, book.size());
    }

    @Test
    void newExpirySettingAppliesToNewRequests() {
        book.configure(10, 5);

        assertEquals(10_000, book.send(ALICE, BOB, Direction.TO_TARGET, 0).request().expiresAtMillis());
    }

    @Test
    void settingsAreClamped() {
        TeleportRequestBook odd = new TeleportRequestBook(0, 0);
        odd.send(ALICE, CAROL, Direction.TO_TARGET, 0);

        assertEquals(1_000, odd.send(BOB, CAROL, Direction.TO_TARGET, 0).request().expiresAtMillis());
        assertEquals(1, odd.incoming(CAROL, 0).size(), "cap is at least one");
    }

    // ===== answer / withdraw =====

    @Test
    void acceptTakesTheNewestRequestAndRemovesIt() {
        book.send(ALICE, DAVE, Direction.TO_TARGET, 0);
        Request fromBob = book.send(BOB, DAVE, Direction.TO_TARGET, 1_000).request();

        assertEquals(fromBob, book.take(DAVE, null, 2_000).orElseThrow());
        assertTrue(book.outgoing(BOB, 2_000).isEmpty());
        assertEquals(1, book.incoming(DAVE, 2_000).size());
    }

    @Test
    void acceptByNameTakesThatPlayersRequest() {
        Request fromAlice = book.send(ALICE, DAVE, Direction.TO_TARGET, 0).request();
        book.send(BOB, DAVE, Direction.TO_TARGET, 1_000);

        assertEquals(fromAlice, book.take(DAVE, ALICE, 2_000).orElseThrow());
        assertTrue(book.take(DAVE, CAROL, 2_000).isEmpty());
    }

    @Test
    void doubleAcceptIsANoOp() {
        book.send(ALICE, BOB, Direction.TO_TARGET, 0);

        assertTrue(book.take(BOB, ALICE, 1_000).isPresent());
        assertTrue(book.take(BOB, ALICE, 1_000).isEmpty());
        assertTrue(book.take(BOB, null, 1_000).isEmpty());
    }

    @Test
    void onlyTheTargetCanTakeARequest() {
        book.send(ALICE, BOB, Direction.TO_TARGET, 0);

        assertTrue(book.take(ALICE, null, 1_000).isEmpty());
        assertTrue(book.take(CAROL, ALICE, 1_000).isEmpty());
        assertEquals(1, book.incoming(BOB, 1_000).size());
    }

    @Test
    void requesterCanWithdrawTheirRequest() {
        Request request = book.send(ALICE, BOB, Direction.TO_TARGET, 0).request();

        assertEquals(request, book.cancelOutgoing(ALICE, 1_000).orElseThrow());
        assertTrue(book.incoming(BOB, 1_000).isEmpty());
        assertTrue(book.cancelOutgoing(ALICE, 1_000).isEmpty());
        assertTrue(book.take(BOB, null, 1_000).isEmpty(), "a withdrawn request can't be accepted");
    }

    @Test
    void withdrawingAnExpiredRequestFindsNothing() {
        book.send(ALICE, BOB, Direction.TO_TARGET, 0);

        assertTrue(book.cancelOutgoing(ALICE, 30_000).isEmpty());
    }

    @Test
    void clearRemovesEverythingAPlayerSentOrReceived() {
        Request aliceToBob = book.send(ALICE, BOB, Direction.TO_TARGET, 0).request();
        Request carolToAlice = book.send(CAROL, ALICE, Direction.TO_REQUESTER, 0).request();
        Request daveToBob = book.send(DAVE, BOB, Direction.TO_TARGET, 0).request();

        List<Request> cleared = book.clear(ALICE, 1_000);

        assertEquals(2, cleared.size());
        assertTrue(cleared.containsAll(List.of(aliceToBob, carolToAlice)));
        assertEquals(List.of(daveToBob), book.incoming(BOB, 1_000));
        assertFalse(book.outgoing(CAROL, 1_000).isPresent());
    }

    @Test
    void clearDoesNotReportExpiredRequestsButStillRemovesThem() {
        book.send(ALICE, BOB, Direction.TO_TARGET, 0);

        assertTrue(book.clear(BOB, 30_000).isEmpty());
        assertEquals(0, book.size());
    }
}
