package net.knightsandkings.knk.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.core.realtime.RealtimeState;
import net.knightsandkings.knk.core.realtime.RealtimeStats;
import net.knightsandkings.knk.core.realtime.ReconnectBackoff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** PandiO/knk-workspace#178: reconnect, connectivity gating, sequence checks and sends of {@link RealtimeClient}. */
class RealtimeClientTest {

    /** A transport the test drives: start succeeds or fails on command, messages and closes are pushed by hand. */
    static final class FakeTransport implements HubTransport {
        final Map<String, Consumer<String>> handlers = new HashMap<>();
        final List<Object[]> sent = new CopyOnWriteArrayList<>();
        final CompletableFuture<Void> started = new CompletableFuture<>();
        Consumer<Throwable> closed;
        volatile boolean stopCalled;
        volatile boolean connected;

        @Override
        public void on(String method, Consumer<String> handler) {
            handlers.put(method, handler);
        }

        @Override
        public void onClosed(Consumer<Throwable> handler) {
            closed = handler;
        }

        @Override
        public CompletableFuture<Void> start() {
            return started;
        }

        @Override
        public CompletableFuture<Void> stop() {
            stopCalled = true;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void send(String method, Object... args) {
            if (!connected) {
                throw new IllegalStateException("not connected");
            }
            Object[] call = new Object[args.length + 1];
            call[0] = method;
            System.arraycopy(args, 0, call, 1, args.length);
            sent.add(call);
        }

        void succeed() {
            connected = true;
            started.complete(null);
        }

        void fail() {
            started.completeExceptionally(new java.io.IOException("connection refused"));
        }

        void message(String method, long seq) {
            handlers.get(method).accept("{\"v\":1,\"seq\":" + seq + ",\"type\":\"" + method
                + "\",\"at\":\"2026-10-26T12:00:00Z\",\"actor\":{\"userId\":null,\"source\":\"system\"},\"payload\":{\"serverId\":\"s1\"}}");
        }

        void drop() {
            connected = false;
            closed.accept(new java.io.IOException("socket closed"));
        }
    }

    private final List<FakeTransport> transports = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<RealtimeEnvelope> envelopes = new CopyOnWriteArrayList<>();
    private final RealtimeStats stats = new RealtimeStats();
    private ScheduledExecutorService executor;
    private RealtimeClient client;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadScheduledExecutor();
        ReconnectBackoff backoff = new ReconnectBackoff(List.of(Duration.ofMillis(20), Duration.ofMillis(40)), 0.0, () -> 0.5);
        client = new RealtimeClient(
            () -> {
                FakeTransport t = new FakeTransport();
                transports.add(t);
                return t;
            },
            () -> Set.of("Welcome"),
            backoff,
            executor,
            new ObjectMapper(),
            stats,
            Clock.systemUTC(),
            new RealtimeClient.Listener() {
                @Override
                public void onConnected(boolean reconnect) {
                    events.add(reconnect ? "reconnected" : "connected");
                }

                @Override
                public void onEnvelope(RealtimeEnvelope envelope) {
                    envelopes.add(envelope);
                }

                @Override
                public void onGap(long expected, long received) {
                    events.add("gap " + expected + ".." + received);
                }

                @Override
                public void onDisconnected(Throwable error) {
                    events.add("disconnected");
                }
            },
            Logger.getLogger("RealtimeClientTest"));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        client.stop();
        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
        fail("Timed out waiting for " + what);
    }

    private FakeTransport transport(int index) {
        await(() -> transports.size() > index, "connect attempt " + (index + 1));
        return transports.get(index);
    }

    @Test
    void connectsAndDeliversMessagesInOrder() {
        client.start();
        FakeTransport t = transport(0);
        assertTrue(t.handlers.containsKey("Welcome"));
        t.succeed();
        await(client::isConnected, "connected");

        t.message("Welcome", 1);
        await(() -> envelopes.size() == 1, "the welcome");

        RealtimeEnvelope welcome = envelopes.get(0);
        assertEquals("Welcome", welcome.type());
        assertEquals(1, welcome.seq());
        assertEquals("s1", welcome.payloadText("serverId"));
        assertEquals(List.of("connected"), events);
        assertEquals(1, stats.connects());
    }

