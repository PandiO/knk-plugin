package net.knightsandkings.knk.paper.realtime;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.realtime.HubTransport;
import net.knightsandkings.knk.api.realtime.RealtimeClient;
import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import net.knightsandkings.knk.api.realtime.SignalRHubTransport;
import net.knightsandkings.knk.core.connectivity.ApiConnectivityState;
import net.knightsandkings.knk.core.realtime.RealtimeState;
import net.knightsandkings.knk.core.realtime.RealtimeStats;
import net.knightsandkings.knk.core.realtime.ReconnectBackoff;
import net.knightsandkings.knk.core.realtime.ResyncHooks;
import net.knightsandkings.knk.paper.events.ApiConnectivityChangedEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * The plugin side of the realtime channel (PandiO/knk-workspace#178, DESIGN §4.4–§5), off unless
 * {@code realtime.enabled}. With it off nothing here runs and the plugin behaves as before (polls, TTLs, task codes).
 * <ul>
 *   <li>{@link RealtimeClient} to {@code /api/hubs/game} with the plugin key and {@code X-Knk-Server-Id}, reconnecting
 *       with backoff; paused while the API connectivity probe says DOWN, resumed on UP.</li>
 *   <li>On every (re)connect, on the main thread: the online roster ({@code ReportRoster}), then the
 *       {@link ResyncHooks} (existing refresh paths). The same resync after a sequence gap.</li>
 *   <li>{@code PlayerJoined} / {@code PlayerLeft} while connected (the REST presence call stays as it is).</li>
 *   <li>Incoming events go through the {@link RealtimeDispatcher}; S1 handles only {@code Welcome}.</li>
 * </ul>
 */
public final class RealtimeService implements Listener {

    private final Plugin plugin;
    private final RealtimeSettings settings;
    private final String apiBaseUrl;
    private final AuthProvider authProvider;
    private final ResyncHooks resyncHooks;
    private final RealtimeStats stats;
    private final RealtimeDispatcher dispatcher;
    private final Function<UUID, Integer> userIds;
    private final Executor mainThread;
    private final Logger logger;

    private volatile ScheduledExecutorService executor;
    private volatile RealtimeClient client;
    private volatile String acceptedServerId;

    /**
     * @param userIds the cached user id of an online player, or null (informational for the API)
     */
    public RealtimeService(Plugin plugin, RealtimeSettings settings, String apiBaseUrl, AuthProvider authProvider,
                           ResyncHooks resyncHooks, RealtimeStats stats, Function<UUID, Integer> userIds) {
        this.plugin = plugin;
        this.settings = settings;
        this.apiBaseUrl = apiBaseUrl;
        this.authProvider = authProvider;
        this.resyncHooks = resyncHooks;
        this.stats = stats;
        this.userIds = userIds;
        this.logger = plugin.getLogger();
        this.mainThread = runnable -> {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, runnable);
            }
        };
        this.dispatcher = new RealtimeDispatcher(logger);
        dispatcher.register("Welcome", 1, Runnable::run, this::onWelcome);
    }

    public RealtimeDispatcher dispatcher() {
        return dispatcher;
    }

    public RealtimeStats stats() {
        return stats;
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    /** Connects when {@code realtime.enabled}; otherwise logs that the channel is off. Main thread. */
    public void start() {
        if (!settings.enabled()) {
            stats.state(RealtimeState.DISABLED, Instant.now());
            logger.info("Realtime channel disabled (realtime.enabled: false); polls and TTLs as before");
            return;
        }
        String hubUrl = settings.resolveHubUrl(apiBaseUrl);
        Map<String, String> headers = new LinkedHashMap<>();
        String headerName = authProvider == null ? null : authProvider.getAuthHeaderName();
        String headerValue = authProvider == null ? null : authProvider.getAuthHeader();
        if (headerName != null && headerValue != null && !headerValue.isBlank()) {
            headers.put(headerName, headerValue);
        }
        headers.put("X-Knk-Server-Id", settings.serverId());
        HubTransport.Factory transports = SignalRHubTransport.factory(hubUrl, headers);

        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "KnK-Realtime");
            thread.setDaemon(true);
            return thread;
        });
        ReconnectBackoff backoff = new ReconnectBackoff(settings.backoff(), settings.jitter(), Math::random);
        client = new RealtimeClient(transports, dispatcher::types, backoff, executor, stats,
            Clock.systemUTC(), new ClientListener(), logger);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        logger.info("Realtime channel: connecting to " + hubUrl + " as server '" + settings.serverId() + "' (backoff "
            + settings.backoff().stream().map(d -> d.toMillis() / 1000.0 + "s").toList() + ")");
        client.start();
    }

    /** Closes the connection (plugin disable). */
    public void stop() {
        HandlerList.unregisterAll(this);
        RealtimeClient c = client;
        client = null;
        if (c != null) {
            c.stop();
        }
        ScheduledExecutorService e = executor;
        executor = null;
        if (e != null) {
            e.shutdown();
            try {
                e.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** One line for {@code /knk health}. */
    public String statusLine() {
        RealtimeState state = stats.state();
        if (state == RealtimeState.DISABLED) {
            return "realtime: off (realtime.enabled: false)";
        }
        StringBuilder line = new StringBuilder("realtime: ").append(state.name().toLowerCase(java.util.Locale.ROOT))
            .append(" as '").append(settings.serverId()).append("'");
        Instant since = stats.connectedSince();
        if (since != null) {
            line.append(" for ").append(Duration.between(since, Instant.now()).toSeconds()).append("s");
        }
        line.append(", connects ").append(stats.connects())
            .append(", messages ").append(stats.messagesTotal())
            .append(", resyncs ").append(stats.resyncs())
            .append(", gaps ").append(stats.gaps());
        return line.toString();
    }

    // ---- Bukkit events (main thread) ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        RealtimeClient c = client;
        if (c != null && c.isConnected()) {
            c.send("PlayerJoined", RosterPayloads.player(event.getPlayer().getUniqueId(), event.getPlayer().getName(),
                event.getPlayer().getWorld().getName(), userIds.apply(event.getPlayer().getUniqueId())));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        RealtimeClient c = client;
        if (c != null && c.isConnected()) {
            c.send("PlayerLeft", RosterPayloads.left(event.getPlayer().getUniqueId(), event.getPlayer().getWorld().getName()));
        }
    }

    /** DESIGN §4.4: no attempts while the API is DOWN; one right away when it is UP again. */
    @EventHandler
    public void onConnectivity(ApiConnectivityChangedEvent event) {
        RealtimeClient c = client;
        if (c == null) {
            return;
        }
        if (event.getNewState() == ApiConnectivityState.DOWN) {
            c.pause();
        } else if (event.getNewState() == ApiConnectivityState.UP) {
            c.resume();
        }
    }

    // ---- main thread ----

    private void sendRoster() {
        RealtimeClient c = client;
        if (c == null) {
            return;
        }
        List<Map<String, Object>> roster = Bukkit.getOnlinePlayers().stream()
            .map(p -> RosterPayloads.player(p.getUniqueId(), p.getName(), p.getWorld().getName(), userIds.apply(p.getUniqueId())))
            .toList();
        if (!c.send("ReportRoster", roster)) {
            logger.warning("Realtime: could not send the roster (" + roster.size() + " player(s)); the next connect sends it again");
        }
    }

    private void resync(String why) {
        stats.resync();
        List<String> failed = resyncHooks.runAll();
        logger.info("Realtime resync after " + why + ": " + resyncHooks.names().size() + " step(s)"
            + (failed.isEmpty() ? "" : ", failed: " + String.join(", ", failed)));
    }

    private void onWelcome(RealtimeEnvelope envelope) {
        String serverId = envelope.payloadText("serverId");
        acceptedServerId = serverId;
        if (serverId != null && !serverId.equals(settings.serverId())) {
            logger.warning("Realtime: the API filed this server as '" + serverId + "', config says '" + settings.serverId() + "'");
        }
    }

    /** The server id the API confirmed in its last {@code Welcome}, or null. */
    public String acceptedServerId() {
        return acceptedServerId;
    }

    private final class ClientListener implements RealtimeClient.Listener {
        @Override
        public void onConnected(boolean reconnect) {
            mainThread.execute(() -> {
                sendRoster();
                resync(reconnect ? "reconnect" : "connect");
            });
        }

        @Override
        public void onEnvelope(RealtimeEnvelope envelope) {
            dispatcher.dispatch(envelope);
        }

        @Override
        public void onGap(long expected, long received) {
            mainThread.execute(() -> resync("lost messages " + expected + "-" + (received - 1)));
        }
    }

    /** Hub argument shapes for presence (camelCase, as the API's DTOs). */
    static final class RosterPayloads {
        private RosterPayloads() {
        }

        static Map<String, Object> player(UUID uuid, String name, String world, Integer userId) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("uuid", uuid.toString());
            map.put("name", name);
            map.put("world", world);
            if (userId != null) {
                map.put("userId", userId);
            }
            return map;
        }

        static Map<String, Object> left(UUID uuid, String world) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("uuid", uuid.toString());
            map.put("world", world);
            return map;
        }
    }
}
