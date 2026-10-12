package net.knightsandkings.knk.core.offline;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.UserIgnore;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.offline.OfflineSecurityStore.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-123: ignore lists in the offline security cache - fallback only, bounded age, deleted on erasure. */
class OfflineIgnoreListsTest {

    @TempDir
    Path dir;

    private Instant now = Instant.parse("2026-10-12T12:00:00Z");
    private final Clock clock = new Clock() {
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
    };

    private static final OffsetDateTime DAY1 = OffsetDateTime.of(2026, 9, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();
    private final UserIgnore ignoresBob = new UserIgnore(2, "Bob", bob, DAY1);
    private final UserIgnore ignoresCarol = new UserIgnore(3, "Carol", carol, null);

    private OfflineSecurityStore store() {
        return new OfflineSecurityStore(dir.resolve("offline-security.json"), Settings.defaults(), clock);
    }

    private static UserSummary user(int id, UUID uuid) {
        return new UserSummary(id, "Alice", uuid, "alice@example.test", 100, 5, 0, true, false,
            GatePassThroughMethod.DEFAULT, ActiveMode.NONE, null, null, 0, null, null, null, false, null);
    }

    @Test
    void aListSurvivesARestart() {
        OfflineSecurityStore store = store();
        store.recordIgnores(alice, 1, List.of(ignoresBob, ignoresCarol));
        store.flushIfDirty();

        OfflineSecurityStore restarted = store();
        restarted.load();

        assertEquals(List.of(ignoresBob, ignoresCarol), restarted.ignores(alice).orElseThrow());
        assertEquals(1, restarted.ignoreListCount());
    }

    @Test
    void anEmptyListIsKnownToo() {
        OfflineSecurityStore store = store();
        store.recordIgnores(alice, 1, List.of());

        assertEquals(List.of(), store.ignores(alice).orElseThrow());
        assertTrue(store.ignores(bob).isEmpty(), "never returned by the API: unknown");
    }

    @Test
    void theFileHoldsOnlyTheListFields() throws Exception {
        OfflineSecurityStore store = store();
        store.recordIgnores(alice, 1, List.of(ignoresBob));
        store.flushIfDirty();

        String json = Files.readString(dir.resolve("offline-security.json"));
        assertTrue(json.contains("\"ignores\""));
        assertTrue(json.contains("\"ignoredUsername\":\"Bob\""));
        assertFalse(json.contains("email"));
    }

    @Test
    void aFileWithoutListsFromTheOlderPluginStillLoads() throws Exception {
        Files.writeString(dir.resolve("offline-security.json"), "{\"version\":1,\"identities\":[{\"uuid\":\"" + alice
            + "\",\"userId\":7,\"username\":\"Alice\",\"activeMode\":\"NONE\",\"frozen\":false,\"frozenReason\":null,"
            + "\"verifiedAt\":\"2026-10-12T11:00:00Z\"}],\"permissions\":[]}");

        OfflineSecurityStore store = store();
        store.load();

        assertEquals(7, store.identity(alice).orElseThrow().userId());
        assertEquals(0, store.ignoreListCount());
    }

    @Test
    void aListOlderThanTheIdentityMaxAgeIsNotUsedAndIsPruned() {
        OfflineSecurityStore store = store();
        store.recordIgnores(alice, 1, List.of(ignoresBob));

        now = now.plus(Duration.ofDays(30)).minusSeconds(1);
        assertTrue(store.ignores(alice).isPresent());

        now = now.plus(Duration.ofDays(1));
        assertTrue(store.ignores(alice).isEmpty());
        assertEquals(1, store.prune());
        assertEquals(0, store.ignoreListCount());
    }

    @Test
    void aChangeMadeHereIsKeptButDoesNotExtendTheMaxAge() {
        OfflineSecurityStore store = store();
        store.recordIgnores(alice, 1, List.of(ignoresBob));

        now = now.plus(Duration.ofDays(20));
        store.updateIgnores(alice, List.of(ignoresBob, ignoresCarol));
        assertEquals(List.of(ignoresBob, ignoresCarol), store.ignores(alice).orElseThrow());

        now = now.plus(Duration.ofDays(11));
        assertTrue(store.ignores(alice).isEmpty(), "still 30 days from the API's list");
    }

    @Test
    void aChangeWithoutAKnownListIsDropped() {
        OfflineSecurityStore store = store();
        store.updateIgnores(alice, List.of(ignoresBob));

        assertTrue(store.ignores(alice).isEmpty());
    }

    @Test
    void erasingTheOwnerByUuidDeletesTheirList() {
        OfflineSecurityStore store = store();
        store.recordUser(user(1, alice));
        store.recordIgnores(alice, 1, List.of(ignoresBob));

        store.forgetUser(alice);

        assertTrue(store.ignores(alice).isEmpty());
        assertEquals(0, store.ignoreListCount());
    }

    @Test
    void erasingTheOwnerByUserIdDeletesTheirList() {
        OfflineSecurityStore store = store();
        store.recordIgnores(alice, 1, List.of(ignoresBob));

        store.forgetUserId(1);

        assertTrue(store.ignores(alice).isEmpty());
    }

    @Test
    void anErasedPlayerIsTakenOffOtherPlayersLists() {
        OfflineSecurityStore store = store();
        store.recordIgnores(alice, 1, List.of(ignoresBob, ignoresCarol));

        store.forgetUser(bob);
        assertEquals(List.of(ignoresCarol), store.ignores(alice).orElseThrow());

        store.forgetUserId(3);
        assertEquals(List.of(), store.ignores(alice).orElseThrow());
    }

    @Test
    void erasureIsWrittenToTheFile() {
        OfflineSecurityStore store = store();
        store.recordIgnores(alice, 1, List.of(ignoresBob));
        store.flushIfDirty();

        store.forgetUser(bob);
        store.flushIfDirty();

        OfflineSecurityStore restarted = store();
        restarted.load();
        assertEquals(List.of(), restarted.ignores(alice).orElseThrow());
    }

    @Test
    void aDifferentAccountForTheUuidDropsTheOldAccountsList() {
        OfflineSecurityStore store = store();
        store.recordUser(user(1, alice));
        store.recordIgnores(alice, 1, List.of(ignoresBob));

        store.recordUser(user(99, alice));

        assertTrue(store.ignores(alice).isEmpty());
    }
}
