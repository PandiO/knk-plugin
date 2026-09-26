package net.knightsandkings.knk.paper.teleport;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;

import net.knightsandkings.knk.core.dataaccess.DistrictsDataAccess;
import net.knightsandkings.knk.core.dataaccess.FetchPolicy;
import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.LocationsDataAccess;
import net.knightsandkings.knk.core.dataaccess.StructuresDataAccess;
import net.knightsandkings.knk.core.dataaccess.TownsDataAccess;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.ports.api.GameSettingsQueryApi;
import net.knightsandkings.knk.core.teleport.SpawnPoint;
import net.knightsandkings.knk.core.teleport.SpawnPointResolver;

/**
 * The {@code /spawn} destination as a Bukkit {@link Location} (docs/specs/teleport/DESIGN.md §3.6,
 * Phase 4): {@code GameSettings.JoinSpawnReference} when the Game Settings page sets a custom spawn -
 * a Location, or a Town/District/Structure's Location, looked up through the data-access gateways -
 * else the main world's spawn. Resolution, caching (5 min, dropped by {@code /knk cache refresh}) and
 * the fallbacks live in {@link SpawnPointResolver}; this class adds the gateways and the world lookup.
 * <p>
 * Only {@code /spawn} uses it; the join and respawn listeners still pick their own spot.
 */
public class SpawnDestinationResolver {

    private static final Logger LOGGER = Logger.getLogger(SpawnDestinationResolver.class.getName());
    /** Lookups go to the API first so a moved town spawn shows up after a refresh; the cache covers an outage. */
    private static final FetchPolicy LOOKUP_POLICY = FetchPolicy.API_THEN_CACHE_REFRESH;

    private final SpawnPointResolver points;
    private final Function<String, World> worldByName;
    private final Supplier<World> mainWorld;

    public SpawnDestinationResolver(SpawnPointResolver points, Function<String, World> worldByName, Supplier<World> mainWorld) {
        this.points = Objects.requireNonNull(points, "points must not be null");
        this.worldByName = Objects.requireNonNull(worldByName, "worldByName must not be null");
        this.mainWorld = Objects.requireNonNull(mainWorld, "mainWorld must not be null");
    }

    /** Wires the resolver to the Game Settings API and the Location/Town/District/Structure gateways. */
    public static SpawnDestinationResolver create(GameSettingsQueryApi gameSettings, LocationsDataAccess locations,
                                                  TownsDataAccess towns, DistrictsDataAccess districts,
                                                  StructuresDataAccess structures, Function<String, World> worldByName,
                                                  Supplier<World> mainWorld) {
        Objects.requireNonNull(gameSettings, "gameSettings must not be null");
        Objects.requireNonNull(locations, "locations must not be null");
        Objects.requireNonNull(towns, "towns must not be null");
        Objects.requireNonNull(districts, "districts must not be null");
        Objects.requireNonNull(structures, "structures must not be null");
        SpawnPointResolver.LocationLookup locationById = id -> value(locations.getByIdAsync(id, LOOKUP_POLICY));
        SpawnPointResolver points = new SpawnPointResolver(
            gameSettings::get,
            locationById,
            id -> value(towns.getByIdAsync(id, LOOKUP_POLICY)).thenCompose(town -> town
                .map(t -> ownLocation(t.location() == null ? null : new KnkLocation(t.location().id(), t.location().name(),
                    t.location().x(), t.location().y(), t.location().z(), t.location().yaw(), t.location().pitch(),
                    t.location().world()), t.locationId(), locationById))
                .orElse(CompletableFuture.completedFuture(Optional.empty()))),
            id -> value(districts.getByIdAsync(id, LOOKUP_POLICY)).thenCompose(district -> district
                .map(d -> ownLocation(d.location() == null ? null : new KnkLocation(d.location().id(), d.location().name(),
                    d.location().x(), d.location().y(), d.location().z(), d.location().yaw(), d.location().pitch(),
                    d.location().world()), d.locationId(), locationById))
                .orElse(CompletableFuture.completedFuture(Optional.empty()))),
            id -> value(structures.getByIdAsync(id, LOOKUP_POLICY)).thenCompose(structure -> structure
                .map(s -> ownLocation(null, s.locationId(), locationById))
                .orElse(CompletableFuture.completedFuture(Optional.empty()))),
            System::currentTimeMillis,
            SpawnPointResolver.DEFAULT_TTL);
        return new SpawnDestinationResolver(points, worldByName, mainWorld);
    }

    /** The current spawn; any thread, never completes exceptionally. */
    public CompletableFuture<SpawnPoint> resolve() {
        return points.resolve();
    }

    /** Forget the cached spawn ({@code /knk cache refresh}, or after changing it on the Game Settings page). */
    public void invalidate() {
        points.invalidate();
    }

    /**
     * {@code point} in the running server. Main thread. The main world's spawn when {@code point} is
     * the world spawn or names a world that isn't loaded; null only when no world is loaded at all.
     */
    public Location toLocation(SpawnPoint point) {
        if (point != null && !point.isWorldSpawn()) {
            KnkLocation spot = point.location();
            World world = worldByName.apply(spot.world());
            if (world != null) {
                return new Location(world, spot.x(), spot.y(), spot.z(),
                    spot.yaw() != null ? spot.yaw() : 0f, spot.pitch() != null ? spot.pitch() : 0f);
            }
            LOGGER.warning("[KnK Teleport] The spawn " + point.label() + " is in world '" + spot.world()
                + "', which isn't loaded; using the main world's spawn");
        }
        World main = mainWorld.get();
        return main != null ? main.getSpawnLocation() : null;
    }

    private static <T> CompletableFuture<Optional<T>> value(CompletableFuture<FetchResult<T>> fetch) {
        return fetch.thenApply(result -> result != null ? result.value() : Optional.<T>empty());
    }

    /** A domain's embedded Location when the API included it, else its {@code locationId} looked up. */
    private static CompletableFuture<Optional<KnkLocation>> ownLocation(KnkLocation embedded, Integer locationId,
                                                                        SpawnPointResolver.LocationLookup locations) {
        if (embedded != null && embedded.world() != null) {
            return CompletableFuture.completedFuture(Optional.of(embedded));
        }
        if (locationId == null || locationId <= 0) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return locations.find(locationId);
    }
}
