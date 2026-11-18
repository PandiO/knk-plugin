package net.knightsandkings.knk.paper.navigation.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import net.knightsandkings.knk.core.dataaccess.DomainCatalogDataAccess;
import net.knightsandkings.knk.core.dataaccess.LocationsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.domains.KnkDomainSummary;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.towns.TownDetail;
import net.knightsandkings.knk.core.navigation.DomainLocationResolver;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;
import net.knightsandkings.knk.core.navigation.model.DomainPlace;
import net.knightsandkings.knk.core.navigation.model.ModelFreshness;
import net.knightsandkings.knk.core.navigation.model.NavigationModel;
import net.knightsandkings.knk.core.navigation.model.NavigationModelSnapshot;
import net.knightsandkings.knk.core.ports.api.NavigationCompassSettingsApi;
import net.knightsandkings.knk.paper.navigation.NavigationDestinations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Destination engine phase 2 on the game server (PandiO/knk-workspace#103): the snapshot loaded at start-up, the
 * background refresh through the data accesses, and the road-access answers equal to today's catalogue (E19).
 */
class NavigationModelRuntimeTest {

    private static final Instant T0 = Instant.parse("2026-11-18T12:00:00Z");

    @TempDir
    Path dataFolder;

    private final DomainCatalogDataAccess domains = mock(DomainCatalogDataAccess.class);
    private final LocationsDataAccess locations = mock(LocationsDataAccess.class);
    private final NavigationCompassSettingsApi compass = mock(NavigationCompassSettingsApi.class);
    private final KnkLocation spawn = new KnkLocation(5, "Kardenna Spawn", 50.0, 65.0, 5.0, 0f, 0f, "world");
    private final List<KnkDomainSummary> all = List.of(
        new KnkDomainSummary(1, "Kardenna", "Town", "Spawn", "Applies", "kardenna", "world", spawn, true),
        new KnkDomainSummary(3, "Market", "District", null, "Applies", "district_3", "world", null, true),
        new KnkDomainSummary(9, "Mill House", "Structure", null, "Ignored", "Mill_House", "world", null, true),
        new KnkDomainSummary(11, "Keep Gate", "GateStructure", "Region", null, "keep_gate", "world", null, true),
        new KnkDomainSummary(50, "Wild", "Kingdom", null, "ignored", null, null, null, true));
    private NavigationModelRuntime runtime;

    @BeforeEach
    void setUp() {
        when(domains.searchAsync(any())).thenAnswer(inv -> page(inv.getArgument(0), all));
        when(locations.searchAsync(any())).thenAnswer(inv -> page(inv.getArgument(0),
            List.of(new KnkLocation(30, "Kardenna Mill", 400.0, 65.0, 10.0, 0f, 0f, "world"))));
        when(compass.get()).thenReturn(CompletableFuture.completedFuture(CompassSettings.defaults()));
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    private static <T> CompletableFuture<Page<T>> page(PagedQuery q, List<T> items) {
        return CompletableFuture.completedFuture(q.pageNumber() == 1 ? new Page<>(items, items.size(), 1, q.pageSize())
            : new Page<>(List.of(), items.size(), q.pageNumber(), q.pageSize()));
    }

    private static DomainLocationResolver resolver() {
        return new DomainLocationResolver(
            id -> CompletableFuture.completedFuture(Optional.empty()),
            id -> CompletableFuture.completedFuture(id == 1
                ? Optional.of(new TownDetail(1, "Kardenna", null, null, true, true, "kardenna", null,
                    new TownDetail.Location(5, "Kardenna Spawn", 50.0, 65.0, 5.0, 0f, 0f, "world"), List.of(), List.of(), List.of(), List.of()))
                : Optional.empty()),
            id -> CompletableFuture.completedFuture(Optional.empty()),
            id -> CompletableFuture.completedFuture(Optional.empty()));
    }

    private NavigationModelRuntime runtime() {
        runtime = new NavigationModelRuntime(NavigationModelRuntime.source(domains, locations, compass),
            NavigationModelRuntime.placeLookup(resolver()), dataFolder, NavigationModelConfig.defaults(),
            Clock.fixed(T0, ZoneOffset.UTC), Executors.newSingleThreadScheduledExecutor());
        return runtime;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(20);
        }
    }

    @Test
    void theFirstStartWritesTheSnapshotAfterTheBackgroundRefresh() throws Exception {
        NavigationModelRuntime runtime = runtime();
        Path file = dataFolder.resolve("offline").resolve("navigation-model.json");

        NavigationModelSnapshot.Loaded loaded = runtime.start();

        assertEquals(NavigationModelSnapshot.Loaded.Status.MISSING, loaded.status());
        await(() -> Files.exists(file));
        NavigationModel saved = runtime.snapshot().read().model();
        assertEquals(List.of(1, 3, 9, 11, 50), saved.domains().stream().map(DomainPlace::id).toList());
        assertEquals(50.0, saved.domain(1).orElseThrow().spawn().x());
        assertEquals(List.of("Kardenna Mill"), saved.locations().stream().map(p -> p.name()).toList());
        assertEquals(ModelFreshness.State.FRESH, runtime.freshness().state());
    }

    @Test
    void theSnapshotIsLoadedAtStartUpBeforeTheApiAnswers() throws Exception {
        NavigationModel before = new NavigationModel(T0.minus(Duration.ofDays(20)),
            List.of(new DomainPlace(1, "Kardenna", "Town", "Spawn", "Applies", "kardenna", "world", null, true)),
            List.of(), null, null);
        new NavigationModelSnapshot(dataFolder.resolve(NavigationModelSnapshot.RELATIVE_PATH)).write(before);
        org.mockito.Mockito.doReturn(new CompletableFuture<>()).when(domains).searchAsync(any()); // the API never answers

        NavigationModelRuntime runtime = runtime();
        NavigationModelSnapshot.Loaded loaded = runtime.start();

        assertTrue(loaded.loaded());
        assertEquals(before, runtime.holder().current());
        assertEquals(ModelFreshness.State.STALE, runtime.freshness().state(), "20 days > 14");
    }

    @Test
    void aBrokenSnapshotDoesNotStopTheStart() throws Exception {
        Path file = dataFolder.resolve(NavigationModelSnapshot.RELATIVE_PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ broken");
        NavigationModelRuntime runtime = runtime();

        NavigationModelSnapshot.Loaded loaded = runtime.start();

        assertEquals(NavigationModelSnapshot.Loaded.Status.CORRUPT, loaded.status());
        await(() -> runtime.snapshot().read().loaded());
    }

    @Test
    void theRoadAccessAnswersAreTheCataloguesAnswers() {
        NavigationDestinations catalogue = new NavigationDestinations(domains, locations, resolver(), w -> null, () -> 0L);
        catalogue.refresh().join();
        NavigationModelRuntime runtime = runtime();
        runtime.refresher().refresh().join();
        NavigationModel model = runtime.holder().current();

        for (int id : List.of(1, 3, 9, 11, 50, 404)) {
            assertEquals(catalogue.roadAccessIgnored(id), model.roadAccessIgnored(id), "domain " + id);
        }
        for (String region : List.of("kardenna", "district_3", "mill_house", "MILL_HOUSE", "keep_gate", "nowhere")) {
            assertEquals(catalogue.roadsIgnoreRegion(region), model.roadsIgnoreRegion(region), region);
        }
        assertTrue(model.roadAccessIgnored(9));
    }

    @Test
    void theFallbackLooksUpTownsDistrictsAndStructuresThroughTheResolver() {
        var lookup = NavigationModelRuntime.placeLookup(resolver());

        assertTrue(lookup.supports("Town"));
        assertTrue(lookup.supports("district"));
        assertTrue(lookup.supports("STRUCTURE"));
        assertFalse(lookup.supports("GateStructure"), "today's locate cannot either");
        assertFalse(lookup.supports(null));
        DomainPlace kardenna = new DomainPlace(1, "Kardenna", "Town", null, null, null, null, null, false);
        assertEquals(Optional.of(spawn), lookup.lookup(kardenna).join());
        DomainPlace unknownTown = new DomainPlace(2, "Brink", "Town", null, null, null, null, null, false);
        assertEquals(Optional.empty(), lookup.lookup(unknownTown).join());
    }
}
