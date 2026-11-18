package net.knightsandkings.knk.paper.navigation.model;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.dataaccess.DomainCatalogDataAccess;
import net.knightsandkings.knk.core.dataaccess.LocationsDataAccess;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.navigation.DomainLocationResolver;
import net.knightsandkings.knk.core.navigation.model.DomainPlace;
import net.knightsandkings.knk.core.navigation.model.ModelFreshness;
import net.knightsandkings.knk.core.navigation.model.NavigationModel;
import net.knightsandkings.knk.core.navigation.model.NavigationModelHolder;
import net.knightsandkings.knk.core.navigation.model.NavigationModelRefresher;
import net.knightsandkings.knk.core.navigation.model.NavigationModelSnapshot;
import net.knightsandkings.knk.core.navigation.model.PagedFetch;
import net.knightsandkings.knk.core.ports.api.NavigationCompassSettingsApi;

/**
 * The game server's navigation model (destination engine phase 2, DESIGN § 7, PandiO/knk-workspace#103): the
 * snapshot at {@code <data folder>/offline/navigation-model.json} loaded at start-up, a background refresh every
 * {@link #REFRESH_SECONDS} seconds, the paced per-domain place fallback once a second, and the snapshot written on
 * the runtime's own thread when the content changed.
 *
 * <p><b>Nothing reads the model yet</b>: {@code /navigate}, tab completion, the compass and {@code NavigationService}
 * keep using {@code NavigationDestinations} and {@code CompassSettingsStore} until phase 3 (PandiO/knk-workspace#69)
 * switches them over behind {@code navigation.destination-engine}. Bukkit-free apart from where it is wired.
 */
public final class NavigationModelRuntime {

    private static final Logger LOGGER = Logger.getLogger(NavigationModelRuntime.class.getName());

    /** As {@code NavigationDestinations.REFRESH_MILLIS}. */
    public static final long REFRESH_SECONDS = 60;
    /** One fallback tick a second, {@link NavigationModelRefresher#DEFAULT_LOOKUPS_PER_TICK} lookups each. */
    public static final long BACKFILL_SECONDS = 1;

    private final NavigationModelHolder holder = new NavigationModelHolder();
    private final NavigationModelRefresher refresher;
    private final NavigationModelSnapshot snapshot;
    private final NavigationModelConfig config;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private volatile boolean started;

    /**
     * @param dataFolder the plugin's data folder; the snapshot is {@link NavigationModelSnapshot#RELATIVE_PATH} in it
     * @param executor   runs the refresh, the fallback and the snapshot writes (one thread is enough)
     */
    public NavigationModelRuntime(NavigationModelRefresher.Source source, NavigationModelRefresher.PlaceLookup places,
                                  Path dataFolder, NavigationModelConfig config, Clock clock,
                                  ScheduledExecutorService executor) {
        this.config = config == null ? NavigationModelConfig.defaults() : config;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.snapshot = new NavigationModelSnapshot(
            Objects.requireNonNull(dataFolder, "dataFolder").resolve(NavigationModelSnapshot.RELATIVE_PATH));
        this.refresher = new NavigationModelRefresher(source, places, holder, snapshot, executor, clock,
            NavigationModelRefresher.DEFAULT_LOOKUPS_PER_TICK);
    }