    @Test
    void failedAttemptsRetryWithBackoff() {
        client.start();
        transport(0).fail();
        transport(1).fail();
        FakeTransport third = transport(2);
        assertEquals(RealtimeState.CONNECTING, client.state());
        third.succeed();
        await(() -> events.contains("connected"), "connected on the third attempt");

        assertEquals(3, stats.attempts());
        assertEquals(List.of("disconnected", "disconnected", "connected"), events);
    }

    @Test
    void dropTriggersReconnect_AndTheSecondConnectIsAReconnect() {
        client.start();
        FakeTransport first = transport(0);
        first.succeed();
        await(client::isConnected, "connected");

        first.drop();
        FakeTransport second = transport(1);
        second.succeed();
        await(() -> events.contains("reconnected"), "the reconnect");

        assertEquals(List.of("connected", "disconnected", "reconnected"), events);
        assertEquals(1, stats.disconnects());
    }

    @Test
    void sequenceIsPerConnection_GapsReported_DuplicatesDropped() {
        client.start();
        FakeTransport first = transport(0);
        first.succeed();
        await(client::isConnected, "connected");
        first.message("Welcome", 1);
        first.message("Welcome", 1);
        first.message("Welcome", 4);
        await(() -> envelopes.size() == 2, "two messages");
        assertEquals(List.of("connected", "gap 2..4"), events);

        first.drop();
        FakeTransport second = transport(1);
        second.succeed();
        await(client::isConnected, "reconnected");
        second.message("Welcome", 1);
        await(() -> envelopes.size() == 3, "the welcome on the new connection");

        assertEquals(1, stats.gaps());
        assertFalse(events.contains("gap 0..1"));
    }

    @Test
    void messagesFromAnOldConnectionAreIgnored() {
        client.start();
        FakeTransport first = transport(0);
        first.succeed();
        await(client::isConnected, "connected");
        first.drop();
        transport(1).succeed();
        await(() -> events.contains("reconnected"), "reconnected");

        first.message("Welcome", 1);
        transport(1).message("Welcome", 1);
        await(() -> envelopes.size() == 1, "the new connection's message");
        assertEquals(1, envelopes.size());
    }

    @Test
    void pauseStopsAttempts_ResumeTriesAtOnce() throws InterruptedException {
        client.start();
        client.pause();
        transport(0).fail();
        await(() -> client.state() == RealtimeState.PAUSED, "paused");
        Thread.sleep(100);
        assertEquals(1, transports.size(), "no attempts while the API is down");

        client.resume();
        transport(1).succeed();
        await(client::isConnected, "connected after resume");
    }

    @Test
    void resumeWhileWaitingSkipsTheRestOfTheBackoff() {
        client.start();
        transport(0).fail();
        await(() -> client.state() == RealtimeState.WAITING || transports.size() > 1, "waiting");
        client.resume();
        transport(1).succeed();
        await(client::isConnected, "connected");
    }

    @Test
    void sendOnlyWhileConnected() {
        assertFalse(client.send("PlayerJoined", Map.of("uuid", "u")));
        client.start();
        FakeTransport t = transport(0);
        assertFalse(client.send("PlayerJoined", Map.of("uuid", "u")));
        t.succeed();
        await(client::isConnected, "connected");

        assertTrue(client.send("PlayerJoined", Map.of("uuid", "u")));
        assertEquals("PlayerJoined", t.sent.get(0)[0]);

        t.connected = false; // the socket died before the close callback ran
        assertFalse(client.send("PlayerLeft", Map.of("uuid", "u")));
        assertEquals(1, stats.sendFailures());
    }

    @Test
    void stopClosesAndNeverReconnects() throws InterruptedException {
        client.start();
        FakeTransport t = transport(0);
        t.succeed();
        await(client::isConnected, "connected");

        client.stop();
        await(() -> client.state() == RealtimeState.STOPPED, "stopped");
        assertTrue(t.stopCalled);
        t.drop();
        Thread.sleep(100);
        assertEquals(1, transports.size());
    }

    @Test
    void malformedMessagesAreIgnored() {
        client.start();
        FakeTransport t = transport(0);
        t.succeed();
        await(client::isConnected, "connected");

        t.handlers.get("Welcome").accept("not json");
        t.message("Welcome", 1);
        await(() -> envelopes.size() == 1, "the valid message");
        assertNull(envelopes.get(0).actor().get("userId").textValue());
        assertEquals(new ArrayList<>(List.of("connected")), new ArrayList<>(events));
    }
}
