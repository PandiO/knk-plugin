package net.knightsandkings.knk.paper.realtime;

import net.knightsandkings.knk.api.realtime.ConfigEvents;
import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import net.knightsandkings.knk.core.realtime.Coalescer;
import net.knightsandkings.knk.core.realtime.EntityChangeRouter;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * The game server's handlers for the S3 events (PandiO/knk-workspace#182, DESIGN §6.2–§6.3). Each one calls a refresh
 * path the plugin already has (P3): the domain access sync, {@code GameSettingsManager.refreshNow()}, the siege config
 * refresh ({@code /siege admin reload}), {@code LootboxRuntime.refresh()}, the teleport and navigation cache hooks of
 * {@code /knk cache refresh}, the road network refresh, the telemetry config poll; {@code EntityChanged} goes to the
 * caches of that type ({@link EntityChangeRouter}) and {@code GateChanged} to {@link GateRealtimeSync}. Bursts are merged
 * per section by the {@link Coalescer} (its scheduler runs the refresh on the main thread). A section whose feature is
 * off (null target) ignores its event. The periodic re-syncs stay as the safety net, slower while connected.
 */
public final class ConfigRealtimeSync {

    /**
     * The refresh paths, each null when that feature isn't running.
     *
     * @param roads re-reads one world's road tiles, or every world and the profiles for null
     */
    public record Targets(Runnable domainAccess, Runnable gameSettings, Runnable siegeConfig, Runnable lootboxConfig,
                          Runnable teleportDestinations, Runnable navigationSettings, Consumer<String> roads,
                          Runnable telemetryConfig) {
    }

    private final Coalescer coalescer;
    private final Targets targets;
    private final EntityChangeRouter entities;
    private final GateRealtimeSync gates;
    private final Logger logger;

    /** @param gates null when the gate system isn't running */
    public ConfigRealtimeSync(Coalescer coalescer, Targets targets, EntityChangeRouter entities, GateRealtimeSync gates,
                              Logger logger) {
        this.coalescer = Objects.requireNonNull(coalescer, "coalescer");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.gates = gates;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Registers every S3 handler (before the client starts: it listens for the registered types). */
    public void register(RealtimeDispatcher dispatcher) {
        Executor here = Runnable::run;
        dispatcher.register(ConfigEvents.ENTITY_CHANGED, 1, here, this::onEntityChanged);
        dispatcher.register(ConfigEvents.GATE_CHANGED, 1, here, this::onGateChanged);
        dispatcher.register(ConfigEvents.DOMAIN_ACCESS_CHANGED, 1, here, envelope -> section("domain access", targets.domainAccess()));
        dispatcher.register(ConfigEvents.GAME_SETTINGS_CHANGED, 1, here, envelope -> section("game settings", targets.gameSettings()));
        dispatcher.register(ConfigEvents.SIEGE_CONFIG_CHANGED, 1, here, envelope -> section("siege config", targets.siegeConfig()));
        dispatcher.register(ConfigEvents.LOOTBOX_CONFIG_CHANGED, 1, here, envelope -> section("lootbox config", targets.lootboxConfig()));
        dispatcher.register(ConfigEvents.TELEPORT_DESTINATIONS_CHANGED, 1, here,
            envelope -> section("teleport destinations", targets.teleportDestinations()));
        dispatcher.register(ConfigEvents.NAVIGATION_SETTINGS_CHANGED, 1, here,
            envelope -> section("navigation settings", targets.navigationSettings()));
        dispatcher.register(ConfigEvents.ROAD_NETWORK_CHANGED, 1, here, this::onRoadNetworkChanged);
        dispatcher.register(ConfigEvents.TELEMETRY_CONFIG_CHANGED, 1, here, envelope -> section("telemetry config", targets.telemetryConfig()));
    }

    void onEntityChanged(RealtimeEnvelope envelope) {
        ConfigEvents.EntityChanged event = ConfigEvents.EntityChanged.parse(envelope);
        if (event == null) {
            logger.fine("Realtime: ignored an unreadable EntityChanged (seq " + envelope.seq() + ")");
            return;
        }
        if (!entities.route(event.type(), event.id(), event.key(), event.op())) {
            logger.fine("Realtime: EntityChanged for " + event.type() + " - not cached on this server");
        }
    }

    void onGateChanged(RealtimeEnvelope envelope) {
        ConfigEvents.GateChanged event = ConfigEvents.GateChanged.parse(envelope);
        if (event == null || gates == null) {
            return;
        }
        gates.onGateChanged(event);
    }

    void onRoadNetworkChanged(RealtimeEnvelope envelope) {
        Consumer<String> roads = targets.roads();
        if (roads == null) {
            return;
        }
        String world = ConfigEvents.RoadNetworkChanged.parse(envelope).world();
        coalescer.request("roads:" + (world == null ? "*" : world), () -> roads.accept(world));
    }

    private void section(String name, Runnable refresh) {
        if (refresh != null) {
            coalescer.request(name, refresh);
        }
    }
}