    /** The runtime on its own daemon thread {@code knk-navigation-model}. */
    public static NavigationModelRuntime create(NavigationModelRefresher.Source source,
                                                NavigationModelRefresher.PlaceLookup places, Path dataFolder,
                                                NavigationModelConfig config) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "knk-navigation-model");
            t.setDaemon(true);
            return t;
        });
        return new NavigationModelRuntime(source, places, dataFolder, config, Clock.systemUTC(), executor);
    }

    // ==================== lifecycle ====================

    /** Loads the snapshot (logged with its age), then starts the refresh and the fallback. Never throws for a bad file. */
    public NavigationModelSnapshot.Loaded start() {
        NavigationModelSnapshot.Loaded loaded = refresher.loadSnapshot();
        if (loaded.loaded()) {
            NavigationModel model = loaded.model();
            LOGGER.info("[Navigation] Navigation model snapshot loaded from " + snapshot.file() + ": "
                + model.domains().size() + " domain(s), " + model.locations().size() + " Location(s), as of "
                + model.asOf() + ", " + freshness().describe());
        } else if (loaded.status() == NavigationModelSnapshot.Loaded.Status.MISSING) {
            LOGGER.info("[Navigation] No navigation model snapshot yet (" + snapshot.file()
                + "); it is written after the first good refresh");
        }
        started = true;
        executor.scheduleWithFixedDelay(this::refreshQuietly, 0, REFRESH_SECONDS, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(this::backfillQuietly, BACKFILL_SECONDS, BACKFILL_SECONDS, TimeUnit.SECONDS);
        return loaded;
    }

    /** Stops the timers; a write already started finishes. */
    public void stop() {
        started = false;
        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@code /knk cache refresh}: a refresh now, looking places up again (only matters with an older API). */
    public CompletableFuture<Boolean> refreshNow() {
        return refresher.refresh(true);
    }

    private void refreshQuietly() {
        try {
            refresher.refresh();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "[Navigation] Navigation model refresh failed", e);
        }
    }

    private void backfillQuietly() {
        try {
            refresher.backfillTick();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "[Navigation] Navigation model place lookup failed", e);
        }
    }

    // ==================== reads (phase 3 on) ====================

    public NavigationModelHolder holder() {
        return holder;
    }

    public NavigationModelRefresher refresher() {
        return refresher;
    }

    public NavigationModelSnapshot snapshot() {
        return snapshot;
    }

    public NavigationModelConfig config() {
        return config;
    }

    public boolean started() {
        return started;
    }

    /** How current the model is now, with {@code navigation.destinations.stale-after-days}. */
    public ModelFreshness freshness() {
        return holder.freshness(clock.instant(), config.staleAfter());
    }

    public Duration staleAfter() {
        return config.staleAfter();
    }

    // ==================== adapters ====================

    /** The API through the data accesses: the domain search, the Location search and the compass settings. */
    public static NavigationModelRefresher.Source source(DomainCatalogDataAccess domains, LocationsDataAccess locations,
                                                         NavigationCompassSettingsApi compass) {
        return source(domains, locations, compass, null);
    }

    /**
     * As above plus the landmarks of {@code api/landmarks} (off-road phase 5.1, PandiO/knk-workspace#708), only when
     * {@code landmarks} is given ({@code navigation.landmarks} on); null keeps the section empty.
     */
    public static NavigationModelRefresher.Source source(DomainCatalogDataAccess domains, LocationsDataAccess locations,
                                                         NavigationCompassSettingsApi compass,
                                                         net.knightsandkings.knk.core.ports.api.LandmarksApi landmarks) {
        NavigationModelRefresher.Source base = baseSource(domains, locations, compass);
        if (landmarks == null) {
            return base;
        }
        return new NavigationModelRefresher.Source() {
            @Override
            public CompletableFuture<java.util.List<net.knightsandkings.knk.core.domain.domains.KnkDomainSummary>> domains() {
                return base.domains();
            }

            @Override
            public CompletableFuture<java.util.List<KnkLocation>> locations() {
                return base.locations();
            }

            @Override
            public CompletableFuture<net.knightsandkings.knk.core.navigation.compass.CompassSettings> compass() {
                return base.compass();
            }

            @Override
            public CompletableFuture<java.util.List<net.knightsandkings.knk.core.navigation.model.Landmark>> landmarks() {
                return landmarks.list(null).thenApply(list -> list.stream()
                    .map(net.knightsandkings.knk.core.domain.landmarks.KnkLandmark::toModel)
                    .filter(Objects::nonNull)
                    .toList());
            }
        };
    }

    private static NavigationModelRefresher.Source baseSource(DomainCatalogDataAccess domains, LocationsDataAccess locations,
                                                              NavigationCompassSettingsApi compass) {
        Objects.requireNonNull(domains, "domains");
        Objects.requireNonNull(locations, "locations");
        Objects.requireNonNull(compass, "compass");
        return new NavigationModelRefresher.Source() {
            @Override
            public CompletableFuture<java.util.List<net.knightsandkings.knk.core.domain.domains.KnkDomainSummary>> domains() {
                return PagedFetch.allPages(domains::searchAsync);
            }

            @Override
            public CompletableFuture<java.util.List<KnkLocation>> locations() {
                return PagedFetch.allPages(locations::searchAsync);
            }

            @Override
            public CompletableFuture<net.knightsandkings.knk.core.navigation.compass.CompassSettings> compass() {
                return compass.get();
            }
        };
    }

    /** The paced fallback through {@link DomainLocationResolver} (Towns, Districts and Structures, as today's locate). */
    public static NavigationModelRefresher.PlaceLookup placeLookup(DomainLocationResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        return new NavigationModelRefresher.PlaceLookup() {
            @Override
            public boolean supports(String domainType) {
                if (domainType == null) {
                    return false;
                }
                return switch (domainType.toLowerCase(Locale.ROOT)) {
                    case "town", "district", "structure" -> true;
                    default -> false;
                };
            }

            @Override
            public CompletableFuture<Optional<KnkLocation>> lookup(DomainPlace domain) {
                return resolver.byType(domain.domainType(), domain.id())
                    .thenApply(place -> place.flatMap(DomainLocationResolver.DomainPlace::location));
            }
        };
    }
}
