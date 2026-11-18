package net.knightsandkings.knk.paper.menu;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Alpha cross-play finding 2026-10-24: tells whether a player joined through Geyser + Floodgate
 * (a Bedrock client), via {@code FloodgateApi.getInstance().isFloodgatePlayer(uuid)}.
 * <p>
 * Floodgate is an optional soft dependency, so its API is reached by reflection only: without
 * Floodgate (or with an API this cannot call) every player counts as a Java player and the menu
 * engine behaves exactly as before.
 */
public final class FloodgatePlayers implements Predicate<UUID> {

    static final String PLUGIN_NAME = "floodgate";
    static final String API_CLASS = "org.geysermc.floodgate.api.FloodgateApi";

    private static final Logger LOGGER = Logger.getLogger(FloodgatePlayers.class.getName());

    private final Object api;
    private final Method isFloodgatePlayer;
    private volatile boolean failed;

    private FloodgatePlayers(Object api, Method isFloodgatePlayer) {
        this.api = api;
        this.isFloodgatePlayer = isFloodgatePlayer;
    }

    /** The detector for an enabled Floodgate plugin, or {@code uuid -> false} without one. */
    public static Predicate<UUID> detect(Plugin floodgate, Logger logger) {
        if (floodgate == null || !floodgate.isEnabled()) {
            return uuid -> false;
        }
        try {
            Class<?> apiClass = Class.forName(API_CLASS, true, floodgate.getClass().getClassLoader());
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) {
                logger.warning("Floodgate is enabled but FloodgateApi.getInstance() returned null; Bedrock menu transitions are off");
                return uuid -> false;
            }
            Method method = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            logger.info("Floodgate detected: Bedrock players get delayed menu-to-menu transitions");
            return new FloodgatePlayers(api, method);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            logger.log(Level.WARNING, "Floodgate is enabled but its API could not be reached; Bedrock menu transitions are off", e);
            return uuid -> false;
        }
    }

    @Override
    public boolean test(UUID uuid) {
        if (uuid == null || failed) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(isFloodgatePlayer.invoke(api, uuid));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Logged once; from then on everyone is treated as a Java player.
            failed = true;
            LOGGER.log(Level.WARNING, "FloodgateApi.isFloodgatePlayer failed; Bedrock menu transitions are off", e);
            return false;
        }
    }
}
