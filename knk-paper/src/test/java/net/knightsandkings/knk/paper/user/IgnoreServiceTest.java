package net.knightsandkings.knk.paper.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.users.UserIgnore;
import net.knightsandkings.knk.core.messaging.ParticipantId;
import net.knightsandkings.knk.core.ports.api.UserIgnoresApi;

/**
 * KNG-18 Phase 2: cached ignore lists - load on join, optimistic changes with rollback, fail open
 * while loading (DESIGN.md §3.3.5).
 */
class IgnoreServiceTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("Alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("Bob".getBytes());
    private static final UUID CAROL = UUID.nameUUIDFromBytes("Carol".getBytes());
    private static final OffsetDateTime DAY1 = OffsetDateTime.of(2026, 9, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime DAY2 = OffsetDateTime.of(2026, 9, 2, 12, 0, 0, 0, ZoneOffset.UTC);

    /** Records calls; each call answers with whatever future the test put in place. */
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

    private final FakeApi api = new FakeApi();
    private final Map<UUID, Integer> userIds = new HashMap<>(Map.of(ALICE, 1, BOB, 2, CAROL, 3));
    private final Set<UUID> online = new HashSet<>(Set.of(ALICE, BOB, CAROL));
    private final IgnoreService service = new IgnoreService(api, userIds::get, online::contains,
            Clock.fixed(Instant.parse("2026-09-26T19:04:00Z"), ZoneOffset.UTC));

    private void loadAliceIgnoringBobAndCarol() {
        api.listAnswer = CompletableFuture.completedFuture(List.of(
                new UserIgnore(3, "Carol", CAROL, DAY2), new UserIgnore(2, "Bob", BOB, DAY1)));
        assertTrue(service.load(ALICE).join());
    }

    @Test
    void load_fillsTheList_oldestFirst() {
        loadAliceIgnoringBobAndCarol();

        assertEquals(List.of("list 1"), api.calls);
        assertTrue(service.ignores(ALICE, BOB));
        assertTrue(service.ignores(ParticipantId.player(ALICE), ParticipantId.player(CAROL)));
        assertFalse(service.ignores(BOB, ALICE), "one way only");
        assertEquals(List.of("Bob", "Carol"),
                service.list(ALICE).orElseThrow().stream().map(UserIgnore::ignoredUsername).toList());
        assertEquals(2, service.find(ALICE, "bob").orElseThrow().ignoredUserId());
        assertEquals("Carol", service.find(ALICE, 3).orElseThrow().ignoredUsername());
    }

    @Test
    void load_whenAlreadyLoaded_doesNotCallTheApiAgain() {
        loadAliceIgnoringBobAndCarol();

        assertTrue(service.load(ALICE).join());

        assertEquals(List.of("list 1"), api.calls);
    }

    @Test
    void beforeLoad_nobodyIsIgnored() {
        api.listAnswer = new CompletableFuture<>();
        service.load(ALICE);

        assertFalse(service.isLoaded(ALICE));
        assertFalse(service.ignores(ALICE, BOB));
        assertTrue(service.list(ALICE).isEmpty());
    }

    @Test
    void load_withoutACachedUserId_staysUnloaded() {
        userIds.remove(ALICE);

        assertFalse(service.load(ALICE).join());
        assertFalse(service.isLoaded(ALICE));
        assertTrue(api.calls.isEmpty());
    }

    @Test
    void load_failureOrQuit_staysUnloaded() {
        api.listAnswer = CompletableFuture.failedFuture(new RuntimeException("API down"));
        assertFalse(service.load(ALICE).join());
        assertFalse(service.isLoaded(ALICE));

        CompletableFuture<List<UserIgnore>> late = new CompletableFuture<>();
        api.listAnswer = late;
        CompletableFuture<Boolean> loading = service.load(BOB);
        online.remove(BOB);
        late.complete(List.of(new UserIgnore(1, "Alice", ALICE, DAY1)));
        assertFalse(loading.join());
        assertFalse(service.isLoaded(BOB), "a late answer for a player who quit is dropped");
    }

    @Test
    void forget_dropsTheList() {
        loadAliceIgnoringBobAndCarol();

        service.forget(ALICE);

        assertFalse(service.isLoaded(ALICE));
        assertFalse(service.ignores(ALICE, BOB));
    }

    @Test
    void consoleIsNeverIgnored() {
        loadAliceIgnoringBobAndCarol();

        assertFalse(service.ignores(ParticipantId.player(ALICE), ParticipantId.CONSOLE));
        assertFalse(service.ignores(ParticipantId.CONSOLE, ParticipantId.player(BOB)));
    }

    @Test
    void ignore_appliesAtOnce_andStaysWhenTheApiAgrees() {
        assertTrue(service.load(BOB).join());
        CompletableFuture<UserIgnoresApi.AddResult> answer = new CompletableFuture<>();
        api.addAnswer = answer;

        CompletableFuture<UserIgnoresApi.AddResult> result = service.ignore(BOB, 1, "Alice", ALICE);
        assertTrue(service.ignores(BOB, ALICE), "optimistic");

        answer.complete(UserIgnoresApi.AddResult.IGNORED);
        assertEquals(UserIgnoresApi.AddResult.IGNORED, result.join());
        assertTrue(service.ignores(BOB, ALICE));
        assertEquals(List.of("list 2", "add 2 1"), api.calls);
        assertEquals(OffsetDateTime.parse("2026-09-26T19:04:00Z"), service.find(BOB, 1).orElseThrow().createdAt());
    }

    @Test
    void ignore_rolledBack_whenTheApiRefuses() {
        assertTrue(service.load(BOB).join());
        api.addAnswer = CompletableFuture.completedFuture(UserIgnoresApi.AddResult.CANNOT_IGNORE_STAFF);

        assertEquals(UserIgnoresApi.AddResult.CANNOT_IGNORE_STAFF, service.ignore(BOB, 1, "Alice", ALICE).join());

        assertFalse(service.ignores(BOB, ALICE));
        assertTrue(service.list(BOB).orElseThrow().isEmpty());
    }

    @Test
    void ignore_rolledBack_whenTheApiFails() {
        assertTrue(service.load(BOB).join());
        api.addAnswer = CompletableFuture.failedFuture(new RuntimeException("API down"));

        assertThrows(CompletionException.class, () -> service.ignore(BOB, 1, "Alice", ALICE).join());

        assertFalse(service.ignores(BOB, ALICE));
    }

    @Test
    void ignore_beforeLoad_fails() {
        assertThrows(CompletionException.class, () -> service.ignore(BOB, 1, "Alice", ALICE).join());
        assertTrue(api.calls.isEmpty());
    }

    @Test
    void unignore_appliesAtOnce_andIsRestoredWhenTheApiFails() {
        loadAliceIgnoringBobAndCarol();
        CompletableFuture<Void> answer = new CompletableFuture<>();
        api.removeAnswer = answer;

        CompletableFuture<Void> result = service.unignore(ALICE, 2);
        assertFalse(service.ignores(ALICE, BOB), "optimistic");

        answer.completeExceptionally(new RuntimeException("API down"));
        assertThrows(CompletionException.class, result::join);
        assertTrue(service.ignores(ALICE, BOB), "restored");
        assertEquals(DAY1, service.find(ALICE, 2).orElseThrow().createdAt());
    }

    @Test
    void unignore_success() {
        loadAliceIgnoringBobAndCarol();

        service.unignore(ALICE, 2).join();

        assertFalse(service.ignores(ALICE, BOB));
        assertTrue(service.ignores(ALICE, CAROL));
        assertEquals(List.of("list 1", "remove 1 2"), api.calls);
    }
}
