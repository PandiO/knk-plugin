package net.knightsandkings.knk.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.core.realtime.RealtimeState;
import net.knightsandkings.knk.core.realtime.RealtimeStats;
import net.knightsandkings.knk.core.realtime.ReconnectBackoff;
import net.knightsandkings.knk.core.realtime.SequenceTracker;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The plugin's connection to the API's game hub (PandiO/knk-workspace#178, DESIGN §4.4).
 * <ul>
 *   <li>Connects on {@link #start()}; after a drop (or a failed attempt) waits per {@link ReconnectBackoff} and tries
 *       again, because the Java SignalR client has no automatic reconnect.</li>
 *   <li>Gated by the API connectivity probe: {@link #pause()} while it says DOWN (no attempts),
 *       {@link #resume()} on UP (an attempt right away, backoff reset).</li>
 *   <li>Numbers: a fresh {@link SequenceTracker} per connection; a jump calls {@link Listener#onGap}, a repeat is
 *       dropped.</li>
 *   <li>Every state change, connect and message runs on the one {@code executor} thread, in order; the
 *       {@link Listener} is called there and hops to the main thread itself where it touches Bukkit (P6).</li>
 * </ul>
 * Nothing here blocks a caller: {@link #send} is fire-and-forget and refuses while not connected (P5).
 */
public final class RealtimeClient {

    /** Callbacks, all on the client's executor thread. */
    public interface Listener {
        /** Connected; {@code reconnect} is false only for the first connect since {@link #start()}. */
        void onConnected(boolean reconnect);

        /** A message, after its sequence check. Also called for the message that revealed a gap. */
        void onEnvelope(RealtimeEnvelope envelope);

        /** Messages {@code expected} up to {@code received - 1} were lost: run the full resync. */
        void onGap(long expected, long received);

        /** The connection closed or an attempt failed ({@code error} may be null). */
        default void onDisconnected(Throwable error) {
        }
    }

    private final HubTransport.Factory transports;
    private final Supplier<Set<String>> methods;
    private final ReconnectBackoff backoff;
    private final ScheduledExecutorService executor;
    private final ObjectMapper mapper;
    private final RealtimeStats stats;
    private final Clock clock;
    private final Listener listener;
    private final Logger logger;

    // Only touched on the executor thread.
    private HubTransport current;
    private ScheduledFuture<?> pendingAttempt;
    private boolean everConnected;
    private boolean started;
    private boolean paused;
    private volatile RealtimeState state = RealtimeState.STOPPED;
    private volatile boolean stopped;

    /** With a plain Jackson mapper for the envelopes. */
    public RealtimeClient(HubTransport.Factory transports, Supplier<Set<String>> methods, ReconnectBackoff backoff,
                          ScheduledExecutorService executor, RealtimeStats stats, Clock clock, Listener listener,
                          Logger logger) {
        this(transports, methods, backoff, executor, new ObjectMapper(), stats, clock, listener, logger);
    }

    /**
     * @param methods the hub methods (event types) to listen for; read on every connect
     */
    public RealtimeClient(HubTransport.Factory transports, Supplier<Set<String>> methods, ReconnectBackoff backoff,
                          ScheduledExecutorService executor, ObjectMapper mapper, RealtimeStats stats, Clock clock,
                          Listener listener, Logger logger) {
        this.transports = Objects.requireNonNull(transports, "transports");
        this.methods = Objects.requireNonNull(methods, "methods");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.logger = Objects.requireNonNull(logger, "logger");
        stats.state(RealtimeState.STOPPED, clock.instant());
    }

    public RealtimeState state() {
        return state;
    }

    public boolean isConnected() {
        return state == RealtimeState.CONNECTED;
    }

    /** Starts connecting (once). */
    public void start() {
        executor.execute(() -> {
            if (started || stopped) {
                return;
            }
            started = true;
            connect();
        });
    }

    /** Closes the connection for good (plugin disable). Safe to call more than once. */
    public void stop() {
        stopped = true;
        executor.execute(() -> {
            cancelPending();
            HubTransport transport = current;
            current = null;
            setState(RealtimeState.STOPPED);
            if (transport != null) {
                transport.stop().exceptionally(error -> null);
            }
        });
    }

    /** The API is DOWN: no new attempts. A live connection is left alone (it fails by itself if the API is gone). */
    public void pause() {
        executor.execute(() -> {
            paused = true;
            if (state == RealtimeState.WAITING) {
                cancelPending();
                setState(RealtimeState.PAUSED);
            }
        });
    }

    /** The API is UP again: if disconnected, try now with a fresh backoff. */
    public void resume() {
        executor.execute(() -> {
            paused = false;
            if (!started || stopped) {
                return;
            }
            if (state == RealtimeState.PAUSED || state == RealtimeState.WAITING) {
                cancelPending();
                backoff.reset();
                connect();
            }
        });
    }

    /**
     * Invokes a hub method (client to server), fire-and-forget. Returns false, and sends nothing, while not connected.
     */
    public boolean send(String method, Object... args) {
        HubTransport transport = current;
        if (state != RealtimeState.CONNECTED || transport == null) {
            return false;
        }
        try {
            transport.send(method, args);
            return true;
        } catch (RuntimeException e) {
            stats.sendFailure();
            logger.log(Level.FINE, "Realtime send of " + method + " failed: " + e.getMessage(), e);
            return false;
        }
    }

    // ---- executor thread ----

    private void connect() {
        if (stopped) {
            return;
        }
        if (paused) {
            setState(RealtimeState.PAUSED);
            return;
        }
        pendingAttempt = null;
        setState(RealtimeState.CONNECTING);
        stats.attempt();
        HubTransport transport;
        try {
            transport = transports.create();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Realtime: could not build the hub connection: " + e.getMessage(), e);
            scheduleReconnect();
            return;
        }
        SequenceTracker tracker = new SequenceTracker();
        current = transport;
        for (String method : List.copyOf(methods.get())) {
            transport.on(method, json -> executor.execute(() -> onMessage(transport, tracker, method, json)));
        }
        transport.onClosed(error -> executor.execute(() -> onClosed(transport, error)));
        transport.start().whenComplete((ignored, error) -> executor.execute(() -> onStarted(transport, error)));
    }

    private void onStarted(HubTransport transport, Throwable error) {
        if (transport != current) {
            return; // stopped or replaced meanwhile
        }
        if (stopped) {
            current = null;
            transport.stop().exceptionally(e -> null);
            return;
        }
        if (error != null) {
            current = null;
            Throwable cause = unwrap(error);
            logger.warning("Realtime: connect to the game hub failed (" + describe(cause) + ")");
            listener.onDisconnected(cause);
            scheduleReconnect();
            return;
        }
        boolean reconnect = everConnected;
        everConnected = true;
        backoff.reset();
        setState(RealtimeState.CONNECTED);
        logger.info("Realtime: connected to the game hub" + (reconnect ? " (reconnect)" : ""));
        try {
            listener.onConnected(reconnect);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Realtime: on-connect handling failed", e);
        }
    }

    private void onClosed(HubTransport transport, Throwable error) {
        if (transport != current) {
            return;
        }
        current = null;
        if (stopped) {
            setState(RealtimeState.STOPPED);
            return;
        }
        logger.warning("Realtime: connection to the game hub closed (" + (error == null ? "closed by the server" : describe(error)) + ")");
        listener.onDisconnected(error);
        scheduleReconnect();
    }

    private void onMessage(HubTransport transport, SequenceTracker tracker, String method, String json) {
        if (transport != current || stopped) {
            return;
        }
        RealtimeEnvelope envelope;
        try {
            envelope = RealtimeEnvelope.parse(json, mapper);
        } catch (IllegalArgumentException e) {
            logger.warning("Realtime: ignored a malformed " + method + " message (" + e.getMessage() + ")");
            return;
        }
        long expected = tracker.last() + 1;
        SequenceTracker.Outcome outcome = tracker.observe(envelope.seq());
        if (outcome == SequenceTracker.Outcome.DUPLICATE) {
            return;
        }
        stats.message(envelope.type() == null ? method : envelope.type(), envelope.seq());
        if (outcome == SequenceTracker.Outcome.GAP) {
            stats.gap();
            logger.warning("Realtime: messages " + expected + "-" + (envelope.seq() - 1) + " were lost; resyncing");
            try {
                listener.onGap(expected, envelope.seq());
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Realtime: gap handling failed", e);
            }
        }
        try {
            listener.onEnvelope(envelope);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Realtime: handling " + method + " failed", e);
        }
    }

    private void scheduleReconnect() {
        if (stopped) {
            setState(RealtimeState.STOPPED);
            return;
        }
        if (paused) {
            setState(RealtimeState.PAUSED);
            return;
        }
        Duration delay = backoff.nextDelay();
        setState(RealtimeState.WAITING);
        logger.fine("Realtime: next attempt in " + delay.toMillis() + " ms");
        pendingAttempt = executor.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void cancelPending() {
        if (pendingAttempt != null) {
            pendingAttempt.cancel(false);
            pendingAttempt = null;
        }
    }

    private void setState(RealtimeState newState) {
        state = newState;
        stats.state(newState, clock.instant());
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
