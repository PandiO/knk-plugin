package net.knightsandkings.knk.core.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.discovery.DiscoveryTracker.Batch;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrant;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySkip;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySource;
import net.knightsandkings.knk.core.domain.discovery.KnownDiscovery;

class DiscoveryTrackerTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-09-26T12:00:00Z");

    private DiscoveryTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new DiscoveryTracker(50, 3, Duration.ofMinutes(10), Duration.ofSeconds(60));
        tracker.startSession(PLAYER, 7);
    }

    private static DiscoveryGrantResult result(List<DiscoveryGrant> granted, List<Integer> already, List<DiscoverySkip> skipped) {
        return new DiscoveryGrantResult(granted, already, skipped, 0, 0, 0, 0, 0, 0, null, null, null, null, 0, 0, 0, null);
    }

    private static DiscoveryGrant grant(int id, String region, String type) {
        return new DiscoveryGrant(id, region, "D" + id, type, null, "RegionEnter", 10, 1, 5, 10, 1, 5);
    }

    @Test
    void knownRegionsAreNotCandidatesCaseInsensitively() {
        tracker.knownLoaded(PLAYER, List.of(new KnownDiscovery(1, "town_rivia")));

        assertFalse(tracker.isCandidate(PLAYER, "Town_Rivia", T0));
        assertFalse(tracker.offer(PLAYER, "town_rivia", DiscoverySource.REGION_ENTER, T0));
        assertTrue(tracker.offer(PLAYER, "district_market", DiscoverySource.REGION_ENTER, T0));
        assertFalse(tracker.isCandidate(PLAYER, "district_market", T0), "already pending");
    }

    @Test
    void playersWithoutASessionHaveNoCandidates() {
        UUID other = UUID.randomUUID();
        assertFalse(tracker.isCandidate(other, "town_rivia", T0));
        assertFalse(tracker.offer(other, "town_rivia", DiscoverySource.REGION_ENTER, T0));
    }

    @Test
    void candidatesQueuedBeforeTheKnownSetLoadsWaitAndAreFilteredWhenItArrives() {
        tracker.offer(PLAYER, "town_rivia", DiscoverySource.JOIN_INSIDE, T0);
        tracker.offer(PLAYER, "district_market", DiscoverySource.JOIN_INSIDE, T0);

        assertTrue(tracker.nextBatch(PLAYER, T0).isEmpty(), "nothing is sent before the known set loaded");

        tracker.knownLoaded(PLAYER, List.of(new KnownDiscovery(1, "TOWN_RIVIA")));
        Batch batch = tracker.nextBatch(PLAYER, T0).orElseThrow();

        assertEquals(List.of("district_market"), batch.regionIds());
        assertEquals(DiscoverySource.JOIN_INSIDE, batch.source());
        assertEquals(7, batch.userId());
    }

    @Test
    void aFailedKnownLoadStillSendsCandidates() {
        tracker.offer(PLAYER, "town_rivia", DiscoverySource.REGION_ENTER, T0);
        tracker.knownLoadFailed(PLAYER);

        assertEquals(List.of("town_rivia"), tracker.nextBatch(PLAYER, T0).orElseThrow().regionIds());
    }

    @Test
    void batchesHoldAtMostFiftyIdsOfOneSource() {
        tracker.knownLoaded(PLAYER, List.of());
        tracker.offer(PLAYER, "join_region", DiscoverySource.JOIN_INSIDE, T0);
        for (int i = 0; i < 60; i++) {
            tracker.offer(PLAYER, "r" + i, DiscoverySource.REGION_ENTER, T0);
        }

        Batch first = tracker.nextBatch(PLAYER, T0).orElseThrow();
        assertEquals(List.of("join_region"), first.regionIds(), "the oldest candidate's source goes first");
        tracker.completed(first, result(List.of(), List.of(), List.of()), T0);

        Batch second = tracker.nextBatch(PLAYER, T0).orElseThrow();
        assertEquals(50, second.entries().size());
        assertEquals(DiscoverySource.REGION_ENTER, second.source());
        tracker.completed(second, result(List.of(), List.of(), List.of()), T0);

        assertEquals(10, tracker.nextBatch(PLAYER, T0).orElseThrow().entries().size());
    }

    @Test
    void onlyOneRequestPerPlayerIsInFlight() {
        tracker.knownLoaded(PLAYER, List.of());
        tracker.offer(PLAYER, "a", DiscoverySource.REGION_ENTER, T0);
        Batch batch = tracker.nextBatch(PLAYER, T0).orElseThrow();
        tracker.offer(PLAYER, "b", DiscoverySource.REGION_ENTER, T0);

        assertFalse(tracker.isCandidate(PLAYER, "a", T0), "in flight");
        assertTrue(tracker.nextBatch(PLAYER, T0).isEmpty());

        tracker.completed(batch, result(List.of(), List.of(), List.of()), T0);
        assertEquals(List.of("b"), tracker.nextBatch(PLAYER, T0).orElseThrow().regionIds());
    }

    @Test
    void requestsPerMinuteAreLimited() {
        tracker.knownLoaded(PLAYER, List.of());
        for (int i = 0; i < 3; i++) {
            tracker.offer(PLAYER, "r" + i, DiscoverySource.REGION_ENTER, T0);
            tracker.completed(tracker.nextBatch(PLAYER, T0).orElseThrow(), result(List.of(), List.of(), List.of()), T0);
        }
        tracker.offer(PLAYER, "r3", DiscoverySource.REGION_ENTER, T0);

        assertTrue(tracker.nextBatch(PLAYER, T0.plusSeconds(59)).isEmpty(), "3 per minute used up");
        assertEquals(List.of("r3"), tracker.nextBatch(PLAYER, T0.plusSeconds(61)).orElseThrow().regionIds());
    }

    @Test
    void grantedAlreadyAndDisabledBecomeKnownIncludingGrantedAncestorRegions() {
        tracker.knownLoaded(PLAYER, List.of());
        tracker.offer(PLAYER, "district_market", DiscoverySource.REGION_ENTER, T0);
        tracker.offer(PLAYER, "structure_smithy", DiscoverySource.REGION_ENTER, T0);
        tracker.offer(PLAYER, "gate_north", DiscoverySource.REGION_ENTER, T0);
        Batch batch = tracker.nextBatch(PLAYER, T0).orElseThrow();

        tracker.completed(batch, result(
                List.of(grant(1, "town_rivia", "Town"), grant(2, "district_market", "District")),
                List.of(3),
                List.of(new DiscoverySkip("gate_north", DiscoverySkip.DISABLED))), T0);

        for (String region : List.of("town_rivia", "district_market", "structure_smithy", "gate_north")) {
            assertFalse(tracker.isCandidate(PLAYER, region, T0.plusSeconds(3600)), region);
        }
        assertEquals(4, tracker.knownCount(PLAYER));
    }

    @Test
    void notADomainIsRememberedForTheTtl() {
        tracker.knownLoaded(PLAYER, List.of());
        tracker.offer(PLAYER, "spawn", DiscoverySource.REGION_ENTER, T0);
        tracker.completed(tracker.nextBatch(PLAYER, T0).orElseThrow(),
                result(List.of(), List.of(), List.of(new DiscoverySkip("spawn", DiscoverySkip.NOT_A_DOMAIN))), T0);

        assertFalse(tracker.isCandidate(PLAYER, "spawn", T0.plus(Duration.ofMinutes(9))));
        assertTrue(tracker.isCandidate(PLAYER, "spawn", T0.plus(Duration.ofMinutes(11))));
    }

    @Test
    void rateLimitedGoesBackToPendingAfterTheDelay() {
        tracker.knownLoaded(PLAYER, List.of());
        tracker.offer(PLAYER, "town_rivia", DiscoverySource.REGION_ENTER, T0);
        tracker.completed(tracker.nextBatch(PLAYER, T0).orElseThrow(),
                result(List.of(), List.of(), List.of(new DiscoverySkip("town_rivia", DiscoverySkip.RATE_LIMITED))), T0);

        assertFalse(tracker.isCandidate(PLAYER, "town_rivia", T0), "pending again");
        assertTrue(tracker.nextBatch(PLAYER, T0.plusSeconds(30)).isEmpty(), "not before the delay");
        assertEquals(List.of("town_rivia"), tracker.nextBatch(PLAYER, T0.plusSeconds(61)).orElseThrow().regionIds());
    }

    @Test
    void deferredIdsAreNotSentAgainUntilReplayed() {
        tracker.knownLoaded(PLAYER, List.of());
        tracker.offer(PLAYER, "town_rivia", DiscoverySource.REGION_ENTER, T0);
        Batch batch = tracker.nextBatch(PLAYER, T0).orElseThrow();
        tracker.deferred(batch);

        assertFalse(tracker.isCandidate(PLAYER, "town_rivia", T0));
        assertTrue(tracker.playersWithPending().isEmpty());

        tracker.replayed(PLAYER, 7, batch.entries(), result(List.of(grant(1, "town_rivia", "Town")), List.of(), List.of()), T0);
        assertFalse(tracker.isCandidate(PLAYER, "town_rivia", T0), "known after the replay");
        assertEquals(1, tracker.knownCount(PLAYER));
    }

    @Test
    void endSessionReturnsPendingCandidatesAndForgetsThePlayer() {
        tracker.offer(PLAYER, "town_rivia", DiscoverySource.JOIN_INSIDE, T0);

        List<PendingDiscovery> pending = tracker.endSession(PLAYER);

        assertEquals(1, pending.size());
        assertEquals("town_rivia", pending.get(0).regionId());
        assertEquals(T0, pending.get(0).discoveredAt());
        assertFalse(tracker.hasSession(PLAYER));
    }

    @Test
    void aResponseForAnEndedSessionIsIgnored() {
        tracker.knownLoaded(PLAYER, List.of());
        tracker.offer(PLAYER, "town_rivia", DiscoverySource.REGION_ENTER, T0);
        Batch batch = tracker.nextBatch(PLAYER, T0).orElseThrow();
        tracker.endSession(PLAYER);
        tracker.startSession(PLAYER, 7);

        tracker.completed(batch, result(List.of(grant(1, "town_rivia", "Town")), List.of(), List.of()), T0);

        assertTrue(tracker.isCandidate(PLAYER, "town_rivia", T0), "the new session starts clean");
    }
}
