package net.knightsandkings.knk.core.navigation.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.knightsandkings.knk.core.navigation.compass.CompassSettings;

/**
 * The navigation model of the destination engine (DESIGN § 7, E1): everything the engine's providers need that comes
 * from the web API - the domain places, the {@code Location} targets, the navigation compass settings, the road-access
 * answers (E19) and the landmarks (E16, empty until PandiO/knk-workspace#708) - as one immutable value. It is loaded
 * from the snapshot at start-up, replaced whole by every successful API refresh (so renames and deletions propagate,
 * § 7.3) and saved again; an outage only means it is not refreshed (§ 7.1).
 *
 * <p>Pure and Bukkit-free. Nothing in the running plugin reads it before phase 3 (PandiO/knk-workspace#69); the road
 * access answers here are the same as {@code NavigationDestinations}' (E19), which they replace from phase 3 on.
 *
 * <p>Equality ({@link #sameContent}) ignores {@link #asOf()}: a refresh that brings nothing new does not change the
 * model's version, so caches keyed by version stay valid (§ 11).
 */
public final class NavigationModel {

    private final Instant asOf;
    private final List<DomainPlace> domains;
    private final List<ModelPoint> locations;
    private final CompassSettings compass;
    private final List<Landmark> landmarks;
    private final Map<Integer, DomainPlace> domainsById;
    private final Set<Integer> roadAccessIgnored;
    private final Set<String> roadAccessIgnoredRegions;

    /**
     * @param asOf      when the API last confirmed this content
     * @param domains   every domain of the search; duplicates by id keep the first
     * @param locations the {@code Location} targets (named, with a world and coordinates)
     * @param compass   the navigation compass settings; null means {@link CompassSettings#defaults()}
     * @param landmarks the landmarks; null means none (schema 1 before #708)
     */
    public NavigationModel(Instant asOf, List<DomainPlace> domains, List<ModelPoint> locations, CompassSettings compass,
                           List<Landmark> landmarks) {
        this.asOf = Objects.requireNonNull(asOf, "asOf");
        Map<Integer, DomainPlace> byId = new LinkedHashMap<>();
        for (DomainPlace domain : domains == null ? List.<DomainPlace>of() : domains) {
            if (domain != null) {
                byId.putIfAbsent(domain.id(), domain);
            }
        }
        List<DomainPlace> sorted = new ArrayList<>(byId.values());
        sorted.sort(Comparator.comparingInt(DomainPlace::id));
        this.domains = List.copyOf(sorted);
        this.domainsById = Collections.unmodifiableMap(byId);
        List<ModelPoint> sortedLocations = new ArrayList<>();
        for (ModelPoint location : locations == null ? List.<ModelPoint>of() : locations) {
            if (location != null && location.name() != null && !location.name().isBlank()) {
                sortedLocations.add(location);
            }
        }
        sortedLocations.sort(Comparator.comparing((ModelPoint p) -> p.id() == null ? Integer.MAX_VALUE : p.id())
            .thenComparing(ModelPoint::name));
        this.locations = List.copyOf(sortedLocations);
        this.compass = compass == null ? CompassSettings.defaults() : compass;
        List<Landmark> sortedLandmarks = new ArrayList<>(landmarks == null ? List.of() : landmarks);
        sortedLandmarks.removeIf(Objects::isNull);
        sortedLandmarks.sort(Comparator.comparingInt(Landmark::id));
        this.landmarks = List.copyOf(sortedLandmarks);

        Set<Integer> ignored = new HashSet<>();
        Set<String> ignoredRegions = new HashSet<>();
        for (DomainPlace domain : this.domains) {
            if (domain.roadAccessIgnored()) {
                ignored.add(domain.id());
                if (domain.wgRegionId() != null && !domain.wgRegionId().isBlank()) {
                    ignoredRegions.add(domain.wgRegionId().toLowerCase(Locale.ROOT));
                }
            }
        }
        this.roadAccessIgnored = Set.copyOf(ignored);
        this.roadAccessIgnoredRegions = Set.copyOf(ignoredRegions);
    }

