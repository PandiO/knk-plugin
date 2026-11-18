package net.knightsandkings.knk.paper.navigation;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.navigation.compass.CompassSettings;
import net.knightsandkings.knk.core.ports.api.NavigationCompassSettingsApi;

/**
 * The navigation compass settings (KNG-234) as the game server last read them from the web API. Asked
 * on every click, so it answers from memory: the first call and every call after {@link #REFRESH_MILLIS}
 * start a background refresh that is never awaited (as {@link NavigationDestinations}); until one succeeds,
 * and whenever the API is down, the last good settings (the defaults at first) apply.
 */
public final class CompassSettingsStore {

    private static final Logger LOGGER = Logger.getLogger(CompassSettingsStore.class.getName());
    public static final long REFRESH_MILLIS = 60_000;

    private final NavigationCompassSettingsApi api;
    private final LongSupplier clock;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile CompassSettings current = CompassSettings.defaults();
    private static final long NEVER = Long.MIN_VALUE;
    private volatile long loadedAt = NEVER;

    public CompassSettingsStore(NavigationCompassSettingsApi api, LongSupplier clock) {
        this.api = Objects.requireNonNull(api, "api");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The settings now; starts a background refresh when they are stale. Any thread. */
    public CompassSettings get() {
        if (loadedAt == NEVER || clock.getAsLong() - loadedAt >= REFRESH_MILLIS) {
            refresh();
        }
        return current;
    }

    /** Reads the settings in the background; a failed read keeps the last good ones and is tried again after the interval. */
    public void refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        // Stamped up front so a down API is not hit on every click.
        loadedAt = clock.getAsLong();
        try {
            api.get().whenComplete((settings, error) -> {
                try {
                    if (error != null || settings == null) {
                        LOGGER.log(Level.WARNING, "Could not read the navigation compass settings; keeping the last ones", error);
                    } else {
                        current = settings;
                    }
                } finally {
                    refreshing.set(false);
                }
            });
        } catch (RuntimeException e) {
            refreshing.set(false);
            LOGGER.log(Level.WARNING, "Could not start the navigation compass settings refresh", e);
        }
    }
}
