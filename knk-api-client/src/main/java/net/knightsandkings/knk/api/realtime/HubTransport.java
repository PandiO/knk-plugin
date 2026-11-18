package net.knightsandkings.knk.api.realtime;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * One SignalR connection attempt, as {@link RealtimeClient} needs it. The real one is {@link SignalRHubTransport};
 * tests use a fake. A transport is used for one connection only: after it closed, the client builds a new one.
 */
public interface HubTransport {

    /** Registers a server-to-client method; the handler gets the single argument as raw JSON. Before {@link #start()}. */
    void on(String method, Consumer<String> handler);

    /** Called once when the connection closes; {@code error} is null for a normal stop. Before {@link #start()}. */
    void onClosed(Consumer<Throwable> handler);

    /** Connects (negotiate + handshake); completes when connected, exceptionally when the attempt failed. */
    CompletableFuture<Void> start();

    /** Closes the connection. */
    CompletableFuture<Void> stop();

    /** Fire-and-forget invocation of a hub method; fails when not connected. */
    void send(String method, Object... args);

    /** Builds a fresh transport for each attempt. */
    @FunctionalInterface
    interface Factory {
        HubTransport create();
    }
}
