package net.knightsandkings.knk.core.teleport;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference.SourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /spawn destination (docs/specs/teleport/DESIGN.md §3.6, Phase 4): per source type, fallbacks, caching. */
class SpawnPointResolverTest {

    private static final KnkLocation SNAPSHOT = new KnkLocation(9, "old", 1.0, 64.0, 1.0, 0f, 0f, "world");

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AtomicInteger settingsReads = new AtomicInteger();
    private CompletableFuture<KnkGameSettings> nextSettings = CompletableFuture.completedFuture(worldSpawnSettings());
    /** Locations each lookup knows, by source type then id. */
    private final Map<SourceType, Map<Integer, KnkLocation>> known = new HashMap<>();
    private final List<String> lookedUp = new ArrayList<>();

    private final SpawnPointResolver resolver = new SpawnPointResolver(
        () -> {
            settingsReads.incrementAndGet();
            return nextSettings;
        },
        lookup(SourceType.LOCATION), lookup(SourceType.TOWN), lookup(SourceType.DISTRICT), lookup(SourceType.STRUCTURE),
        now::get, Duration.ofMinutes(5));

    private SpawnPointResolver.LocationLookup lookup(SourceType type) {
        return id -> {
            lookedUp.add(type + ":" + id);
            return CompletableFuture.completedFuture(Optional.ofNullable(known.getOrDefault(type, Map.of()).get(id)));
        };
    }

    private void know(SourceType type, int id, KnkLocation location) {
        known.computeIfAbsent(type, t -> new HashMap<>()).put(id, location);
    }

    private static KnkLocation at(String world, double x, double y, double z) {
        return new KnkLocation(1, "spot", x, y, z, 90f, 5f, world);
    }

    private static KnkGameSettings worldSpawnSettings() {
        return new KnkGameSettings("WorldSpawn", null);
    }

    private static KnkGameSettings custom(SourceType type, int id, KnkLocation snapshot) {
        return new KnkGameSettings("CustomReference", new KnkSpawnReference(type, id, "Town: Kardenna", snapshot));
    }

    private SpawnPoint resolve() {
        return resolver.resolve().join();
    }

    // ===== sources =====

    @Test
    void worldSpawnModeUsesTheMainWorldSpawn() {
        SpawnPoint point = resolve();

        assertTrue(point.isWorldSpawn());
        assertEquals(SpawnPoint.Source.WORLD_SPAWN, point.source());
        assertTrue(lookedUp.isEmpty());
    }

    @Test
    void referenceIgnoredOutsideCustomReferenceMode() {
        nextSettings = CompletableFuture.completedFuture(new KnkGameSettings("WorldSpawn",
            new KnkSpawnReference(SourceType.TOWN, 4, "Town: Kardenna", SNAPSHOT)));

        assertTrue(resolve().isWorldSpawn());
        assertTrue(lookedUp.isEmpty());
    }

    @Test
    void customModeWithoutAReferenceUsesTheWorldSpawn() {
        nextSettings = CompletableFuture.completedFuture(new KnkGameSettings("CustomReference", null));

        assertTrue(resolve().isWorldSpawn());
    }

    @ParameterizedTest
    @EnumSource(SourceType.class)
    void eachSourceTypeIsLookedUpThroughItsOwnGateway(SourceType type) {
        KnkLocation spot = at("world", 100.5, 70, -20.5);
        know(type, 4, spot);
        nextSettings = CompletableFuture.completedFuture(custom(type, 4, SNAPSHOT));

        SpawnPoint point = resolve();

        assertSame(spot, point.location());
        assertEquals(SpawnPoint.Source.REFERENCE, point.source());
        assertEquals("Town: Kardenna", point.label());
        assertEquals(List.of(type + ":4"), lookedUp);
    }

    @Test
    void modeIsCaseInsensitive() {
        know(SourceType.TOWN, 4, at("world", 5, 64, 5));
        nextSettings = CompletableFuture.completedFuture(new KnkGameSettings("customreference",
            new KnkSpawnReference(SourceType.TOWN, 4, null, null)));

        SpawnPoint point = resolve();

        assertEquals(SpawnPoint.Source.REFERENCE, point.source());
        assertEquals("Town #4", point.label());
    }

    // ===== fallbacks =====

    @Test
    void missingReferenceFallsBackToTheSavedCoordinates() {
        nextSettings = CompletableFuture.completedFuture(custom(SourceType.TOWN, 4, SNAPSHOT));

        SpawnPoint point = resolve();

        assertSame(SNAPSHOT, point.location());
        assertEquals(SpawnPoint.Source.SNAPSHOT, point.source());
    }

    @Test
    void missingReferenceWithoutSavedCoordinatesFallsBackToTheWorldSpawn() {
        nextSettings = CompletableFuture.completedFuture(custom(SourceType.STRUCTURE, 4, null));

        assertTrue(resolve().isWorldSpawn());
    }

    @Test
    void locationWithoutAWorldOrCoordinatesIsNotUsed() {
        know(SourceType.TOWN, 4, new KnkLocation(1, "broken", 1.0, 64.0, 1.0, 0f, 0f, null));
        know(SourceType.TOWN, 5, new KnkLocation(1, "broken", null, 64.0, 1.0, 0f, 0f, "world"));
        know(SourceType.TOWN, 6, new KnkLocation(1, "broken", Double.NaN, 64.0, 1.0, 0f, 0f, "world"));
        know(SourceType.TOWN, 7, new KnkLocation(1, "broken", 40_000_000.0, 64.0, 1.0, 0f, 0f, "world"));

        for (int id = 4; id <= 7; id++) {
            resolver.invalidate();
            nextSettings = CompletableFuture.completedFuture(custom(SourceType.TOWN, id, null));
            assertTrue(resolve().isWorldSpawn(), "town " + id);
        }
    }

