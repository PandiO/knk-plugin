package net.knightsandkings.knk.paper.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.dataaccess.DomainCatalogDataAccess;
import net.knightsandkings.knk.core.dataaccess.LocationsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.domains.KnkDomainSummary;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.navigation.DomainLocationResolver;
import net.knightsandkings.knk.core.navigation.DomainLocationResolver.DomainPlace;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.util.NamedTargets;

/**
 * The {@code /navigate} catalogue (DESIGN §6.1, plan Phase 4 task 5): Locations and Towns /
 * Districts / Structures (gates included, also as {@code gatestructure:} / {@code gate:}) from the
 * web API (reuse map R19 {@code DomainCatalogDataAccess.searchAsync}, R18
 * {@code LocationsDataAccess.searchAsync}), the labelled streets and the named road nodes of
 * the world's network snapshot, as one list of {@link NavTarget}s that {@link NamedTargets} (R21)
 * resolves: a bare name, {@code type:name}, {@code type:#id}; one match → go, several → the
 * {@code type:name} choices; tab completion the same way.
 *
 * <p>The API part is loaded once and refreshed in the background every {@link #REFRESH_MILLIS}
 * (a refresh is started by the next call that finds it stale, never awaited: tab completion stays
 * synchronous). {@link #locate} turns a target into a routable {@link Destination}: a domain's
 * spawn Location through {@link DomainLocationResolver} (R20), or its region.
 */
public final class NavigationDestinations {

    private static final Logger LOGGER = Logger.getLogger(NavigationDestinations.class.getName());
    public static final long REFRESH_MILLIS = 60_000;
    static final int PAGE_SIZE = 200;
    static final int MAX_PAGES = 25;

    /** What the player asked for after the name: the domain's spawn Location, its region, or whatever it has. */
    public enum Mode {
        DEFAULT,
        SPAWN,
        REGION;

        public static Optional<Mode> parse(String word) {
            if (word == null) {
                return Optional.empty();
            }
            return switch (word.toLowerCase(Locale.ROOT)) {
                case "spawn" -> Optional.of(SPAWN);
                case "region" -> Optional.of(REGION);
                default -> Optional.empty();
            };
        }
    }

    /** {@link #resolve}'s answer: found, ambiguous (choices), or nothing (suggestions). */
    public record Resolution(NavTarget target, List<NavTarget> choices, List<NavTarget> suggestions) {
        public boolean found() {
            return target != null;
        }

        public boolean ambiguous() {
            return target == null && !choices.isEmpty();
        }

        public List<String> choiceNames() {
            return choices.stream().map(NavTarget::qualifiedName).toList();
        }

        public List<String> suggestionNames() {
            return suggestions.stream().map(NavTarget::name).distinct().toList();
        }
    }

    /** {@link #locate}'s answer: a destination, or why there is none. */
    public record Located(Destination destination, Failure failure) {
        public enum Failure {
            /** The domain has neither a Location nor a region. */
            NO_LOCATION,
            /** The place lives in another world than the player. */
            OTHER_WORLD,
            /** The API has no such domain any more (or the lookup failed). */
            NOT_FOUND
        }

        public static Located of(Destination destination) {
            return new Located(destination, null);
        }

        public static Located failed(Failure failure) {
            return new Located(null, failure);
        }

        public boolean ok() {
            return destination != null;
        }
    }

    private static final NamedTargets<NavTarget> TARGETS =
        new NamedTargets<>(NavTarget::name, t -> t.type().word(), NavTarget::id, NavTarget::aliases);

    private final DomainCatalogDataAccess domains;
    private final LocationsDataAccess locations;
    private final DomainLocationResolver domainLocations;
    private final Function<String, RoadNetworkSnapshot> snapshots;
    private final LongSupplier clock;

