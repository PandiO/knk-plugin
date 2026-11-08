package net.knightsandkings.knk.paper.siege.survival.results;

import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MatchResultDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.SettlementDto;
import net.knightsandkings.knk.api.impl.SiegeSurvivalMatchApiImpl;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.paper.siege.survival.results.SurvivalResultPoster.Outcome;
import net.knightsandkings.knk.paper.siege.survival.results.SurvivalResultPoster.State;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Posting rules (link S8b): retry transport errors, stop on SettlementMismatch, fetch the stored row on a repeat. */
class SurvivalResultPosterTest {

    private final SiegeSurvivalMatchApiImpl api = mock(SiegeSurvivalMatchApiImpl.class);
    private final List<LogRecord> logs = new ArrayList<>();
    private final Logger logger = Logger.getLogger("SurvivalResultPosterTest-" + System.nanoTime());
    private final List<Long> delays = new ArrayList<>();
    private final SurvivalResultPoster poster = new SurvivalResultPoster(api, millis -> {
        delays.add(millis);
        return Runnable::run;
    }, 3, 1_000, 60_000, logger);
    private final MatchResultDto body = new MatchResultDto(42, 1, 1, null, null, OffsetDateTime.now(), OffsetDateTime.now(), "TeamWipe",
        3, 2, 5, 0, List.of(), new SettlementDto(true, 30, 3, "siege-survival:42", null, null, null), null, null, null, null);
    private final MatchResultDto stored = new MatchResultDto(42, 1, 1, null, null, OffsetDateTime.now(), OffsetDateTime.now(), "TeamWipe",
        3, 2, 5, 0, List.of(), new SettlementDto(true, 30, 3, "siege-survival:42", OffsetDateTime.now(), "posting-7", 1), null, "v1", "Plugin", OffsetDateTime.now());

    SurvivalResultPosterTest() {
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        });
        when(api.conflictCode(any(ApiException.class))).thenAnswer(inv -> {
            ApiException e = inv.getArgument(0);
            String b = e.getResponseBody() == null ? "" : e.getResponseBody();
            int i = b.indexOf("\"code\":\"");
            return i < 0 ? null : b.substring(i + 8, b.indexOf('"', i + 8));
        });
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException("url", 409, "conflict", "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    @Test
    void aStoredAnswerIsPostedAndLogged() {
        when(api.postResult(eq(42L), any())).thenReturn(CompletableFuture.completedFuture(stored));
        Outcome o = poster.post(42, body, "test").join();
        assertEquals(State.POSTED, o.state());
        assertSame(stored, o.stored());
        assertFalse(o.fromStore());
        assertEquals(1, o.attempts());
        assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.INFO && r.getMessage().contains("ledger posting posting-7")));
        verify(api, never()).getResult(anyLong());
    }

    @Test
    void transportErrorsRetryTheSameBodyWithBackoff() {
        AtomicInteger calls = new AtomicInteger();
        when(api.postResult(eq(42L), any())).thenAnswer(inv -> calls.incrementAndGet() < 3
            ? CompletableFuture.failedFuture(new ApiException("url", "connect failed", new IOException("refused")))
            : CompletableFuture.completedFuture(stored));
        Outcome o = poster.post(42, body, "test").join();
        assertEquals(State.POSTED, o.state());
        assertEquals(3, o.attempts());
        assertEquals(List.of(1_000L, 2_000L), delays);
        verify(api, times(3)).postResult(eq(42L), same(body));
    }

    private static MatchResultDto same(MatchResultDto b) {
        return org.mockito.ArgumentMatchers.same(b);
    }

    @Test
    void givingUpAfterTheLastTransportFailureIsATransportFailure() {
        when(api.postResult(eq(42L), any())).thenReturn(CompletableFuture.failedFuture(new ApiException("url", 503, "down", "")));
        Outcome o = poster.post(42, body, "test").join();
        assertEquals(State.FAILED, o.state());
        assertEquals(SurvivalResultPoster.CODE_TRANSPORT, o.code());
        assertEquals(3, o.attempts());
        assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.SEVERE && r.getMessage().contains("giving up")));
    }

    @Test
    void settlementMismatchIsSevereAndFinalWithTheApisMessage() {
        when(api.postResult(eq(42L), any())).thenReturn(CompletableFuture.failedFuture(
            conflict("SettlementMismatch", "player 101: coinsSettled 93 expected 92")));
        Outcome o = poster.post(42, body, "test").join();
        assertEquals(State.FAILED, o.state());
        assertEquals("SettlementMismatch", o.code());
        assertEquals("player 101: coinsSettled 93 expected 92", o.message());
        assertEquals(1, o.attempts(), "never retried, never adjusted");
        assertTrue(delays.isEmpty());
        assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.SEVERE && r.getMessage().contains("SettlementMismatch")
            && r.getMessage().contains("coinsSettled 93 expected 92")));
        verify(api, never()).getResult(anyLong());
    }

    @Test
    void resultAlreadyStoredAndMatchAbortedUseTheStoredRow() {
        for (String code : List.of("ResultAlreadyStored", "MatchAborted")) {
            logs.clear();
            when(api.postResult(eq(42L), any())).thenReturn(CompletableFuture.failedFuture(conflict(code, "exists")));
            when(api.getResult(42L)).thenReturn(CompletableFuture.completedFuture(stored));
            Outcome o = poster.post(42, body, "test").join();
            assertEquals(State.POSTED, o.state(), code);
            assertTrue(o.fromStore());
            assertSame(stored, o.stored());
            assertEquals(code, o.code());
        }
        when(api.postResult(eq(42L), any())).thenReturn(CompletableFuture.failedFuture(conflict("MatchAborted", "exists")));
        when(api.getResult(42L)).thenReturn(CompletableFuture.completedFuture(null));
        Outcome o = poster.post(42, body, "test").join();
        assertEquals(State.FAILED, o.state(), "the row could not be fetched");
        assertEquals("MatchAborted", o.code());
    }

    @Test
    void otherRejectionsAreFinal() {
        when(api.postResult(eq(42L), any())).thenReturn(CompletableFuture.failedFuture(conflict("MatchNotInProgress", "Created")));
        assertEquals("MatchNotInProgress", poster.post(42, body, "test").join().code());
        when(api.postResult(eq(42L), any())).thenReturn(CompletableFuture.failedFuture(new ApiException("url", 400, "bad", "{\"message\":\"bonusKills > kills\"}")));
        Outcome bad = poster.post(42, body, "test").join();
        assertEquals(SurvivalResultPoster.CODE_REJECTED, bad.code());
        assertEquals("bonusKills > kills", bad.message());
        when(api.postResult(eq(42L), any())).thenReturn(CompletableFuture.completedFuture(null));
        assertEquals(SurvivalResultPoster.CODE_UNKNOWN_MATCH, poster.post(42, body, "test").join().code());
        assertTrue(delays.isEmpty(), "4xx answers are never retried");
    }

    @Test
    void messagesAreReadOffTheErrorBody() {
        assertEquals("a \"quoted\" word", SurvivalResultPoster.messageOf("{\"code\":\"X\",\"message\":\"a \\\"quoted\\\" word\"}"));
        assertEquals("", SurvivalResultPoster.messageOf(null));
        assertEquals("plain", SurvivalResultPoster.messageOf("plain"));
        assertNull(SurvivalResultPoster.unwrap(null));
    }
}
