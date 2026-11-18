package net.knightsandkings.knk.core.navigation.model;

import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.T0;
import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.domain;
import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.location;
import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.oldApiDomain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.knightsandkings.knk.core.navigation.compass.CompassSettings;
import net.knightsandkings.knk.core.navigation.model.ModelFixtures.FakeApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Destination engine DESIGN § 7.1 to § 7.3 (A11 to A14): refresh, the paced place fallback, the snapshot written
 * when the content changed, a restart with the API down.
 */
class NavigationModelRefresherTest {

    @TempDir
    Path dir;

    private FakeApi api;
    private MutableClock clock;
    private final List<Runnable> writes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        api = new FakeApi();
        api.domains.add(domain(1, "Kardenna", "Town", "Applies", "town_1", location(7, "Kardenna spawn", 10, 64, -20, "world")));
        api.domains.add(domain(3, "Market", "District", "Applies", "district_3", null));
        api.domains.add(domain(9, "Mill House", "Structure", "Ignored", "Mill_House", null));
        api.locations.add(location(41, "Old Well", 100, 64, 5, "world"));
        clock = new MutableClock(T0);
    }

    private NavigationModelSnapshot snapshot() {
        return new NavigationModelSnapshot(dir.resolve(NavigationModelSnapshot.RELATIVE_PATH));
    }

    /** A refresher whose snapshot writes run when the test says ({@link #runWrites}); the API answers at once. */
    private NavigationModelRefresher refresher(NavigationModelHolder holder, int perTick) {
        return new NavigationModelRefresher(api, api, holder, snapshot(), writes::add, clock, perTick);
    }

    private void runWrites() {
        List<Runnable> pending = new ArrayList<>(writes);
        writes.clear();
        pending.forEach(Runnable::run);
    }

    // ==================== refresh ====================

    @Test
    void aRefreshBuildsTheModelFromTheSearchWithItsLocations() {
        NavigationModelHolder holder = new NavigationModelHolder();

        assertTrue(refresher(holder, 2).refresh().join());

        NavigationModel model = holder.current();
        assertEquals(T0, model.asOf());
        assertEquals(List.of(1, 3, 9), model.domains().stream().map(DomainPlace::id).toList());
        assertEquals(10, model.domain(1).orElseThrow().spawn().x());
        assertTrue(model.domain(3).orElseThrow().placeKnown(), "the API said: no Location");
        assertNull(model.domain(3).orElseThrow().spawn());
        assertTrue(model.roadAccessIgnored(9));
        assertEquals(List.of("Old Well"), model.locations().stream().map(ModelPoint::name).toList());
        assertEquals(ModelFixtures.compass(), model.compass());
        assertTrue(model.landmarks().isEmpty());
    }

    @Test
    void aRefreshReplacesTheModelSoRenamesAndDeletionsPropagate() {
        NavigationModelHolder holder = new NavigationModelHolder();
        NavigationModelRefresher refresher = refresher(holder, 2);
        refresher.refresh().join();

        api.domains.set(0, domain(1, "Kardenna the Great", "Town", "Applies", "town_1", null));
        api.domains.remove(2);
        api.compass = CompassSettings.defaults();
        clock.advance(Duration.ofMinutes(1));
        assertTrue(refresher.refresh().join());

        NavigationModel model = holder.current();
        assertEquals("Kardenna the Great", model.domain(1).orElseThrow().name());
        assertEquals(Optional.empty(), model.domain(9), "deleted in the web app: gone");
        assertFalse(model.roadAccessIgnored(9));
        assertEquals(CompassSettings.defaults(), model.compass());
        assertEquals(2, holder.version());
    }

    @Test
    void aFailedRefreshKeepsTheLastModel() {
        NavigationModelHolder holder = new NavigationModelHolder();
        NavigationModelRefresher refresher = refresher(holder, 2);
        refresher.refresh().join();
        NavigationModel before = holder.current();

        api.down = true;
        clock.advance(Duration.ofMinutes(1));

        assertFalse(refresher.refresh().join());
        assertEquals(before, holder.current(), "an outage only means no refresh (§ 7.1)");
    }

    @Test
    void aRefreshThatBringsNothingNewKeepsTheVersion() {
        NavigationModelHolder holder = new NavigationModelHolder();
        NavigationModelRefresher refresher = refresher(holder, 2);
        refresher.refresh().join();
        clock.advance(Duration.ofMinutes(1));

        assertFalse(refresher.refresh().join());
        assertEquals(1, holder.version());
        assertEquals(T0.plus(Duration.ofMinutes(1)), holder.current().asOf());
    }

    // ==================== snapshot ====================

    @Test
    void theSnapshotIsWrittenOffTheCallingThreadAfterAChange() throws Exception {
        NavigationModelHolder holder = new NavigationModelHolder();
        NavigationModelRefresher refresher = refresher(holder, 2);

        refresher.refresh().join();
        assertFalse(Files.exists(snapshot().file()), "not written on the refreshing thread");
        assertEquals(1, writes.size());
        runWrites();

        assertEquals(holder.current(), snapshot().read().model());
    }

    @Test
    void anUnchangedModelIsNotWrittenAgainUntilItsFileIsADayBehind() {
        NavigationModelHolder holder = new NavigationModelHolder();
        NavigationModelRefresher refresher = refresher(holder, 2);
        refresher.refresh().join();
        runWrites();

        clock.advance(Duration.ofHours(23));
        refresher.refresh().join();
        assertTrue(writes.isEmpty(), "same content, file less than a day behind");

        clock.advance(Duration.ofHours(2));
        refresher.refresh().join();
        assertEquals(1, writes.size(), "the file's asOf is refreshed once a day");
        runWrites();
        assertEquals(T0.plus(Duration.ofHours(25)), snapshot().read().model().asOf());
    }

    @Test
    void afterARestartWithTheApiDownTheSameModelIsServed() {
        NavigationModelHolder before = new NavigationModelHolder();
        refresher(before, 2).refresh().join();
        runWrites();

        api.down = true;
        NavigationModelHolder after = new NavigationModelHolder();
        NavigationModelRefresher restarted = refresher(after, 2);
        NavigationModelSnapshot.Loaded loaded = restarted.loadSnapshot();
        restarted.refresh().join();

        assertTrue(loaded.loaded());
        assertEquals(before.current(), after.current(), "same domains, Locations and compass settings (A12, E1)");
        assertTrue(after.current().roadAccessIgnored(9), "road access too (E19)");
    }

    @Test
    void aCorruptSnapshotIsIgnoredAndTheNextGoodRefreshWritesANewOne() throws Exception {
        Files.createDirectories(snapshot().file().getParent());
        Files.writeString(snapshot().file(), "{ broken");
        NavigationModelHolder holder = new NavigationModelHolder();
        NavigationModelRefresher refresher = refresher(holder, 2);

        NavigationModelSnapshot.Loaded loaded = refresher.loadSnapshot();
        assertEquals(NavigationModelSnapshot.Loaded.Status.CORRUPT, loaded.status());
        assertNull(holder.current(), "limited until the API answers");

        refresher.refresh().join();
        runWrites();
        assertTrue(snapshot().read().loaded());
    }

    // ==================== paced fallback (older API) ====================

    @Test
    void withAnOlderApiPlacesAreLookedUpAFewPerTickAndTheSnapshotWaitsForThem() {
        api.domains.clear();
        for (int id = 1; id <= 5; id++) {
            api.domains.add(oldApiDomain(id, "Town " + id, "Town"));
            api.places.put(id, Optional.of(location(100 + id, null, id, 64, id, "world")));
        }
        api.places.put(5, Optional.empty());
        NavigationModelHolder holder = new NavigationModelHolder();
        NavigationModelRefresher refresher = refresher(holder, 2);

        refresher.refresh().join();
        assertEquals(5, holder.current().unknownPlaces().size());
        assertEquals(5, refresher.queuedPlaces());
        assertTrue(writes.isEmpty(), "saved when complete (§ 7.2)");

        refresher.backfillTick();
        assertEquals(2, api.lookups.get(), "two per tick");
        assertEquals(3, holder.current().unknownPlaces().size());
        refresher.backfillTick();
        refresher.backfillTick();
        assertEquals(5, api.lookups.get());
        assertTrue(holder.current().unknownPlaces().isEmpty());
        assertEquals(1.0, holder.current().domain(1).orElseThrow().spawn().x());
        assertNull(holder.current().domain(5).orElseThrow().spawn(), "looked up: no Location");

        assertEquals(1, writes.size(), "complete: written");
        runWrites();
        assertTrue(snapshot().read().model().unknownPlaces().isEmpty());
    }

    @Test
    void placesFoundByTheFallbackAreKeptByTheNextRefresh() {
        api.domains.clear();
        api.domains.add(oldApiDomain(1, "Kardenna", "Town"));
        api.places.put(1, Optional.of(location(7, null, 10, 64, -20, "world")));
        NavigationModelHolder holder = new NavigationModelHolder();
        NavigationModelRefresher refresher = refresher(holder, 2);
        refresher.refresh().join();
        refresher.backfillTick();

        clock.advance(Duration.ofMinutes(1));
        refresher.refresh().join();

        assertTrue(holder.current().unknownPlaces().isEmpty());
        assertEquals(0, refresher.queuedPlaces());
        assertEquals(1, api.lookups.get(), "not looked up again");

        refresher.refresh(true).join();
        assertEquals(1, refresher.queuedPlaces(), "/knk cache refresh looks it up again");
    }

    @Test
    void aFailedLookupIsTriedAgainAfterTheNextRefreshAndDoesNotHoldUpTheSnapshot() {
        api.domains.clear();
        api.domains.add(oldApiDomain(1, "Kardenna", "Town"));
        api.failingPlaces.put(1, true);
        NavigationModelHolder holder = new NavigationModelHolder();
        NavigationModelRefresher refresher = refresher(holder, 2);
        refresher.refresh().join();

        refresher.backfillTick();
        assertEquals(0, refresher.queuedPlaces(), "not retried in a loop");
        assertEquals(1, writes.size(), "the round is over: written with the place unknown");
        assertFalse(holder.current().domain(1).orElseThrow().placeKnown());

        api.failingPlaces.clear();
        api.places.put(1, Optional.of(location(7, null, 10, 64, -20, "world")));
        clock.advance(Duration.ofMinutes(1));
        refresher.refresh().join();
        refresher.backfillTick();
        assertTrue(holder.current().domain(1).orElseThrow().placeKnown());
    }

    @Test
    void withTheNewApiNothingIsLookedUp() {
        NavigationModelHolder holder = new NavigationModelHolder();
        NavigationModelRefresher refresher = refresher(holder, 2);
        refresher.refresh().join();

        refresher.backfillTick();

        assertEquals(0, refresher.queuedPlaces());
        assertEquals(0, api.lookups.get());
    }

    @Test
    void typesTheFallbackCannotLookUpAreLeftUnknown() {
        api.domains.clear();
        api.domains.add(oldApiDomain(11, "Keep Gate", "GateStructure"));
        NavigationModelHolder holder = new NavigationModelHolder();
        NavigationModelRefresher refresher = refresher(holder, 2);

        refresher.refresh().join();

        assertEquals(0, refresher.queuedPlaces());
        assertFalse(holder.current().domain(11).orElseThrow().placeKnown());
        assertEquals(1, writes.size());
    }

    @Test
    void aSnapshotWithUnknownPlacesQueuesThemAtStartUp() throws Exception {
        api.domains.clear();
        api.domains.add(oldApiDomain(1, "Kardenna", "Town"));
        api.failingPlaces.put(1, true);
        NavigationModelHolder first = new NavigationModelHolder();
        NavigationModelRefresher before = refresher(first, 2);
        before.refresh().join();
        before.backfillTick();
        runWrites();

        NavigationModelRefresher restarted = refresher(new NavigationModelHolder(), 2);
        restarted.loadSnapshot();

        assertEquals(1, restarted.queuedPlaces());
    }

    @Test
    void onlyOneRefreshRunsAtATime() {
        NavigationModelHolder holder = new NavigationModelHolder();
        var pending = new java.util.concurrent.CompletableFuture<List<net.knightsandkings.knk.core.domain.domains.KnkDomainSummary>>();
        NavigationModelRefresher.Source slow = new NavigationModelRefresher.Source() {
            @Override
            public java.util.concurrent.CompletableFuture<List<net.knightsandkings.knk.core.domain.domains.KnkDomainSummary>> domains() {
                api.domainCalls.incrementAndGet();
                return pending;
            }

            @Override
            public java.util.concurrent.CompletableFuture<List<net.knightsandkings.knk.core.domain.location.KnkLocation>> locations() {
                return api.locations();
            }

            @Override
            public java.util.concurrent.CompletableFuture<CompassSettings> compass() {
                return api.compass();
            }
        };
        NavigationModelRefresher refresher = new NavigationModelRefresher(slow, api, holder, null, Runnable::run, clock, 2);

        var first = refresher.refresh();
        assertFalse(refresher.refresh().join(), "a second call while one runs does nothing");
        assertEquals(1, api.domainCalls.get());
        pending.complete(List.copyOf(api.domains));
        assertTrue(first.join());
        assertNotNull(holder.current());
    }

    /** A clock the test moves. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