    private volatile List<NavTarget> remote = List.of();
    private volatile long loadedAt = Long.MIN_VALUE;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    public NavigationDestinations(DomainCatalogDataAccess domains, LocationsDataAccess locations,
                                  DomainLocationResolver domainLocations, Function<String, RoadNetworkSnapshot> snapshots,
                                  LongSupplier clock) {
        this.domains = Objects.requireNonNull(domains, "domains");
        this.locations = Objects.requireNonNull(locations, "locations");
        this.domainLocations = Objects.requireNonNull(domainLocations, "domainLocations");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ==================== catalogue ====================

    /** Every target a player in {@code world} may name now (API part as last loaded; streets and nodes live). */
    public List<NavTarget> catalogue(String world) {
        refreshIfStale();
        List<NavTarget> out = new ArrayList<>(remote);
        RoadNetworkSnapshot snapshot = world == null ? null : snapshots.apply(world);
        if (snapshot != null) {
            for (Map.Entry<Integer, RoadNetworkSnapshot.Street> street : snapshot.streets().entrySet()) {
                if (street.getValue().name() != null && !street.getValue().name().isBlank()) {
                    out.add(NavTarget.street(world, street.getKey(), street.getValue().name()));
                }
            }
            for (RoadNode node : snapshot.namedNodes()) {
                out.add(NavTarget.node(world, node.id(), node.name(), node.position()));
            }
        }
        return out;
    }

    /** Resolve what the player typed against {@link #catalogue}. */
    public Resolution resolve(String input, String world) {
        List<NavTarget> all = catalogue(world);
        NamedTargets.Match<NavTarget> match = TARGETS.resolve(all, input);
        if (match.found()) {
            return new Resolution(match.target(), List.of(), List.of());
        }
        if (match.ambiguous()) {
            return new Resolution(null, match.choices(), List.of());
        }
        return new Resolution(null, List.of(), TARGETS.suggestions(all, input));
    }

    /** Tab completions for the destination words typed so far. */
    public List<String> complete(List<String> words, String world) {
        return TARGETS.complete(catalogue(world), words);
    }

    /** Whether {@code words} name exactly one target (so the next word may be {@code spawn} / {@code region}). */
    public boolean isComplete(List<String> words, String world) {
        return TARGETS.resolve(catalogue(world), String.join(" ", words)).found();
    }

    // ==================== locating ====================

    /**
     * The target as a destination for a player in {@code playerWorld}: a Location's point; a domain's
     * spawn Location (or its region with {@code region}, or when it has no Location); a street; a node.
     * Any thread; completes on the API client's thread for domains.
     */
    public CompletableFuture<Located> locate(NavTarget target, Mode mode, String playerWorld) {
        switch (target.type()) {
            case LOCATION -> {
                KnkLocation l = target.location();
                if (l == null || l.world() == null || l.x() == null || l.y() == null || l.z() == null) {
                    return CompletableFuture.completedFuture(Located.failed(Located.Failure.NO_LOCATION));
                }
                if (!l.world().equalsIgnoreCase(playerWorld)) {
                    return CompletableFuture.completedFuture(Located.failed(Located.Failure.OTHER_WORLD));
                }
                return CompletableFuture.completedFuture(Located.of(Destination.point(target.name(), l.world(), l.x(), l.y(), l.z())));
            }
            case STREET -> {
                return CompletableFuture.completedFuture(sameWorld(target, playerWorld)
                    ? Located.of(Destination.street(target.name(), target.world(), target.id()))
                    : Located.failed(Located.Failure.OTHER_WORLD));
            }
            case NODE -> {
                return CompletableFuture.completedFuture(sameWorld(target, playerWorld)
                    ? Located.of(Destination.node(target.name(), target.world(), target.id(), target.position()))
                    : Located.failed(Located.Failure.OTHER_WORLD));
            }
            default -> {
                return domainLocations.byType(target.type().word(), target.id())
                    .thenApply(place -> place.map(p -> locateDomain(p, mode, playerWorld))
                        .orElse(Located.failed(Located.Failure.NOT_FOUND)))
                    .exceptionally(ex -> {
                        LOGGER.log(Level.WARNING, "[Navigation] Could not look up " + target + ": " + ex.getMessage());
                        return Located.failed(Located.Failure.NOT_FOUND);
                    });
            }
        }
    }

    /**
     * DESIGN §6.1: the Location unless {@code region} was asked; a domain without one falls back to its region.
     * Without {@code spawn}, a player already inside the domain's region is already there (N9).
     */
    static Located locateDomain(DomainPlace place, Mode mode, String playerWorld) {
        Optional<KnkLocation> location = place.location()
            .filter(l -> l.world() != null && l.x() != null && l.y() != null && l.z() != null);
        boolean hasRegion = place.wgRegionId() != null && !place.wgRegionId().isBlank();
        if (mode != Mode.REGION && location.isPresent()) {
            KnkLocation l = location.get();
            if (!l.world().equalsIgnoreCase(playerWorld)) {
                return Located.failed(Located.Failure.OTHER_WORLD);
            }
            return Located.of(mode == Mode.DEFAULT && hasRegion
                ? Destination.domainPoint(place.name(), l.world(), l.x(), l.y(), l.z(), place.wgRegionId())
                : Destination.point(place.name(), l.world(), l.x(), l.y(), l.z()));
        }
        if (hasRegion) {
            // Regions carry no world in the API; the player's world is tried (WorldGuard answers "unknown" otherwise).
            return Located.of(Destination.region(place.name(), playerWorld, place.wgRegionId()));
        }
        if (mode == Mode.REGION && location.isPresent()) {
            KnkLocation l = location.get();
            if (!l.world().equalsIgnoreCase(playerWorld)) {
                return Located.failed(Located.Failure.OTHER_WORLD);
            }
            return Located.of(Destination.point(place.name(), l.world(), l.x(), l.y(), l.z()));
        }
        return Located.failed(Located.Failure.NO_LOCATION);
    }

    private static boolean sameWorld(NavTarget target, String playerWorld) {
        return target.world() != null && target.world().equalsIgnoreCase(playerWorld);
    }

    // ==================== API part ====================

    /** Force a reload of the API part (start-up, {@code /knk cache refresh}). */
    public CompletableFuture<Void> refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<List<NavTarget>> domainTargets = allPages(query -> domains.searchAsync(query))
            .thenApply(list -> {
                List<NavTarget> out = new ArrayList<>();
                for (KnkDomainSummary domain : list) {
                    NavTarget.Type type = NavTarget.Type.ofDomainType(domain.domainType());
                    if (type != null && domain.id() != null && domain.name() != null && !domain.name().isBlank()) {
                        out.add(NavTarget.domain(type, domain.id(), domain.name(), domain.domainType()));
                    }
                }
                return out;
            });
        CompletableFuture<List<NavTarget>> locationTargets = allPages(query -> locations.searchAsync(query))
            .thenApply(list -> {
                List<NavTarget> out = new ArrayList<>();
                for (KnkLocation location : list) {
                    if (location.name() != null && !location.name().isBlank() && location.world() != null) {
                        out.add(NavTarget.location(location));
                    }
                }
                return out;
            });
        return domainTargets.thenCombine(locationTargets, (d, l) -> {
                List<NavTarget> merged = new ArrayList<>(d.size() + l.size());
                merged.addAll(d);
                merged.addAll(l);
                return Collections.unmodifiableList(merged);
            })
            .whenComplete((merged, ex) -> {
                refreshing.set(false);
                if (ex != null) {
                    LOGGER.log(Level.WARNING, "[Navigation] Destination catalogue refresh failed: " + ex.getMessage());
                    loadedAt = clock.getAsLong(); // retry after the interval, not on every call
                    return;
                }
                remote = merged;
                loadedAt = clock.getAsLong();
                LOGGER.fine("[Navigation] Destination catalogue: " + merged.size() + " places");
            })
            .thenApply(merged -> null);
    }

