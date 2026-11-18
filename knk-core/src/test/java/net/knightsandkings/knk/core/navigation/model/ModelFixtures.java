package net.knightsandkings.knk.core.navigation.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.knightsandkings.knk.core.domain.domains.KnkDomainSummary;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.navigation.compass.CompassItemRule;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;

/** Shared data for the navigation model tests: a small dev world as the API would send it. */
final class ModelFixtures {

    static final Instant T0 = Instant.parse("2026-11-18T12:00:00Z");

    private ModelFixtures() {
    }

    static KnkLocation location(int id, String name, double x, double y, double z, String world) {
        return new KnkLocation(id, name, x, y, z, 90f, 0f, world);
    }

    /** A domain as the new API's search sends it (with the location field). */
    static KnkDomainSummary domain(int id, String name, String type, String roadAccess, String region, KnkLocation spawn) {
        return new KnkDomainSummary(id, name, type, "Spawn", roadAccess, region, "world", spawn, true);
    }

    /** A domain as an API from before #103 sends it (no location field). */
    static KnkDomainSummary oldApiDomain(int id, String name, String type) {
        return new KnkDomainSummary(id, name, type, "Spawn", "Applies", type.toLowerCase() + "_" + id, "world", null, false);
    }

    static CompassSettings compass() {
        return new CompassSettings(new CompassItemRule(true, "RECOVERY_COMPASS", "Guide", CompassItemRule.NameMatch.CONTAINS),
            false, true, List.of("town:Kardenna", "street:Main Road"));
    }

    static DomainPlace place(int id, String name, String type, String roadAccess, String region, ModelPoint spawn) {
        return new DomainPlace(id, name, type, "Spawn", roadAccess, region, "world", spawn, true);
    }

    static ModelPoint point(int id, String name, double x, String world) {
        return new ModelPoint(id, name, x, 64, -x, 90f, 0f, world);
    }

    static NavigationModel model(Instant asOf) {
        return new NavigationModel(asOf,
            List.of(place(1, "Kardenna", "Town", "Applies", "town_1", point(7, "Kardenna spawn", 10, "world")),
                place(3, "Market", "District", "Applies", "district_3", null),
                place(9, "Mill House", "Structure", "Ignored", "Mill_House", null)),
            List.of(point(41, "Old Well", 100, "world")), compass(), List.of());
    }

    /** The web API as the refresher sees it; answers can be changed or made to fail between calls. */
    static final class FakeApi implements NavigationModelRefresher.Source, NavigationModelRefresher.PlaceLookup {
        volatile List<KnkDomainSummary> domains = new ArrayList<>();
        volatile List<KnkLocation> locations = new ArrayList<>();
        volatile CompassSettings compass = ModelFixtures.compass();
        volatile boolean down;
        final Map<Integer, Optional<KnkLocation>> places = new ConcurrentHashMap<>();
        final Map<Integer, Boolean> failingPlaces = new ConcurrentHashMap<>();
        final AtomicInteger lookups = new AtomicInteger();
        final AtomicInteger domainCalls = new AtomicInteger();

        @Override
        public CompletableFuture<List<KnkDomainSummary>> domains() {
            domainCalls.incrementAndGet();
            return down ? CompletableFuture.failedFuture(new IllegalStateException("API down"))
                : CompletableFuture.completedFuture(List.copyOf(domains));
        }

        @Override
        public CompletableFuture<List<KnkLocation>> locations() {
            return down ? CompletableFuture.failedFuture(new IllegalStateException("API down"))
                : CompletableFuture.completedFuture(List.copyOf(locations));
        }

        @Override
        public CompletableFuture<CompassSettings> compass() {
            return down ? CompletableFuture.failedFuture(new IllegalStateException("API down"))
                : CompletableFuture.completedFuture(compass);
        }

        @Override
        public boolean supports(String domainType) {
            return !"GateStructure".equals(domainType);
        }

        @Override
        public CompletableFuture<Optional<KnkLocation>> lookup(DomainPlace domain) {
            lookups.incrementAndGet();
            if (down || failingPlaces.containsKey(domain.id())) {
                return CompletableFuture.failedFuture(new IllegalStateException("lookup failed"));
            }
            return CompletableFuture.completedFuture(places.getOrDefault(domain.id(), Optional.empty()));
        }
    }
}
