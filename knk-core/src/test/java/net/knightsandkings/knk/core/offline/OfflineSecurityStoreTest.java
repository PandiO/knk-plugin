package net.knightsandkings.knk.core.offline;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.offline.OfflineIdentityVerifier.Result;
import net.knightsandkings.knk.core.offline.OfflineSecurityStore.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-58: the last-known security state, used only while the API can't be reached. */
class OfflineSecurityStoreTest {

    @TempDir
    Path dir;

    private Instant now = Instant.parse("2026-10-08T12:00:00Z");
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

    private final UUID alice = UUID.randomUUID();

    private static UserSummary user(int id, UUID uuid, ActiveMode mode, boolean frozen) {
        return new UserSummary(id, "Alice", uuid, "alice@example.test", 100, 5, 0, true, false,
            GatePassThroughMethod.DEFAULT, mode, null, null, 0, null, null, null, frozen, frozen ? "griefing" : null);
    }

    private OfflineSecurityStore store() {
        return new OfflineSecurityStore(dir.resolve("offline-security.json"), Settings.defaults(), clock);
    }

    @Test
    void anAccountAndItsPermissionAnswersSurviveARestart() {
        OfflineSecurityStore store = store();
        store.recordUser(user(7, alice, ActiveMode.STAFF, true));
        store.recordPermission(7, "knk.region.bypass", true);
        store.recordPermission(7, "knk.mode.owner", false);
        store.flushIfDirty();

        OfflineSecurityStore restarted = store();
        restarted.load();

        OfflineSecurityStore.Identity identity = restarted.identity(alice).orElseThrow();
        assertEquals(7, identity.userId());
        assertEquals(ActiveMode.STAFF, identity.activeMode());
        assertTrue(identity.frozen());
        assertEquals("griefing", identity.frozenReason());
        assertEquals(Boolean.TRUE, restarted.permission(7, "knk.region.bypass").orElseThrow());
        assertEquals(Boolean.FALSE, restarted.permission(7, "knk.mode.owner").orElseThrow());
    }

    @Test
    void onlyWhatEnforcementNeedsIsWrittenNoEmailOrBalances() throws Exception {
        OfflineSecurityStore store = store();
        store.recordUser(user(7, alice, ActiveMode.NONE, false));
        store.flushIfDirty();

        String json = Files.readString(dir.resolve("offline-security.json"));
        assertFalse(json.contains("alice@example.test"));
        assertFalse(json.contains("coins"));
    }

    @Test
    void entriesPastTheirMaxAgeAreUnknownAndPruned() {
        OfflineSecurityStore store = store();
        store.recordUser(user(7, alice, ActiveMode.NONE, false));
        store.recordPermission(7, "knk.freeze", true);

        now = now.plus(Duration.ofHours(73));
        assertTrue(store.permission(7, "knk.freeze").isEmpty(), "a 3-day-old grant is not trusted");
        assertTrue(store.identity(alice).isPresent());

        now = now.plus(Duration.ofDays(30));
        assertTrue(store.identity(alice).isEmpty(), "past the 30-day erasure deadline");
        assertEquals(2, store.prune());
    }

    @Test
    void forgettingAPlayerDeletesTheirIdentityAndPermissions() {
        OfflineSecurityStore store = store();
        store.recordUser(user(7, alice, ActiveMode.NONE, false));
        store.recordPermission(7, "knk.freeze", true);

        store.forgetUser(alice);

        assertTrue(store.identity(alice).isEmpty());
        assertTrue(store.permission(7, "knk.freeze").isEmpty());
    }

    @Test
    void aNewAccountForTheSameUuidReplacesTheOldOneAndItsPermissions() {
        OfflineSecurityStore store = store();
        store.recordUser(user(7, alice, ActiveMode.OWNER, false));
        store.recordPermission(7, "knk.mode.owner", true);

        store.recordUser(user(42, alice, ActiveMode.NONE, false));

        assertEquals(42, store.identity(alice).orElseThrow().userId());
        assertTrue(store.permission(7, "knk.mode.owner").isEmpty(), "never resurrected for the returning player");
    }

    @Test
    void freezeAndModeChangesMadeOnThisServerAreKept() {
        OfflineSecurityStore store = store();
        store.recordUser(user(7, alice, ActiveMode.NONE, false));

        store.setFrozen(alice, true, "spam");
        store.setActiveMode(alice, ActiveMode.STAFF);

        OfflineSecurityStore.Identity identity = store.identity(alice).orElseThrow();
        assertTrue(identity.frozen());
        assertEquals("spam", identity.frozenReason());
        assertEquals(ActiveMode.STAFF, identity.activeMode());
        store.setFrozen(alice, false, "ignored");
        assertFalse(store.identity(alice).orElseThrow().frozen());
    }

    @Test
    void aCorruptFileIsMovedAsideNotFatal() throws Exception {
        Files.writeString(dir.resolve("offline-security.json"), "{not json");

        OfflineSecurityStore store = store();
        store.load();

        assertEquals(0, store.identityCount());
        assertTrue(Files.exists(dir.resolve("offline-security.json.corrupt")));
    }

    @Test
    void nothingIsWrittenWhenNothingChanged() {
        OfflineSecurityStore store = store();
        store.flushIfDirty();

        assertFalse(Files.exists(dir.resolve("offline-security.json")));
    }

    // ---- OfflineIdentityVerifier ----

    @Test
    void theVerifierDeletesAnErasedAccountAndRefreshesTheRest() {
        OfflineSecurityStore store = store();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();
        store.recordUser(user(7, alice, ActiveMode.NONE, false));
        store.recordUser(user(8, bob, ActiveMode.NONE, false));
        store.recordUser(user(9, carol, ActiveMode.NONE, false));
        store.recordPermission(8, "knk.freeze", true);
        now = now.plus(Duration.ofHours(7));

        Map<UUID, UserSummary> api = new HashMap<>();
        api.put(alice, user(7, alice, ActiveMode.STAFF, false));   // still there, now in staff mode
        // bob: erased - the API knows no account for his UUID any more
        api.put(carol, user(10, carol, ActiveMode.NONE, false));   // returned after erasure: a new account
        Result result = new OfflineIdentityVerifier(store, uuid -> CompletableFuture.completedFuture(api.get(uuid)))
            .run(Duration.ofHours(6));

        assertEquals(3, result.checked());
        assertEquals(ActiveMode.STAFF, store.identity(alice).orElseThrow().activeMode());
        assertTrue(store.identity(bob).isEmpty());
        assertTrue(store.permission(8, "knk.freeze").isEmpty());
        assertEquals(10, store.identity(carol).orElseThrow().userId());
        assertEquals(2, result.forgotten());
    }

    @Test
    void theVerifierChangesNothingWhileTheApiIsDown() {
        OfflineSecurityStore store = store();
        store.recordUser(user(7, alice, ActiveMode.NONE, false));
        now = now.plus(Duration.ofHours(7));

        Result result = new OfflineIdentityVerifier(store,
            uuid -> CompletableFuture.failedFuture(new RuntimeException("connection refused"))).run(Duration.ofHours(6));

        assertTrue(result.apiUnreachable());
        assertTrue(store.identity(alice).isPresent());
    }

    @Test
    void recentlyConfirmedIdentitiesAreNotRechecked() {
        OfflineSecurityStore store = store();
        store.recordUser(user(7, alice, ActiveMode.NONE, false));

        Result result = new OfflineIdentityVerifier(store, uuid -> {
            throw new AssertionError("no lookup expected");
        }).run(Duration.ofHours(6));

        assertEquals(0, result.checked());
    }
}
