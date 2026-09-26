package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.api.dto.PlayerNotificationDto;
import net.knightsandkings.knk.api.mapper.UsersMapper;
import net.knightsandkings.knk.core.domain.currency.CurrencyError;
import net.knightsandkings.knk.core.domain.currency.CurrencyException;
import net.knightsandkings.knk.core.domain.currency.LedgerPage;
import net.knightsandkings.knk.core.domain.currency.TransferOutcome;
import net.knightsandkings.knk.core.domain.users.BalanceCurrency;
import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * KNG-21 Phase 3: /pay's HTTP side - one Idempotency-Key per payment reused on every retry, the
 * acting player, error-code mapping, and the "outcome unknown" answer once retries run out.
 */
class CurrencyApiImplTest {

    private static final String COMPLETED = """
        {"status":"Completed","transactionId":5,"publicId":"01M3FHW0E9NNVK4SWMK3BR002T","replayed":false,"currency":"Coins",
         "amount":100,"fee":0,"senderUserId":5,"senderUsername":"alice","recipientUserId":6,"recipientUsername":"bob",
         "senderBalances":{"userId":5,"coins":13650,"gems":53,"experiencePoints":3032},"pending":null,"createdAt":"2026-09-26T19:08:00.84Z"}
        """;

    private static final String PENDING = """
        {"status":"PendingConfirmation","transactionId":null,"publicId":null,"replayed":false,"currency":"Coins","amount":150000,
         "fee":0,"senderUserId":5,"senderUsername":"alice","recipientUserId":6,"recipientUsername":"bob",
         "senderBalances":{"userId":5,"coins":500000,"gems":0,"experiencePoints":0},
         "pending":{"publicId":"01M3FHX1ZDRTAB76K01ZYK0PCZ","status":"Pending","currency":"Coins","amount":150000,"fee":0,
                    "recipientUserId":6,"recipientUsername":"bob","createdAt":"2026-09-26T19:08:35.17Z",
                    "expiresAt":"2026-09-26T19:09:35.1702872","expiresInSeconds":60}}
        """;

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final List<Duration> slept = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    /** An API whose n-th call (0-based) answers {@code responder.apply(n)}; null = connection failure. */
    private CurrencyApiImpl api(Function<Integer, Response.Builder> responder) {
        OkHttpClient client = new OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain -> {
            Request request = chain.request();
            int n = seen.size();
            seen.add(request);
            if (request.body() != null) {
                Buffer buffer = new Buffer();
                request.body().writeTo(buffer);
                bodies.add(buffer.readUtf8());
            }
            Response.Builder answer = responder.apply(n);
            if (answer == null) {
                throw new IOException("connection reset");
            }
            return answer.request(request).protocol(Protocol.HTTP_1_1).build();
        }).build();
        return new CurrencyApiImpl("http://api.test/api", client, new ObjectMapper(), new NoAuthProvider(), executor, false,
            CurrencyApiImpl.DEFAULT_RETRY_DELAYS, slept::add);
    }

    private static Response.Builder json(int code, String body) {
        return new Response.Builder().code(code).message("x").body(ResponseBody.create(body, MediaType.get("application/json")));
    }

    @Test
    void transfer_postsTheRequestAsTheSender() throws Exception {
        TransferOutcome outcome = api(n -> json(200, COMPLETED)).transfer(5, 6, BalanceCurrency.COINS, 100, false).join();

        Request request = seen.get(0);
        assertEquals("POST", request.method());
        assertEquals("http://api.test/api/currency/transfers", request.url().toString());
        assertEquals("5", request.header(CurrencyApiImpl.ACTING_USER_HEADER));
        assertNotNull(request.header(BaseApiImpl.IDEMPOTENCY_KEY_HEADER));
        JsonNode body = new ObjectMapper().readTree(bodies.get(0));
        assertEquals(6, body.get("recipientUserId").asInt());
        assertEquals("Coins", body.get("currency").asText());
        assertEquals(100, body.get("amount").asLong());

        assertTrue(outcome.completed());
        assertEquals(13650, outcome.senderBalances().coins());
        assertEquals("bob", outcome.recipientUsername());
    }

    @Test
    void retriesAfterFailures_sendTheSameKey_andNewPaymentsGetANewOne() {
        CurrencyApiImpl api = api(n -> switch (n) {
            case 0 -> null;                          // connection reset
            case 1 -> json(503, "{}");               // server trouble
            default -> json(200, COMPLETED);
        });

        TransferOutcome outcome = api.transfer(5, 6, BalanceCurrency.COINS, 100, false).join();
        api.transfer(5, 6, BalanceCurrency.COINS, 100, false).join();

        assertTrue(outcome.completed());
        assertEquals(4, seen.size());
        String key = seen.get(0).header(BaseApiImpl.IDEMPOTENCY_KEY_HEADER);
        assertEquals(key, seen.get(1).header(BaseApiImpl.IDEMPOTENCY_KEY_HEADER));
        assertEquals(key, seen.get(2).header(BaseApiImpl.IDEMPOTENCY_KEY_HEADER));
        assertNotEquals(key, seen.get(3).header(BaseApiImpl.IDEMPOTENCY_KEY_HEADER));
        assertEquals(List.of(Duration.ofMillis(250), Duration.ofSeconds(1)), slept);
    }

    @Test
    void noAnswerAfterEveryRetry_isAnUnknownOutcome() {
        CurrencyApiImpl api = api(n -> null);

        CompletionException thrown = assertThrows(CompletionException.class,
            () -> api.transfer(5, 6, BalanceCurrency.COINS, 100, false).join());

        CurrencyException error = CurrencyException.find(thrown);
        assertNotNull(error);
        assertTrue(error.outcomeUnknown());
        assertEquals(api.maxAttempts(), seen.size());
        assertEquals(1, seen.stream().map(r -> r.header(BaseApiImpl.IDEMPOTENCY_KEY_HEADER)).distinct().count());
    }

    @Test
    void refusals_areNotRetried_andCarryTheCodeAndDetails() {
        CurrencyApiImpl api = api(n -> json(422, """
            {"error":"DailyCapExceeded","code":"DailyCapExceeded","message":"DailyCapExceeded: Daily limit reached - you can send 1,500 more coins in the next 24h.",
             "details":{"currency":"Coins","cap":2000000,"sent":1998500,"remaining":1500,"resetsAt":"2026-09-27T10:00:00Z"}}
            """));

        CurrencyException error = CurrencyException.find(assertThrows(CompletionException.class,
            () -> api.transfer(5, 6, BalanceCurrency.COINS, 5000, false).join()));

        assertEquals(1, seen.size());
        assertEquals(422, error.httpStatus());
        assertTrue(error.error().is(CurrencyError.DAILY_CAP_EXCEEDED));
        assertEquals(1500L, error.error().detailLong("remaining"));
        assertFalse(error.outcomeUnknown());
    }

    @Test
    void anUnparseableErrorBody_keepsTheStatus() {
        CurrencyApiImpl api = api(n -> json(403, "<html>nope</html>"));
        CurrencyException error = CurrencyException.find(assertThrows(CompletionException.class,
            () -> api.cancelTransfer(5, "01M3FHX1ZDRTAB76K01ZYK0PCZ").join()));
        assertEquals("Http403", error.error().code());
    }

    @Test
    void pendingConfirmation_carriesThePendingTransfer() {
        TransferOutcome outcome = api(n -> json(202, PENDING)).transfer(5, 6, BalanceCurrency.COINS, 150_000, false).join();

        assertEquals(TransferOutcome.Status.PENDING_CONFIRMATION, outcome.status());
        assertEquals("01M3FHX1ZDRTAB76K01ZYK0PCZ", outcome.pending().publicId());
        assertTrue(outcome.pending().isOpen());
        // No offset from the API = UTC.
        assertEquals(Instant.parse("2026-09-26T19:09:35.1702872Z"), outcome.pending().expiresAt());
    }

    @Test
    void confirm_postsToThePendingIdAsTheSender() {
        api(n -> json(200, COMPLETED)).confirmTransfer(5, "01M3FHX1ZDRTAB76K01ZYK0PCZ", true).join();

        assertEquals("http://api.test/api/currency/transfers/pending/01M3FHX1ZDRTAB76K01ZYK0PCZ/confirm?bypassLimits=true",
            seen.get(0).url().toString());
        assertEquals("5", seen.get(0).header(CurrencyApiImpl.ACTING_USER_HEADER));
    }

    @Test
    void transactions_readTheLedgerPage() {
        LedgerPage page = api(n -> json(200, """
            {"items":[{"entryId":20,"publicId":"01M3","createdAt":"2026-09-26T19:08:17.775731","currency":"Coins","amount":-1000,
                       "balanceBefore":2000,"balanceAfter":1000,"kind":"Transfer","reasonCode":"PLAYER_TRANSFER","reason":"Player transfer",
                       "initiator":"Player","counterpartyUserId":6,"counterpartyUsername":"bob"}],
             "totalCount":11,"pageNumber":2,"pageSize":10}
            """)).getTransactions(5, BalanceCurrency.EXPERIENCE, 2, 10).join();

        assertEquals("http://api.test/api/currency/users/5/transactions?page=2&pageSize=10&currency=xp", seen.get(0).url().toString());
        assertEquals(2, page.totalPages());
        assertEquals("bob", page.items().get(0).counterpartyUsername());
        assertEquals(-1000, page.items().get(0).amount());
    }

    @Test
    void paymentNotifications_mapThePayment() throws Exception {
        PlayerNotificationDto dto = new ObjectMapper().readValue("""
            {"id":4,"userId":6,"uuid":"u","username":"bob","type":"PaymentReceived","titleChange":null,
             "payment":{"amount":100,"currency":"Coins","fromUserId":5,"fromUsername":"alice","transactionPublicId":"01M3","balanceAfter":350},
             "createdAt":"2026-09-26T19:08:00.8657397Z"}
            """, PlayerNotificationDto.class);

        PlayerNotification notification = UsersMapper.mapPlayerNotification(dto);

        assertEquals(PlayerNotification.TYPE_PAYMENT_RECEIVED, notification.type());
        assertEquals("alice", notification.payment().fromUsername());
        assertEquals(350, notification.payment().balanceAfter());
        assertEquals(BalanceCurrency.COINS, notification.payment().currency());
    }
}
