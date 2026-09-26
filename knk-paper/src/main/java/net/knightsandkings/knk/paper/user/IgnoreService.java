package net.knightsandkings.knk.paper.user;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.domain.users.UserIgnore;
import net.knightsandkings.knk.core.messaging.IgnoreGate;
import net.knightsandkings.knk.core.messaging.ParticipantId;
import net.knightsandkings.knk.core.ports.api.UserIgnoresApi;

/**
 * Ignore lists of online players (KNG-18 Phase 2, docs/specs/private-messages/DESIGN.md §3.3.5).
 * knk-web-api stores them ({@link UserIgnoresApi}); this keeps a copy per online player, loaded
 * async on join and dropped on quit, so the private message gate ({@link IgnoreGate}) and the
 * public chat filter never wait on the API.
 * <p>
 * Each player's list is an immutable snapshot swapped atomically in a concurrent map: read from
 * the main thread (/msg) and from {@code AsyncChatEvent}, written from API callbacks. Changes are
 * optimistic - the snapshot changes right away and is rolled back if the API refuses or fails.
 * Until a player's list has loaded they ignore nobody (fail open).
 */
public class IgnoreService implements IgnoreGate.Lookup {

    private static final Logger LOGGER = Logger.getLogger(IgnoreService.class.getName());

    private final UserIgnoresApi api;
    private final Function<UUID, Integer> userIdOf;
    private final Predicate<UUID> isOnline;
    private final Clock clock;
    private final Map<UUID, Map<Integer, UserIgnore>> lists = new ConcurrentHashMap<>();

    /**
     * @param userIdOf a player's knk user id from the user cache, or null when not cached
     * @param isOnline whether a player is still online (a late load for someone who quit is dropped)
     */
    public IgnoreService(UserIgnoresApi api, Function<UUID, Integer> userIdOf, Predicate<UUID> isOnline, Clock clock) {
        this.api = Objects.requireNonNull(api, "api must not be null");
        this.userIdOf = Objects.requireNonNull(userIdOf, "userIdOf must not be null");
        this.isOnline = Objects.requireNonNull(isOnline, "isOnline must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Loads {@code player}'s list unless it is already loaded. Completes with false when their
     * user id isn't cached yet or the API failed (they stay unloaded - fail open - until the next
     * attempt).
     */
    public CompletableFuture<Boolean> load(UUID player) {
        if (lists.containsKey(player)) {
            return CompletableFuture.completedFuture(true);
        }
        Integer userId = userIdOf.apply(player);
        if (userId == null) {
            return CompletableFuture.completedFuture(false);
        }
        return api.list(userId).handle((entries, ex) -> {
            if (ex != null) {
                LOGGER.log(Level.WARNING, "Failed to load the ignore list of user " + userId, ex);
                return false;
            }
            if (!isOnline.test(player)) {
                return false;
            }
            Map<Integer, UserIgnore> loaded = new LinkedHashMap<>();
            for (UserIgnore entry : entries) {
                loaded.put(entry.ignoredUserId(), entry);
            }
            lists.putIfAbsent(player, Map.copyOf(loaded));
            return true;
        });
    }

    public boolean isLoaded(UUID player) {
        return lists.containsKey(player);
    }

    public void forget(UUID player) {
        lists.remove(player);
    }

    /** Whether {@code viewer} ignores the player {@code sender}; false while the viewer's list is loading. */
    public boolean ignores(UUID viewer, UUID sender) {
        Map<Integer, UserIgnore> list = lists.get(viewer);
        if (list == null || sender == null) {
            return false;
        }
        for (UserIgnore entry : list.values()) {
            if (sender.equals(entry.ignoredUuid())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean ignores(ParticipantId recipient, ParticipantId sender) {
        if (recipient.isConsole() || sender.isConsole()) {
            return false;
        }
        return ignores(recipient.uuid(), sender.uuid());
    }

    /** {@code player}'s list, oldest first; empty while it is loading. */
    public Optional<List<UserIgnore>> list(UUID player) {
        Map<Integer, UserIgnore> list = lists.get(player);
        if (list == null) {
            return Optional.empty();
        }
        return Optional.of(list.values().stream()
                .sorted(Comparator.comparing(UserIgnore::createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList());
    }

    /** The entry for {@code name} (case-insensitive) on {@code player}'s list. */
    public Optional<UserIgnore> find(UUID player, String name) {
        Map<Integer, UserIgnore> list = lists.get(player);
        if (list == null || name == null) {
            return Optional.empty();
        }
        return list.values().stream().filter(entry -> name.equalsIgnoreCase(entry.ignoredUsername())).findFirst();
    }

    /** The entry for user {@code ignoredUserId} on {@code player}'s list. */
    public Optional<UserIgnore> find(UUID player, int ignoredUserId) {
        Map<Integer, UserIgnore> list = lists.get(player);
        return list == null ? Optional.empty() : Optional.ofNullable(list.get(ignoredUserId));
    }

    /**
     * Adds {@code target} to {@code player}'s (loaded) list right away, then asks the API; rolls
     * the entry back unless the API answers {@link UserIgnoresApi.AddResult#IGNORED}. Fails
     * exceptionally (after the rollback) when the API can't be reached, or when the list isn't
     * loaded / the player's user id isn't cached.
     */
    public CompletableFuture<UserIgnoresApi.AddResult> ignore(UUID player, int targetUserId, String targetName, UUID targetUuid) {
        Integer userId = userIdOf.apply(player);
        if (userId == null || !isLoaded(player)) {
            return CompletableFuture.failedFuture(new IllegalStateException("ignore list of " + player + " not loaded"));
        }
        UserIgnore entry = new UserIgnore(targetUserId, targetName, targetUuid, OffsetDateTime.now(clock));
        lists.computeIfPresent(player, (key, list) -> with(list, entry));
        return api.add(userId, targetUserId).whenComplete((result, ex) -> {
            if (ex != null || result != UserIgnoresApi.AddResult.IGNORED) {
                lists.computeIfPresent(player, (key, list) -> list.get(targetUserId) == entry ? without(list, targetUserId) : list);
            }
        });
    }

    /**
     * Removes {@code targetUserId} from {@code player}'s list right away, then asks the API; puts
     * the entry back if the call fails.
     */
    public CompletableFuture<Void> unignore(UUID player, int targetUserId) {
        Integer userId = userIdOf.apply(player);
        Map<Integer, UserIgnore> list = lists.get(player);
        UserIgnore removed = list != null ? list.get(targetUserId) : null;
        if (userId == null || removed == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("user " + targetUserId + " is not on the list of " + player));
        }
        lists.computeIfPresent(player, (key, current) -> without(current, targetUserId));
        return api.remove(userId, targetUserId).whenComplete((ignored, ex) -> {
            if (ex != null) {
                lists.computeIfPresent(player, (key, current) -> current.containsKey(targetUserId) ? current : with(current, removed));
            }
        });
    }

    private static Map<Integer, UserIgnore> with(Map<Integer, UserIgnore> list, UserIgnore entry) {
        Map<Integer, UserIgnore> copy = new LinkedHashMap<>(list);
        copy.put(entry.ignoredUserId(), entry);
        return Map.copyOf(copy);
    }

    private static Map<Integer, UserIgnore> without(Map<Integer, UserIgnore> list, int ignoredUserId) {
        Map<Integer, UserIgnore> copy = new LinkedHashMap<>(list);
        copy.remove(ignoredUserId);
        return Map.copyOf(copy);
    }
}