    @Test
    void failedLookupFallsBackToTheSavedCoordinates() {
        SpawnPointResolver failing = new SpawnPointResolver(
            () -> CompletableFuture.completedFuture(custom(SourceType.DISTRICT, 3, SNAPSHOT)),
            id -> CompletableFuture.failedFuture(new RuntimeException("down")),
            id -> { throw new IllegalStateException("boom"); },
            id -> CompletableFuture.failedFuture(new RuntimeException("down")),
            id -> null,
            now::get, Duration.ofMinutes(5));

        SpawnPoint point = failing.resolve().join();

        assertEquals(SpawnPoint.Source.SNAPSHOT, point.source());
    }

    @Test
    void unknownSourceTypeFallsBackToTheSavedCoordinates() {
        nextSettings = CompletableFuture.completedFuture(new KnkGameSettings("CustomReference",
            new KnkSpawnReference(null, 4, "Plot: 4", SNAPSHOT)));

        assertEquals(SpawnPoint.Source.SNAPSHOT, resolve().source());
        assertTrue(lookedUp.isEmpty());
    }

    @Test
    void unreadableSettingsFallBackToTheWorldSpawnAndRetrySoon() {
        nextSettings = CompletableFuture.failedFuture(new RuntimeException("API down"));

        assertTrue(resolve().isWorldSpawn());
        resolve();
        assertEquals(1, settingsReads.get());

        now.addAndGet(SpawnPointResolver.FAILURE_RETRY_MILLIS);
        resolve();
        assertEquals(2, settingsReads.get());
    }

    @Test
    void unreadableSettingsKeepTheLastKnownSpawn() {
        know(SourceType.TOWN, 4, at("world", 5, 64, 5));
        nextSettings = CompletableFuture.completedFuture(custom(SourceType.TOWN, 4, null));
        SpawnPoint first = resolve();

        now.addAndGet(Duration.ofMinutes(5).toMillis());
        nextSettings = CompletableFuture.failedFuture(new RuntimeException("API down"));

        assertSame(first, resolve());
    }

    @Test
    void settingsReaderThatThrowsStillResolves() {
        SpawnPointResolver throwing = new SpawnPointResolver(() -> { throw new IllegalStateException("no client"); },
            lookup(SourceType.LOCATION), lookup(SourceType.TOWN), lookup(SourceType.DISTRICT), lookup(SourceType.STRUCTURE),
            now::get, Duration.ofMinutes(5));

        assertTrue(throwing.resolve().join().isWorldSpawn());
    }

    // ===== caching =====

    @Test
    void resolvedSpawnIsCachedForTheTtl() {
        resolve();
        now.addAndGet(Duration.ofMinutes(5).toMillis() - 1);
        resolve();
        assertEquals(1, settingsReads.get());

        now.addAndGet(1);
        resolve();
        assertEquals(2, settingsReads.get());
    }

    @Test
    void invalidateReadsTheSettingsAgain() {
        resolve();
        know(SourceType.LOCATION, 2, at("world", 7, 80, 7));
        nextSettings = CompletableFuture.completedFuture(custom(SourceType.LOCATION, 2, null));

        resolver.invalidate();

        assertEquals(SpawnPoint.Source.REFERENCE, resolve().source());
        assertEquals(2, settingsReads.get());
    }

    @Test
    void concurrentCallersShareOneRead() {
        CompletableFuture<KnkGameSettings> pending = new CompletableFuture<>();
        nextSettings = pending;

        CompletableFuture<SpawnPoint> a = resolver.resolve();
        CompletableFuture<SpawnPoint> b = resolver.resolve();
        assertFalse(a.isDone());
        pending.complete(worldSpawnSettings());

        assertSame(a.join(), b.join());
        assertEquals(1, settingsReads.get());
        resolve();
        assertEquals(1, settingsReads.get());
    }

    @Test
    void readStartedBeforeAnInvalidateIsNotCached() {
        CompletableFuture<KnkGameSettings> stale = new CompletableFuture<>();
        nextSettings = stale;
        CompletableFuture<SpawnPoint> before = resolver.resolve();

        resolver.invalidate();
        stale.complete(worldSpawnSettings());
        before.join();

        know(SourceType.LOCATION, 2, at("world", 7, 80, 7));
        nextSettings = CompletableFuture.completedFuture(custom(SourceType.LOCATION, 2, null));
        assertEquals(SpawnPoint.Source.REFERENCE, resolve().source());
    }

    // ===== reference parsing =====

    @Test
    void sourceTypeParsesTheApiNames() {
        assertEquals(SourceType.LOCATION, SourceType.parse("Location"));
        assertEquals(SourceType.TOWN, SourceType.parse("town"));
        assertEquals(SourceType.DISTRICT, SourceType.parse(" District "));
        assertEquals(SourceType.STRUCTURE, SourceType.parse("STRUCTURE"));
        assertNull(SourceType.parse("Street"));
        assertNull(SourceType.parse(null));
        assertNull(SourceType.parse(""));
    }
}
