package net.knightsandkings.knk.api.realtime;

import com.google.gson.JsonElement;
import com.microsoft.signalr.HubConnection;
import com.microsoft.signalr.HubConnectionBuilder;
import com.microsoft.signalr.HttpHubConnectionBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * {@link HubTransport} on the official Java SignalR client ({@code com.microsoft.signalr}, DESIGN §12 Q2). The plugin
 * jar shades it and relocates its RxJava and Gson (okhttp/okio were already relocated). Messages arrive on the
 * client's own threads; {@link RealtimeClient} hops them onto its executor.
 */
public final class SignalRHubTransport implements HubTransport {

    private final HubConnection connection;

    /**
     * @param hubUrl  e.g. {@code http://api:5000/api/hubs/game}
     * @param headers sent on negotiate and on the WebSocket upgrade ({@code X-API-Key}, {@code X-Knk-Server-Id})
     */
    public SignalRHubTransport(String hubUrl, Map<String, String> headers) {
        HttpHubConnectionBuilder builder = HubConnectionBuilder.create(hubUrl);
        Map<String, String> copy = new LinkedHashMap<>(headers);
        if (!copy.isEmpty()) {
            builder.withHeaders(copy);
        }
        this.connection = builder.build();
    }

    /** A factory building a fresh connection with the same URL and headers for every attempt. */
    public static HubTransport.Factory factory(String hubUrl, Map<String, String> headers) {
        Map<String, String> copy = Map.copyOf(headers);
        return () -> new SignalRHubTransport(hubUrl, copy);
    }

    @Override
    public void on(String method, Consumer<String> handler) {
        connection.on(method, (JsonElement argument) -> handler.accept(argument == null ? "null" : argument.toString()),
            JsonElement.class);
    }

    @Override
    public void onClosed(Consumer<Throwable> handler) {
        connection.onClosed(handler::accept);
    }

    @Override
    public CompletableFuture<Void> start() {
        CompletableFuture<Void> started = new CompletableFuture<>();
        try {
            connection.start().subscribe(() -> started.complete(null), started::completeExceptionally);
        } catch (RuntimeException e) {
            started.completeExceptionally(e);
        }
        return started;
    }

    @Override
    public CompletableFuture<Void> stop() {
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        try {
            connection.stop().subscribe(() -> stopped.complete(null), stopped::completeExceptionally);
        } catch (RuntimeException e) {
            stopped.completeExceptionally(e);
        }
        return stopped;
    }

    @Override
    public void send(String method, Object... args) {
        connection.send(method, args);
    }
}
