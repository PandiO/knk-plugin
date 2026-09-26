package net.knightsandkings.knk.core.teleport;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference.SourceType;

/**
 * Resolves the {@code /spawn} destination (docs/specs/teleport/DESIGN.md §3.6): the web API's
 * {@code GameSettings.JoinSpawnReference} when {@code JoinSpawnMode = CustomReference} - a Location,
 * or a Town/District/Structure's own Location - else the main world's spawn.
 * <p>
 * The answer is cached for {@link #DEFAULT_TTL} (5 min) and dropped by {@link #invalidate}
 * ({@code /knk cache refresh}). Fallbacks, so {@code /spawn} always goes somewhere:
 * <ul>
 *   <li>the reference can't be looked up (deleted, no Location, API error) → the coordinates saved
 *       with it on the Game Settings page, else the world spawn;</li>
 *   <li>the Game Settings can't be read → the last spawn resolved from them, else the world spawn,
 *       retried after {@value #FAILURE_RETRY_MILLIS} ms.</li>
 * </ul>
 * Bukkit-free and thread-safe; {@link #resolve} never completes exceptionally.
 */
public class SpawnPointResolver {

    private static final Logger LOGGER = Logger.getLogger(SpawnPointResolver.class.getName());
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    static final long FAILURE_RETRY_MILLIS = 30_000L;
    private static final double MAX_COORDINATE = 30_000_000;

    /** A Location by the id of the thing that has it; empty when there is none. */
    @FunctionalInterface
    public interface LocationLookup {
        CompletableFuture<Optional<KnkLocation>> find(int id);
    }

    private final Supplier<CompletableFuture<KnkGameSettings>> settings;
    private final Map<SourceType, LocationLookup> lookups = new EnumMap<>(SourceType.class);
    private final LongSupplier clock;
    private final long ttlMillis;

    private SpawnPoint cached;
    private long cachedUntil;
    /** The last spawn resolved from settings that were read successfully - the fallback while the API is down. */
    private SpawnPoint lastGood;
    private CompletableFuture<SpawnPoint> inFlight;
    private long generation;

    /**
     * @param settings   reads the Game Settings (a REST call)
     * @param locations  a Location by id
     * @param towns      a Town's Location by town id
     * @param districts  a District's Location by district id
     * @param structures a Structure's Location by structure id
     * @param clock      epoch millis
     * @param ttl        how long a resolved spawn is kept
     */
    public SpawnPointResolver(Supplier<CompletableFuture<KnkGameSettings>> settings, LocationLookup locations,
                              LocationLookup towns, LocationLookup districts, LocationLookup structures,
                              LongSupplier clock, Duration ttl) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        lookups.put(SourceType.LOCATION, Objects.requireNonNull(locations, "locations must not be null"));
        lookups.put(SourceType.TOWN, Objects.requireNonNull(towns, "towns must not be null"));
        lookups.put(SourceType.DISTRICT, Objects.requireNonNull(districts, "districts must not be null"));
        lookups.put(SourceType.STRUCTURE, Objects.requireNonNull(structures, "structures must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.ttlMillis = Objects.requireNonNull(ttl, "ttl must not be null").toMillis();
    }

    /** The current spawn: cached, or read from the API (one read at a time, shared by every caller). */
    public synchronized CompletableFuture<SpawnPoint> resolve() {
        if (cached != null && clock.getAsLong() < cachedUntil) {
            return CompletableFuture.completedFuture(cached);
        }
        if (inFlight != null) {
            return inFlight;
        }
        long started = generation;
        CompletableFuture<SpawnPoint> fetch = fetch().handle((point, ex) -> store(started, point, ex));
        if (!fetch.isDone()) {
            inFlight = fetch;
        }
        return fetch;
    }

    /** Forget the cached spawn; the next {@link #resolve} reads the Game Settings again. */
    public synchronized void invalidate() {
        cached = null;
        inFlight = null;
        generation++;
    }

    private synchronized SpawnPoint store(long started, SpawnPoint point, Throwable failure) {
        boolean current = started == generation;
        if (current) {
            inFlight = null;
        }
        long now = clock.getAsLong();
        if (failure != null || point == null) {
            SpawnPoint fallback = lastGood != null ? lastGood : SpawnPoint.worldSpawn();
            LOGGER.log(Level.WARNING, "[KnK Teleport] Could not read the Game Settings for /spawn; using "
                + (lastGood != null ? "the last known spawn (" + lastGood.label() + ")" : "the main world's spawn"), failure);
            if (current) {
                cached = fallback;
                cachedUntil = now + FAILURE_RETRY_MILLIS;
            }
            return fallback;
        }
        if (current) {
            cached = point;
            cachedUntil = now + ttlMillis;
            lastGood = point;
        }
        return point;
    }

    private CompletableFuture<SpawnPoint> fetch() {
        CompletableFuture<KnkGameSettings> read;
        try {
            read = settings.get();
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
        if (read == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No Game Settings reader"));
        }
        return read.thenCompose(gameSettings -> {
            if (gameSettings == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("The API returned no Game Settings"));
            }
            Optional<KnkSpawnReference> reference = gameSettings.customJoinSpawn();
            return reference.isPresent()
                ? resolveReference(reference.get())
                : CompletableFuture.completedFuture(SpawnPoint.worldSpawn());
        });
    }

    private CompletableFuture<SpawnPoint> resolveReference(KnkSpawnReference reference) {
        return lookUp(reference).handle((found, ex) -> {
            if (ex != null) {
                LOGGER.log(Level.WARNING, "[KnK Teleport] Could not look up the spawn " + reference.label(), ex);
            }
            Optional<KnkLocation> usable = ex == null && found != null
                ? found.filter(SpawnPointResolver::isUsable)
                : Optional.empty();
            if (usable.isPresent()) {
                return new SpawnPoint(usable.get(), reference.label(), SpawnPoint.Source.REFERENCE);
            }
            if (isUsable(reference.snapshot())) {
                LOGGER.warning("[KnK Teleport] The spawn " + reference.label()
                    + " has no usable Location right now; using the coordinates saved with it on the Game Settings page");
                return new SpawnPoint(reference.snapshot(), reference.label(), SpawnPoint.Source.SNAPSHOT);
            }
            LOGGER.warning("[KnK Teleport] The spawn " + reference.label()
                + " has no usable Location; using the main world's spawn");
            return SpawnPoint.worldSpawn();
        });
    }

    private CompletableFuture<Optional<KnkLocation>> lookUp(KnkSpawnReference reference) {
        LocationLookup lookup = reference.sourceType() != null ? lookups.get(reference.sourceType()) : null;
        if (lookup == null || reference.sourceId() <= 0) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        try {
            CompletableFuture<Optional<KnkLocation>> found = lookup.find(reference.sourceId());
            return found != null ? found : CompletableFuture.completedFuture(Optional.empty());
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    /** A Location a player can be put at: a world name and finite coordinates inside the world border limit. */
    static boolean isUsable(KnkLocation location) {
        return location != null
            && location.world() != null && !location.world().isBlank()
            && inRange(location.x()) && inRange(location.y()) && inRange(location.z());
    }

    private static boolean inRange(Double value) {
        return value != null && Double.isFinite(value) && Math.abs(value) <= MAX_COORDINATE;
    }
}
