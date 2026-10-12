package net.knightsandkings.knk.paper.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.knightsandkings.knk.core.domain.users.UserIgnore;
import net.knightsandkings.knk.core.offline.OfflineSecurityStore;
import net.knightsandkings.knk.core.ports.api.UserIgnoresApi;

/** KNG-123: while the API can't be asked, the ignore list the API last returned keeps filtering. */
class IgnoreServiceOfflineTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("Alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("Bob".getBytes());
    private static final UUID CAROL = UUID.nameUUIDFromBytes("Carol".getBytes());
    private static final OffsetDateTime DAY1 = OffsetDateTime.of(2026, 9, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC);

    private static final class FakeApi implements UserIgnoresApi {
        final List<String> calls = new ArrayList<>();
        CompletableFuture<List<UserIgnore>> listAnswer = CompletableFuture.completedFuture(List.of());
        CompletableFuture<AddResult> addAnswer = CompletableFuture.completedFuture(AddResult.IGNORED);
        CompletableFuture<Void> removeAnswer = CompletableFuture.completedFuture(null);

        @Override
        public CompletableFuture<List<UserIgnore>> list(int userId) {
            calls.add("list " + userId);
            return listAnswer;
        }

        @Override
        public CompletableFuture<AddResult> add(int userId, int ignoredUserId) {
            calls.add("add " + userId + " " + ignoredUserId);
            return addAnswer;
        }

        @Override
        public CompletableFuture<Void> remove(int userId, int ignoredUserId) {
            calls.add("remove " + userId + " " + ignoredUserId);
            return removeAnswer;
        }
    }

    @TempDir
    Path dir;

    private final FakeApi api = new FakeApi();
    private final Map<UUID, Integer> userIds = new HashMap<>(Map.of(ALICE, 1, BOB, 2, CAROL, 3));
    private final Set<UUID> online = new HashSet<>(Set.of(ALICE, BOB, CAROL));

    private OfflineSecurityStore store() {
        return new OfflineSecurityStore(dir.resolve("offline-security.json"), OfflineSecurityStore.Settings.defaults(), CLOCK);
    }

    private IgnoreService service(OfflineSecurityStore store) {
        return new IgnoreService(api, userIds::get, online::contains, CLOCK, store);
    }

    private static <T> CompletableFuture<T> down() {
        return CompletableFuture.failedFuture(new RuntimeException("connection refused"));
    }

    @Test
    void aLoadedListIsKeptInTheStore() {
        OfflineSecurityStore store = store();
        api.listAnswer = CompletableFuture.completedFuture(List.of(new UserIgnore(2, "Bob", BOB, DAY1)));

        assertTrue(service(store).load(ALICE).join());

        assertEquals(List.of(new UserIgnore(2, "Bob", BOB, DAY1)), store.ignores(ALICE).orElseThrow());
    }

    @Test
    void theApiDownAfterARestart_theLastListStillFilters() {
        OfflineSecurityStore before = store();
        api.listAnswer = CompletableFuture.completedFuture(List.of(new UserIgnore(2, "Bob", BOB, DAY1)));
        service(before).load(ALICE).join();
        before.flushIfDirty();

        OfflineSecurityStore restarted = store();
        restarted.load();
        api.listAnswer = down();
        IgnoreService service = service(restarted);

        assertTrue(service.load(ALICE).join());
        assertTrue(service.isLoaded(ALICE));
        assertTrue(service.ignores(ALICE, BOB));
        assertFalse(service.ignores(ALICE, CAROL));
        assertEquals("Bob", service.find(ALICE, "bob").orElseThrow().ignoredUsername());
    }

    @Test
    void noUserIdAfterARestart_theLastListIsUsedWithoutAskingTheApi() {
        OfflineSecurityStore store = store();
        store.recordIgnores(ALICE, 1, List.of(new UserIgnore(2, "Bob", BOB, DAY1)));
        userIds.remove(ALICE);
        IgnoreService service = service(store);

        assertTrue(service.load(ALICE).join());

        assertTrue(service.ignores(ALICE, BOB));
        assertEquals(List.of(), api.calls);
    }

    @Test
    void theApiAnswersAgain_itsListReplacesTheLastKnownOne() {
        OfflineSecurityStore store = store();
        store.recordIgnores(ALICE, 1, List.of(new UserIgnore(2, "Bob", BOB, DAY1)));
        api.listAnswer = down();
        IgnoreService service = service(store);
        service.load(ALICE).join();

        api.listAnswer = CompletableFuture.completedFuture(List.of(new UserIgnore(3, "Carol", CAROL, DAY1)));
        assertTrue(service.load(ALICE).join());

        assertFalse(service.ignores(ALICE, BOB), "unignored in the web app meanwhile");
        assertTrue(service.ignores(ALICE, CAROL));
        assertEquals(List.of(new UserIgnore(3, "Carol", CAROL, DAY1)), store.ignores(ALICE).orElseThrow());

        service.load(ALICE).join();
        assertEquals(List.of("list 1", "list 1"), api.calls, "the API's list is not asked for again");
    }

    @Test
    void noLastList_failsOpenAsBefore() {
        api.listAnswer = down();
        IgnoreService service = service(store());

        assertFalse(service.load(ALICE).join());
        assertFalse(service.isLoaded(ALICE));
        assertFalse(service.ignores(ALICE, BOB));
    }

    @Test
    void withoutAStore_failsOpenAsBefore() {
        api.listAnswer = down();
        IgnoreService service = service(null);

        assertFalse(service.load(ALICE).join());
        assertFalse(service.ignores(ALICE, BOB));
    }

    @Test
    void aListOlderThanTheMaxAgeIsNotUsed() {
        Clock monthAgo = Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC);
        OfflineSecurityStore old = new OfflineSecurityStore(dir.resolve("offline-security.json"),
            OfflineSecurityStore.Settings.defaults(), monthAgo);
        old.recordIgnores(ALICE, 1, List.of(new UserIgnore(2, "Bob", BOB, DAY1)));
        old.flushIfDirty();
        OfflineSecurityStore store = store();
        store.load();
        api.listAnswer = down();

        assertFalse(service(store).load(ALICE).join());
    }

    @Test
    void anErasedPlayersListIsNotUsed() {
        OfflineSecurityStore store = store();
        store.recordIgnores(ALICE, 1, List.of(new UserIgnore(2, "Bob", BOB, DAY1)));
        store.forgetUser(ALICE);
        api.listAnswer = down();

        assertFalse(service(store).load(ALICE).join());
    }

    @Test
    void aChangeTheApiAcceptedIsKept_aRefusedOneIsNot() {
        OfflineSecurityStore store = store();
        api.listAnswer = CompletableFuture.completedFuture(List.of(new UserIgnore(2, "Bob", BOB, DAY1)));
        IgnoreService service = service(store);
        service.load(ALICE).join();

        service.ignore(ALICE, 3, "Carol", CAROL).join();
        assertEquals(Set.of(2, 3), Set.copyOf(store.ignores(ALICE).orElseThrow().stream().map(UserIgnore::ignoredUserId).toList()));

        service.unignore(ALICE, 2).join();
        assertEquals(List.of(3), store.ignores(ALICE).orElseThrow().stream().map(UserIgnore::ignoredUserId).toList());

        api.removeAnswer = down();
        service.unignore(ALICE, 3).exceptionally(ex -> null).join();
        assertEquals(List.of(3), store.ignores(ALICE).orElseThrow().stream().map(UserIgnore::ignoredUserId).toList(),
            "the API refused: the store keeps the entry");
    }

    @Test
    void aChangeDuringTheOutageIsRolledBack() {
        OfflineSecurityStore store = store();
        store.recordIgnores(ALICE, 1, List.of(new UserIgnore(2, "Bob", BOB, DAY1)));
        api.listAnswer = down();
        api.addAnswer = down();
        IgnoreService service = service(store);
        service.load(ALICE).join();

        service.ignore(ALICE, 3, "Carol", CAROL).exceptionally(ex -> null).join();

        assertFalse(service.ignores(ALICE, CAROL));
        assertTrue(service.ignores(ALICE, BOB));
        assertEquals(List.of(2), store.ignores(ALICE).orElseThrow().stream().map(UserIgnore::ignoredUserId).toList());
    }

    @Test
    void quitDropsTheLastKnownListFromMemoryOnly() {
        OfflineSecurityStore store = store();
        store.recordIgnores(ALICE, 1, List.of(new UserIgnore(2, "Bob", BOB, DAY1)));
        api.listAnswer = down();
        IgnoreService service = service(store);
        service.load(ALICE).join();

        service.forget(ALICE);

        assertFalse(service.isLoaded(ALICE));
        assertTrue(store.ignores(ALICE).isPresent());
    }

    @Test
    void aPlayerWhoQuitIsNotGivenTheLastKnownList() {
        OfflineSecurityStore store = store();
        store.recordIgnores(ALICE, 1, List.of(new UserIgnore(2, "Bob", BOB, DAY1)));
        api.listAnswer = down();
        online.remove(ALICE);

        assertFalse(service(store).load(ALICE).join());
    }
}