    private void refreshIfStale() {
        if (loadedAt == Long.MIN_VALUE || clock.getAsLong() - loadedAt >= REFRESH_MILLIS) {
            refresh();
        }
    }

    /** Every item of a paged search, {@link #PAGE_SIZE} at a time, at most {@link #MAX_PAGES} pages. */
    static <T> CompletableFuture<List<T>> allPages(Function<PagedQuery, CompletableFuture<Page<T>>> search) {
        List<T> out = new ArrayList<>();
        return page(search, 1, out);
    }

    private static <T> CompletableFuture<List<T>> page(Function<PagedQuery, CompletableFuture<Page<T>>> search,
                                                       int pageNumber, List<T> out) {
        return search.apply(new PagedQuery(pageNumber, PAGE_SIZE, null, "name", false, Map.of()))
            .thenCompose(page -> {
                if (page == null || page.items() == null || page.items().isEmpty()) {
                    return CompletableFuture.completedFuture(out);
                }
                out.addAll(page.items());
                boolean more = out.size() < page.totalCount() && page.items().size() >= PAGE_SIZE && pageNumber < MAX_PAGES;
                return more ? page(search, pageNumber + 1, out) : CompletableFuture.completedFuture(out);
            });
    }

    /** For the tests: the API part as loaded. */
    List<NavTarget> remote() {
        return remote;
    }
}
