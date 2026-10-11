package net.knightsandkings.knk.core.navigation;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.districts.DistrictDetail;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.structures.StructureDetail;
import net.knightsandkings.knk.core.domain.towns.TownDetail;
import net.knightsandkings.knk.core.teleport.SpawnPointResolver.LocationLookup;

/**
 * A domain's own {@code Location} (road navigation plan §2 R20, DESIGN §6.1): a Town or District
 * embeds its Location when the API included it, else carries a {@code locationId}; a Structure only
 * has the id. Extracted from teleport's {@code SpawnDestinationResolver.ownLocation} and its three
 * lambdas so {@code /spawn} and {@code /navigate} resolve places the same way. Bukkit-free: the
 * gateways come in as ports, the paper side binds them to the data accesses.
 */
public final class DomainLocationResolver {

    /** A domain by id from its gateway; empty when there is none (or the lookup failed). */
    @FunctionalInterface
    public interface DomainFetch<D> {
        CompletableFuture<Optional<D>> find(int id);
    }

    /**
     * A domain as a destination: its id, name, type word ("Town", "District", "Structure"), its
     * WorldGuard region id (for region-mode goals) and its own Location when it has one.
     */
    public record DomainPlace(int id, String name, String domainType, String wgRegionId, Optional<KnkLocation> location) {
        public DomainPlace {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(domainType, "domainType");
            location = location == null ? Optional.empty() : location;
        }
    }

    public static final String TOWN = "Town";
    public static final String DISTRICT = "District";
    public static final String STRUCTURE = "Structure";

    private final LocationLookup locations;
    private final DomainFetch<TownDetail> towns;
    private final DomainFetch<DistrictDetail> districts;
    private final DomainFetch<StructureDetail> structures;

    public DomainLocationResolver(LocationLookup locations, DomainFetch<TownDetail> towns,
                                  DomainFetch<DistrictDetail> districts, DomainFetch<StructureDetail> structures) {
        this.locations = Objects.requireNonNull(locations, "locations");
        this.towns = Objects.requireNonNull(towns, "towns");
        this.districts = Objects.requireNonNull(districts, "districts");
        this.structures = Objects.requireNonNull(structures, "structures");
    }

    // ==================== Location only (the /spawn lookups) ====================

    /** A Town's Location: embedded when the API sent it, else its {@code locationId} looked up. */
    public CompletableFuture<Optional<KnkLocation>> townLocation(int id) {
        return towns.find(id).thenCompose(town -> town
            .map(t -> ownLocation(toLocation(t.location()), t.locationId(), locations))
            .orElse(CompletableFuture.completedFuture(Optional.empty())));
    }

    /** A District's Location: embedded when the API sent it, else its {@code locationId} looked up. */
    public CompletableFuture<Optional<KnkLocation>> districtLocation(int id) {
        return districts.find(id).thenCompose(district -> district
            .map(d -> ownLocation(toLocation(d.location()), d.locationId(), locations))
            .orElse(CompletableFuture.completedFuture(Optional.empty())));
    }

    /** A Structure's Location: only ever its {@code locationId} looked up. */
    public CompletableFuture<Optional<KnkLocation>> structureLocation(int id) {
        return structures.find(id).thenCompose(structure -> structure
            .map(s -> ownLocation(null, s.locationId(), locations))
            .orElse(CompletableFuture.completedFuture(Optional.empty())));
    }

    // ==================== The whole place (navigation) ====================

    public CompletableFuture<Optional<DomainPlace>> town(int id) {
        return towns.find(id).thenCompose(town -> town
            .map(t -> ownLocation(toLocation(t.location()), t.locationId(), locations)
                .thenApply(location -> Optional.of(new DomainPlace(id, t.name(), TOWN, t.wgRegionId(), location))))
            .orElse(CompletableFuture.completedFuture(Optional.empty())));
    }

    public CompletableFuture<Optional<DomainPlace>> district(int id) {
        return districts.find(id).thenCompose(district -> district
            .map(d -> ownLocation(toLocation(d.location()), d.locationId(), locations)
                .thenApply(location -> Optional.of(new DomainPlace(id, d.name(), DISTRICT, d.wgRegionId(), location))))
            .orElse(CompletableFuture.completedFuture(Optional.empty())));
    }

    public CompletableFuture<Optional<DomainPlace>> structure(int id) {
        return structures.find(id).thenCompose(structure -> structure
            .map(s -> ownLocation(null, s.locationId(), locations)
                .thenApply(location -> Optional.of(new DomainPlace(id, s.name(), STRUCTURE, s.wgRegionId(), location))))
            .orElse(CompletableFuture.completedFuture(Optional.empty())));
    }

    /** By the type word the domain catalogue uses ("Town", "District", "Structure", any case); empty for another. */
    public CompletableFuture<Optional<DomainPlace>> byType(String domainType, int id) {
        if (domainType == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return switch (domainType.toLowerCase(Locale.ROOT)) {
            case "town" -> town(id);
            case "district" -> district(id);
            case "structure" -> structure(id);
            default -> CompletableFuture.completedFuture(Optional.empty());
        };
    }

    // ==================== Helpers ====================

    /** A domain's embedded Location when the API included it (with a world), else its {@code locationId} looked up. */
    public static CompletableFuture<Optional<KnkLocation>> ownLocation(KnkLocation embedded, Integer locationId,
                                                                       LocationLookup locations) {
        if (embedded != null && embedded.world() != null) {
            return CompletableFuture.completedFuture(Optional.of(embedded));
        }
        if (locationId == null || locationId <= 0) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return locations.find(locationId);
    }

    public static KnkLocation toLocation(TownDetail.Location location) {
        return location == null ? null : new KnkLocation(location.id(), location.name(), location.x(), location.y(),
            location.z(), location.yaw(), location.pitch(), location.world());
    }

    public static KnkLocation toLocation(DistrictDetail.Location location) {
        return location == null ? null : new KnkLocation(location.id(), location.name(), location.x(), location.y(),
            location.z(), location.yaw(), location.pitch(), location.world());
    }
}
