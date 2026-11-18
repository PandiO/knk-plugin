package net.knightsandkings.knk.core.navigation.model;

import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.T0;
import static net.knightsandkings.knk.core.navigation.model.ModelFixtures.domain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.knightsandkings.knk.core.domain.domains.KnkDomainSummary;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;

/**
 * Off-road phase 5.1 (PandiO/knk-workspace#708, destination engine E16): the navigation model's landmarks section is
 * filled from the source's landmarks, written to and read from the snapshot, and kept when the landmark read fails
 * (an API from before #708, or a hiccup) without failing the rest of the refresh.
 */
class NavigationModelLandmarksTest {

    @TempDir
    Path dir;

    private final ModelFixtures.FakeApi api = new ModelFixtures.FakeApi();
    private List<Landmark> landmarks = new ArrayList<>();
    private boolean landmarksFail;
    private boolean landmarksOff;

    private final Landmark throne = new Landmark(41, "Throne room", "world", 12, 70, 30, Landmark.Extent.REGION, "keep_throne",
        null, 9, "Keep", true);
    private final Landmark well = new Landmark(42, "Old well", "world", 5, 64, 5, Landmark.Extent.RADIUS, null, 4.0, null,
        null, false);

    /** The fake API plus a landmark read. */
    private final NavigationModelRefresher.Source source = new NavigationModelRefresher.Source() {
        @Override
        public CompletableFuture<List<KnkDomainSummary>> domains() {
            return api.domains();
        }

        @Override
        public CompletableFuture<List<KnkLocation>> locations() {
            return api.locations();
        }

        @Override
        public CompletableFuture<CompassSettings> compass() {
            return api.compass();
        }

        @Override
        public CompletableFuture<List<Landmark>> landmarks() {
            if (landmarksOff) {
                return NavigationModelRefresher.Source.super.landmarks();
            }
            return landmarksFail ? CompletableFuture.failedFuture(new IllegalStateException("404 Not Found"))
                : CompletableFuture.completedFuture(List.copyOf(landmarks));
        }
    };

    @BeforeEach
    void setUp() {
        api.domains.add(domain(9, "Keep", "Structure", "Applies", "domain_9", null));
        landmarks.add(well);
        landmarks.add(throne);
    }

    private NavigationModelSnapshot snapshot() {
        return new NavigationModelSnapshot(dir.resolve(NavigationModelSnapshot.RELATIVE_PATH));
    }

    private NavigationModelRefresher refresher(NavigationModelHolder holder) {
        return new NavigationModelRefresher(source, api, holder, snapshot(), Runnable::run, Clock.fixed(T0, ZoneOffset.UTC), 2);
    }

    @Test
    void aRefreshFillsTheLandmarksSection() {
        NavigationModelHolder holder = new NavigationModelHolder();
        assertTrue(refresher(holder).refresh().join());
        assertEquals(List.of(throne, well), holder.current().landmarks(), "by id");
    }

    @Test
    void withTheFlagOffTheSectionStaysEmpty() {
        landmarksOff = true;
        NavigationModelHolder holder = new NavigationModelHolder();
        assertTrue(refresher(holder).refresh().join());
        assertEquals(List.of(), holder.current().landmarks());
    }

    @Test
    void aFailedLandmarkReadKeepsTheLastLandmarksAndTheRestRefreshes() {
        NavigationModelHolder holder = new NavigationModelHolder();
        NavigationModelRefresher refresher = refresher(holder);
        refresher.refresh().join();

        landmarksFail = true;
        api.domains.add(domain(10, "Gatehouse", "Structure", "Applies", "domain_10", null));
        assertTrue(refresher.refresh().join(), "the domains still refresh");
        assertEquals(2, holder.current().domains().size());
        assertEquals(List.of(throne, well), holder.current().landmarks());
    }

    @Test
    void aDeletedLandmarkLeavesTheModelOnTheNextRefresh() {
        NavigationModelHolder holder = new NavigationModelHolder();
        NavigationModelRefresher refresher = refresher(holder);
        refresher.refresh().join();
        landmarks.remove(throne);
        assertTrue(refresher.refresh().join(), "a changed landmark list is a changed model");
        assertEquals(List.of(well), holder.current().landmarks());
        assertFalse(refresher.refresh().join(), "unchanged");
    }

    @Test
    void theSnapshotKeepsTheLandmarksAcrossARestart() {
        refresher(new NavigationModelHolder()).refresh().join();

        NavigationModelHolder restarted = new NavigationModelHolder();
        assertEquals(NavigationModelSnapshot.Loaded.Status.LOADED, refresher(restarted).loadSnapshot().status());
        assertEquals(List.of(throne, well), restarted.current().landmarks());
        Optional<Landmark> loaded = restarted.current().landmarks().stream().filter(l -> l.id() == 42).findFirst();
        assertEquals(4.0, loaded.orElseThrow().radius());
    }
}
