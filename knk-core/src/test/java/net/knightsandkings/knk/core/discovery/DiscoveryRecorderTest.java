package net.knightsandkings.knk.core.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.knightsandkings.knk.core.dataaccess.RetryPolicy;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrant;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryProgressRow;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySource;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySummary;
import net.knightsandkings.knk.core.domain.discovery.KnownDiscovery;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.DiscoveriesApi;

class DiscoveryRecorderTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-09-26T12:00:00Z");

    @TempDir
    Path dir;

    /** Answers every grant with {@code answer}; records what was sent. */
    private static final class FakeApi implements DiscoveriesApi {
        Function<Collection<String>, CompletableFuture<DiscoveryGrantResult>> answer;
        final List<List<String>> sent = new ArrayList<>();
        final List<DiscoverySource> sources = new ArrayList<>();

        @Override
        public CompletableFuture<DiscoveryGrantResult> grant(int userId, Collection<String> wgRegionIds, DiscoverySource source) {
            sent.add(List.copyOf(wgRegionIds));
            sources.add(source);
            return answer.apply(wgRegionIds);
        }

        @Override
        public CompletableFuture<List<KnownDiscovery>> known(int userId) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<Page<DiscoveryProgressRow>> progress(int userId, PagedQuery query) {
            return CompletableFuture.completedFuture(new Page<>(List.of(), 0, 1, 10));
        }

        @Override
        public CompletableFuture<DiscoverySummary> summary(int userId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> reset(Integer actorUserId, int userId, int domainId) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static DiscoveryGrantResult granted(Collection<String> regions) {
        List<DiscoveryGrant> grants = new ArrayList<>();
        int id = 1;
        for (String region : regions) {
            grants.add(new DiscoveryGrant(id++, region, region, "Town", null, "Replay", 1, 0, 1, 1, 0, 1));
        }
        return new DiscoveryGrantResult(grants, List.of(), List.of(), 0, 0, 0, 0, 0, 0, null, null, null, null, 0, 0, 0, null);
    }

    private static CompletableFuture<DiscoveryGrantResult> failing(Throwable error) {
        return CompletableFuture.failedFuture(new RuntimeException("wrapped", error));
    }

    private final FakeApi api = new FakeApi();
    private DiscoverySpool spool;
    private DiscoveryRecorder recorder;

    private void create() {
        spool = new DiscoverySpool(dir, Logger.getLogger("test"));
        recorder = new DiscoveryRecorder(api, RetryPolicy.noRetry(), spool, Logger.getLogger("test"));
    }

    private static List<PendingDiscovery> entries(String... regions) {
        return java.util.Arrays.stream(regions).map(r -> new PendingDiscovery(r, DiscoverySource.REGION_ENTER, T0)).toList();
    }

    @Test
    void deliveredGrantReturnsTheResult() {
        create();
        api.answer = regions -> CompletableFuture.completedFuture(granted(regions));

        DiscoveryRecorder.Outcome outcome = recorder.grant(PLAYER, 7, entries("town_rivia"), DiscoverySource.REGION_ENTER).join();

        assertEquals(DiscoveryRecorder.Status.DELIVERED, outcome.status());
        assertEquals(1, outcome.result().granted().size());
        assertTrue(spool.isEmpty());
    }

    @Test
    void transientFailureIsSpooled() {
        create();
        api.answer = regions -> failing(new ConnectException("refused"));

        DiscoveryRecorder.Outcome outcome = recorder.grant(PLAYER, 7, entries("town_rivia"), DiscoverySource.REGION_ENTER).join();

        assertEquals(DiscoveryRecorder.Status.SPOOLED, outcome.status());
        assertNull(outcome.result());
        assertEquals(1, spool.get(PLAYER).orElseThrow().entries().size());
    }

    @Test
    void serverErrorIsSpooledButA4xxIsDropped() {
        create();
        api.answer = regions -> failing(new ApiException("u", 503, "down", ""));
        assertEquals(DiscoveryRecorder.Status.SPOOLED,
                recorder.grant(PLAYER, 7, entries("a"), DiscoverySource.REGION_ENTER).join().status());

        api.answer = regions -> failing(new ApiException("u", 400, "bad", "{}"));
        assertEquals(DiscoveryRecorder.Status.DROPPED,
                recorder.grant(PLAYER, 7, entries("b"), DiscoverySource.REGION_ENTER).join().status());

        assertEquals(List.of("a"), spool.get(PLAYER).orElseThrow().entries().stream().map(PendingDiscovery::regionId).toList());
        assertTrue(DiscoveryRecorder.isFinalRejection(new ApiException("u", 404, "x", "")));
        assertFalse(DiscoveryRecorder.isFinalRejection(new RuntimeException(new ConnectException())));
    }

    @Test
    void replayDeliversWithSourceReplayAndRemovesTheFile() {
        create();
        spool.add(PLAYER, 7, entries("town_rivia", "district_market"));
        api.answer = regions -> CompletableFuture.completedFuture(granted(regions));

        List<DiscoveryRecorder.Replayed> replayed = recorder.replay().join();

        assertEquals(1, replayed.size());
        assertEquals(7, replayed.get(0).userId());
        assertEquals(2, replayed.get(0).result().granted().size());
        assertEquals(List.of(DiscoverySource.REPLAY), api.sources);
        assertTrue(spool.isEmpty());
    }

    @Test
    void replayKeepsTheFileWhileTheApiIsDownAndDropsRefusedEntries() {
        create();
        spool.add(PLAYER, 7, entries("town_rivia"));

        api.answer = regions -> failing(new ConnectException("refused"));
        assertTrue(recorder.replay().join().isEmpty());
        assertFalse(spool.isEmpty());

        api.answer = regions -> failing(new ApiException("u", 404, "user gone", ""));
        assertTrue(recorder.replay(PLAYER).join().isEmpty());
        assertTrue(spool.isEmpty());
    }

    @Test
    void replaySendsAtMostFiftyIdsPerRequest() {
        create();
        String[] regions = new String[120];
        for (int i = 0; i < regions.length; i++) {
            regions[i] = "r" + i;
        }
        spool.add(PLAYER, 7, entries(regions));
        api.answer = sent -> CompletableFuture.completedFuture(granted(sent));

        assertEquals(3, recorder.replay().join().size());
        assertEquals(List.of(50, 50, 20), api.sent.stream().map(List::size).toList());
        assertTrue(spool.isEmpty());
    }

    @Test
    void grantsInFlightAtShutdownAreSpooled() {
        create();
        CompletableFuture<DiscoveryGrantResult> never = new CompletableFuture<>();
        api.answer = regions -> never;

        recorder.grant(PLAYER, 7, entries("town_rivia"), DiscoverySource.REGION_ENTER);
        recorder.spoolInFlight();

        assertEquals(1, spool.get(PLAYER).orElseThrow().entries().size());
    }
}
