package net.knightsandkings.knk.paper.hub;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.World;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkHubRules;
import net.knightsandkings.knk.core.domain.settings.KnkHubSettings;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;

/**
 * The selected hub as the server sees it (hub DESIGN §16 Phase 5): where joining players are placed, whether that is
 * usable, and whether a spot is inside the hub's region. Reads the Game Settings in force, which come from the API or,
 * while it is down, from the KNG-58 offline store (decision 7), so the hub holds through an outage and a restart.
 * Any thread for {@link #admissionSpawn}/{@link #unusable}; {@link #inHub} queries WorldGuard.
 */
public class HubService {

    private final Supplier<KnkGameSettings> settings;
    private final Function<String, World> worlds;
    private final BiPredicate<World, String> regionExists;
    private final Function<Location, Set<String>> regionsAt;
    private Function<KnkSpawnReference, Optional<KnkLocation>> resolvedSpawn = reference -> Optional.empty();
    /** Players placed at the hub spawn while connecting, until their join handler has seen it. */
    private final Set<UUID> admitted = ConcurrentHashMap.newKeySet();

    /**
     * @param settings     the Game Settings in force; null until the API or the offline store gave them
     * @param worlds       a loaded world by name, or null
     * @param regionExists whether a world has a WorldGuard region of that id
     * @param regionsAt    the WorldGuard region ids at a spot
     */
    public HubService(Supplier<KnkGameSettings> settings, Function<String, World> worlds,
                      BiPredicate<World, String> regionExists, Function<Location, Set<String>> regionsAt) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.worlds = Objects.requireNonNull(worlds, "worlds");
        this.regionExists = Objects.requireNonNull(regionExists, "regionExists");
        this.regionsAt = Objects.requireNonNull(regionsAt, "regionsAt");
    }

    /** Where the hub spawn reference points now (the referenced Location or domain), else its saved snapshot is used. */
    public void setResolvedSpawn(Function<KnkSpawnReference, Optional<KnkLocation>> resolvedSpawn) {
        this.resolvedSpawn = resolvedSpawn != null ? resolvedSpawn : reference -> Optional.empty();
    }

    /** The hub spawn's coordinates: as resolved now when that is in the hub's world, else the saved snapshot. */
    private KnkLocation spawnSpot(KnkHubSettings hub) {
        KnkLocation snapshot = hub.spawn().snapshot();
        KnkLocation current = resolvedSpawn.apply(hub.spawn()).orElse(null);
        String hubWorld = hub.worldName() != null && !hub.worldName().isBlank() ? hub.worldName()
            : snapshot != null ? snapshot.world() : null;
        if (current != null && current.world() != null && current.x() != null && current.y() != null && current.z() != null
            && (hubWorld == null || hubWorld.equalsIgnoreCase(current.world()))) {
            return current;
        }
        return snapshot;
    }

    /** The hub settings in force; {@link KnkHubSettings#none()} without Game Settings or without a hub. */
    public KnkHubSettings hub() {
        KnkGameSettings current = settings.get();
        return current != null ? current.hub() : KnkHubSettings.none();
    }

    /** Whether a hub is selected: joining players then go there, and its rules apply. */
    public boolean configured() {
        return hub().configured();
    }

    public KnkHubRules rules() {
        return hub().rules();
    }

    /**
     * Why the selected hub cannot take players (hub DESIGN §3, §13: refuse admission rather than admit into unprotected
     * gameplay), or empty when it can, or when no hub is selected.
     */
    public Optional<String> unusable() {
        KnkHubSettings hub = hub();
        if (!hub.configured()) {
            return Optional.empty();
        }
        KnkLocation spot = spawnSpot(hub);
        if (spot == null || spot.world() == null || spot.x() == null || spot.y() == null || spot.z() == null) {
            return Optional.of("the hub spawn has no coordinates");
        }
        String worldName = hub.worldName() != null && !hub.worldName().isBlank() ? hub.worldName() : spot.world();
        if (!worldName.equalsIgnoreCase(spot.world())) {
            return Optional.of("the hub spawn is in world '" + spot.world() + "', not the hub's world '" + worldName + "'");
        }
        World world = worlds.apply(worldName);
        if (world == null) {
            return Optional.of("the hub world '" + worldName + "' is not loaded");
        }
        if (hub.wgRegionId() == null || hub.wgRegionId().isBlank() || !regionExists.test(world, hub.wgRegionId())) {
            return Optional.of("the hub region '" + hub.wgRegionId() + "' does not exist in world '" + worldName + "'");
        }
        if (!inHub(new Location(world, spot.x(), spot.y(), spot.z()))) {
            return Optional.of("the hub spawn is outside the hub region '" + hub.wgRegionId() + "'");
        }
        return Optional.empty();
    }

    /** Where a joining player is placed: the hub spawn, when a hub is selected and usable. */
    public Optional<Location> admissionSpawn() {
        if (!configured() || unusable().isPresent()) {
            return Optional.empty();
        }
        KnkLocation spot = spawnSpot(hub());
        World world = worlds.apply(spot.world());
        if (world == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(world, spot.x(), spot.y(), spot.z(),
            spot.yaw() != null ? spot.yaw() : 0f, spot.pitch() != null ? spot.pitch() : 0f));
    }

    /** Whether {@code location} is inside the selected hub's region (its world, then WorldGuard). */
    public boolean inHub(Location location) {
        KnkHubSettings hub = hub();
        if (!hub.configured() || location == null || location.getWorld() == null || hub.wgRegionId() == null) {
            return false;
        }
        String hubWorld = hub.worldName() != null ? hub.worldName() : hub.spawn().snapshot() != null ? hub.spawn().snapshot().world() : null;
        if (hubWorld == null || !hubWorld.equalsIgnoreCase(location.getWorld().getName())) {
            return false;
        }
        String region = hub.wgRegionId().toLowerCase(Locale.ROOT);
        return regionsAt.apply(location).stream().anyMatch(id -> id.toLowerCase(Locale.ROOT).equals(region));
    }

    /**
     * Whether {@code location}'s column is in the hub: the spot at the hub spawn's height. For a player falling out of
     * the bottom of a void hub, where no region reaches.
     */
    public boolean inHubColumn(Location location) {
        if (!configured() || location == null) {
            return false;
        }
        KnkLocation spot = spawnSpot(hub());
        if (spot == null || spot.y() == null) {
            return false;
        }
        Location probe = location.clone();
        probe.setY(spot.y());
        return inHub(probe);
    }

    /** The hub's world, if a hub is selected and its world is loaded. */
    public Optional<World> world() {
        KnkHubSettings hub = hub();
        if (!hub.configured()) {
            return Optional.empty();
        }
        String name = hub.worldName() != null ? hub.worldName() : hub.spawn().snapshot() != null ? hub.spawn().snapshot().world() : null;
        return name == null ? Optional.empty() : Optional.ofNullable(worlds.apply(name));
    }

    /** The hub portal at this spot (one of the hub's portal regions, in the hub's world), if any. */
    public Optional<String> portalAt(Location location) {
        KnkHubSettings hub = hub();
        if (!hub.configured() || hub.portalRegionIds().isEmpty() || location == null || location.getWorld() == null) {
            return Optional.empty();
        }
        String hubWorld = hub.worldName() != null ? hub.worldName() : hub.spawn().snapshot() != null ? hub.spawn().snapshot().world() : null;
        if (hubWorld == null || !hubWorld.equalsIgnoreCase(location.getWorld().getName())) {
            return Optional.empty();
        }
        Set<String> here = regionsAt.apply(location);
        return hub.portalRegionIds().stream()
            .filter(portal -> here.stream().anyMatch(id -> id.equalsIgnoreCase(portal)))
            .findFirst();
    }

    /** Hub admission placed this player at the hub spawn; their join then needs no teleport. */
    public void markAdmitted(UUID player) {
        if (player != null) {
            admitted.add(player);
        }
    }

    /** Whether hub admission placed this joining player at the hub spawn; answers once per join. */
    public boolean consumeAdmitted(UUID player) {
        return player != null && admitted.remove(player);
    }
}