    public Instant asOf() {
        return asOf;
    }

    /** Every domain, by id. */
    public List<DomainPlace> domains() {
        return domains;
    }

    public Optional<DomainPlace> domain(int id) {
        return Optional.ofNullable(domainsById.get(id));
    }

    /** The {@code /navigate location:} targets, by id. */
    public List<ModelPoint> locations() {
        return locations;
    }

    public CompassSettings compass() {
        return compass;
    }

    /** Empty in schema 1 until off-road phase 5.1 (#708) fills it (E16). */
    public List<Landmark> landmarks() {
        return landmarks;
    }

    // ==================== road-access answers (E19) ====================

    /**
     * Rev. 7 Part C (KNG-92): whether the domain's entry/exit rule is lifted off the roads ("Ignored"). Unknown domains
     * keep the rule. The same answer as {@code NavigationDestinations.roadAccessIgnored}.
     */
    public boolean roadAccessIgnored(int domainId) {
        return roadAccessIgnored.contains(domainId);
    }

    /**
     * Whether a WorldGuard region belongs to a domain whose rule is "Ignored" for roads (any case). Unknown regions:
     * false. The same answer as {@code NavigationDestinations.roadsIgnoreRegion}.
     */
    public boolean roadsIgnoreRegion(String regionId) {
        return regionId != null && roadAccessIgnoredRegions.contains(regionId.toLowerCase(Locale.ROOT));
    }

    /** The regions {@link #roadsIgnoreRegion} answers true for, lower-cased (to tell whether they changed). */
    public Set<String> roadAccessIgnoredRegions() {
        return roadAccessIgnoredRegions;
    }

    // ==================== places not known yet (§ 7.2 fallback) ====================

    /** Domains whose place the API's search did not give and the fallback has not looked up yet. */
    public List<DomainPlace> unknownPlaces() {
        return domains.stream().filter(d -> !d.placeKnown()).toList();
    }

    /** The same model with domain {@code id}'s place set (null spawn: it has none); this model when the id is unknown. */
    public NavigationModel withPlace(int id, ModelPoint spawn) {
        DomainPlace domain = domainsById.get(id);
        if (domain == null) {
            return this;
        }
        List<DomainPlace> updated = new ArrayList<>(domains.size());
        for (DomainPlace d : domains) {
            updated.add(d.id() == id ? d.withPlace(spawn) : d);
        }
        return new NavigationModel(asOf, updated, locations, compass, landmarks);
    }

    /**
     * This model with the places {@code earlier} knew for domains this one does not know yet: an older API sends no
     * Locations, so a refresh keeps what the fallback looked up instead of starting again.
     */
    public NavigationModel withKnownPlacesOf(NavigationModel earlier) {
        if (earlier == null || unknownPlaces().isEmpty()) {
            return this;
        }
        List<DomainPlace> updated = new ArrayList<>(domains.size());
        for (DomainPlace d : domains) {
            updated.add(d.withPlaceOf(earlier.domainsById.get(d.id())));
        }
        return new NavigationModel(asOf, updated, locations, compass, landmarks);
    }

    /** The same content confirmed at another time. */
    public NavigationModel withAsOf(Instant asOf) {
        return new NavigationModel(asOf, domains, locations, compass, landmarks);
    }

    /** Whether {@code other} holds the same domains, Locations, compass settings and landmarks ({@code asOf} aside). */
    public boolean sameContent(NavigationModel other) {
        return other != null && domains.equals(other.domains) && locations.equals(other.locations)
            && compass.equals(other.compass) && landmarks.equals(other.landmarks);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NavigationModel other && asOf.equals(other.asOf) && sameContent(other);
    }

    @Override
    public int hashCode() {
        return Objects.hash(asOf, domains, locations, compass, landmarks);
    }

    @Override
    public String toString() {
        return "NavigationModel[asOf=" + asOf + ", domains=" + domains.size() + ", locations=" + locations.size()
            + ", landmarks=" + landmarks.size() + ", unknownPlaces=" + unknownPlaces().size() + "]";
    }
}
