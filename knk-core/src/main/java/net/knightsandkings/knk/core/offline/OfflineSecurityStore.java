package net.knightsandkings.knk.core.offline;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.UserIgnore;
import net.knightsandkings.knk.core.domain.users.UserSummary;

/**
 * The last-known security state the game server needs when knk-web-api can't be reached (KNG-58):
 * which knk account a Minecraft UUID belongs to, that account's active mode and freeze, and the
 * permission answers the API gave for it, and the player's ignore list (KNG-123: the chat, /msg and
 * /tpa filter stays on). Kept in memory and written to one JSON file, so it survives a restart
 * during an outage.
 *
 * <p>Rules (KNG-58, KNG-34 D14-D16):
 * <ul>
 *   <li><b>Fallback only.</b> Callers ask the API (or its in-memory cache) first; this store answers
 *       only when that couldn't be done. Every successful API answer overwrites it.</li>
 *   <li><b>Bounded age.</b> An identity is used for at most {@code identityMaxAge} after the API
 *       last confirmed it (at most the 30-day erasure deadline), a permission answer for at most
 *       {@code permissionMaxAge}, an ignore list for at most {@code identityMaxAge} after the API last
 *       returned it. Older entries count as unknown - for permissions that means denied - and are
 *       pruned.</li>
 *   <li><b>Erasure.</b> {@link #forgetUser} / {@link #forgetUserId} delete everything about a
 *       player, including their ignore list and their entries on other players' lists.
 *       {@code OfflineIdentityVerifier} calls them for accounts the API no longer knows
 *       under that UUID (erased, pseudonymized, replaced), and the KNG-57 push will too.</li>
 *   <li><b>Minimal.</b> Only what the server needs to enforce rules: UUID, user id, name, active
 *       mode, freeze and reason, permission answers, and per ignore entry the ignored player's user
 *       id, UUID, name and since when - no balances, email or profile data.</li>
 * </ul>
 * Thread-safe. File writes happen in {@link #flushIfDirty()} (call it off the main thread).
 */
public final class OfflineSecurityStore {

    private static final Logger LOGGER = Logger.getLogger(OfflineSecurityStore.class.getName());
    static final int FORMAT_VERSION = 1;

    /** What the server knows about one player's account. */
    public record Identity(UUID uuid, int userId, String username, ActiveMode activeMode,
                           boolean frozen, String frozenReason, Instant verifiedAt) {
        public Identity {
            activeMode = activeMode == null ? ActiveMode.NONE : activeMode;
        }
    }

    /** {@code identityMaxAge}: an identity unconfirmed for longer is not used; {@code permissionMaxAge} likewise. */
    public record Settings(Duration identityMaxAge, Duration permissionMaxAge) {
        public static Settings defaults() {
            return new Settings(Duration.ofDays(30), Duration.ofHours(72));
        }
    }

    private record PermissionKey(int userId, String node) {
    }

    private record PermissionAnswer(boolean allowed, Instant verifiedAt) {
    }

    /** The ignore list the API last returned for a player. */
    private record IgnoreList(int userId, List<UserIgnore> entries, Instant verifiedAt) {
    }

    // ---- file format (strings for enums and instants so it doesn't depend on Jackson modules) ----

    record IdentityFile(String uuid, int userId, String username, String activeMode, boolean frozen,
                        String frozenReason, String verifiedAt) {
    }

    record PermissionFile(int userId, String node, boolean allowed, String verifiedAt) {
    }

    record IgnoreEntryFile(int ignoredUserId, String ignoredUsername, String ignoredUuid, String createdAt) {
    }

    record IgnoreListFile(String uuid, int userId, List<IgnoreEntryFile> entries, String verifiedAt) {
    }

    /**
     * {@code ignores} was added for KNG-123; a file without it (older plugin) reads as no lists.
     *
     * @param gameSettings the last Game Settings snapshot as its own JSON text (hub DESIGN §16.4 decision 7); may be null
     */
    record StoreFile(int version, List<IdentityFile> identities, List<PermissionFile> permissions,
                     List<IgnoreListFile> ignores, String gameSettings, String gameSettingsSavedAt) {
    }

    private final Path file;
    private final Settings settings;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<UUID, Identity> identities = new ConcurrentHashMap<>();
    private final Map<PermissionKey, PermissionAnswer> permissions = new ConcurrentHashMap<>();
    private final Map<UUID, IgnoreList> ignores = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private volatile String gameSettingsJson;
    private volatile Instant gameSettingsSavedAt;

    /** @param file where the store is kept; null keeps it in memory only (tests) */
    public OfflineSecurityStore(Path file, Settings settings, Clock clock) {
        this.file = file;
        this.settings = settings != null ? settings : Settings.defaults();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Settings settings() {
        return settings;
    }

    // ---- identities ----

    /** The API just returned this account: remember (or refresh) it. A different account for the UUID replaces the old one. */
    public void recordUser(UserSummary user) {
        if (user == null || user.uuid() == null || user.id() == null) {
            return;
        }
        Identity previous = identities.get(user.uuid());
        if (previous != null && previous.userId() != user.id()) {
            forgetUserId(previous.userId());
        }
        identities.put(user.uuid(), new Identity(user.uuid(), user.id(), user.username(), user.activeMode(),
            user.isFrozen(), user.frozenReason(), clock.instant()));
        dirty.set(true);
    }

    /** The player's account, if the API confirmed it within {@code identityMaxAge}. */
    public Optional<Identity> identity(UUID uuid) {
        Identity identity = uuid == null ? null : identities.get(uuid);
        if (identity == null || expired(identity.verifiedAt(), settings.identityMaxAge())) {
            return Optional.empty();
        }
        return Optional.of(identity);
    }

    /** This server froze or unfroze the player: keep that for the next join during an outage. */
    public void setFrozen(UUID uuid, boolean frozen, String reason) {
        identities.computeIfPresent(uuid, (id, current) -> new Identity(current.uuid(), current.userId(),
            current.username(), current.activeMode(), frozen, frozen ? reason : null, current.verifiedAt()));
        dirty.set(true);
    }

    /** This server changed the player's active mode (vanish): keep it for the next join during an outage. */
    public void setActiveMode(UUID uuid, ActiveMode mode) {
        identities.computeIfPresent(uuid, (id, current) -> new Identity(current.uuid(), current.userId(),
            current.username(), mode, current.frozen(), current.frozenReason(), current.verifiedAt()));
        dirty.set(true);
    }

    /** Identities the API hasn't confirmed for at least {@code age}, oldest first (for the verifier). */
    public List<Identity> identitiesUnverifiedFor(Duration age) {
        Instant cutoff = clock.instant().minus(age);
        List<Identity> due = new ArrayList<>();
        for (Identity identity : identities.values()) {
            if (!identity.verifiedAt().isAfter(cutoff)) {
                due.add(identity);
            }
        }
        due.sort(Comparator.comparing(Identity::verifiedAt));
        return due;
    }

    /** Delete everything about the player behind {@code uuid} (erasure, or the UUID now belongs to someone else). */
    public void forgetUser(UUID uuid) {
        if (uuid == null) {
            return;
        }
        Identity removed = identities.remove(uuid);
        if (removed != null) {
            forgetPermissions(removed.userId());
            forgetIgnores(removed.userId());
        }
        boolean ignoresChanged = ignores.remove(uuid) != null;
        ignoresChanged |= removeIgnoreEntries(entry -> uuid.equals(entry.ignoredUuid()));
        if (removed != null || ignoresChanged) {
            dirty.set(true);
        }
    }

    /** Delete everything about knk account {@code userId}. */
    public void forgetUserId(int userId) {
        identities.values().removeIf(identity -> identity.userId() == userId);
        forgetPermissions(userId);
        forgetIgnores(userId);
        dirty.set(true);
    }

    // ---- ignore lists (KNG-123) ----

    /** The API just returned {@code owner}'s (user {@code userId}) ignore list: remember it, replacing the old one. */
    public void recordIgnores(UUID owner, int userId, List<UserIgnore> entries) {
        if (owner == null) {
            return;
        }
        ignores.put(owner, new IgnoreList(userId, entries == null ? List.of() : List.copyOf(entries), clock.instant()));
        dirty.set(true);
    }

    /**
     * The API accepted an /ignore of {@code entry} made on this server: add it to the stored list (replacing an entry
     * for the same user). It doesn't count as the API returning the list, so the max age isn't extended. No list
     * known: nothing to change.
     */
    public void addIgnore(UUID owner, UserIgnore entry) {
        if (owner == null || entry == null) {
            return;
        }
        ignores.computeIfPresent(owner, (key, current) -> {
            List<UserIgnore> entries = new ArrayList<>(current.entries());
            entries.removeIf(e -> e.ignoredUserId() == entry.ignoredUserId());
            entries.add(entry);
            return new IgnoreList(current.userId(), List.copyOf(entries), current.verifiedAt());
        });
        dirty.set(true);
    }

    /** The API accepted an /unignore of user {@code ignoredUserId} made on this server; as {@link #addIgnore}. */
    public void removeIgnore(UUID owner, int ignoredUserId) {
        if (owner == null) {
            return;
        }
        ignores.computeIfPresent(owner, (key, current) -> new IgnoreList(current.userId(),
            current.entries().stream().filter(e -> e.ignoredUserId() != ignoredUserId).toList(), current.verifiedAt()));
        dirty.set(true);
    }

    /** {@code owner}'s ignore list as the API last returned it, if within {@code identityMaxAge}. */
    public Optional<List<UserIgnore>> ignores(UUID owner) {
        IgnoreList list = owner == null ? null : ignores.get(owner);
        if (list == null || expired(list.verifiedAt(), settings.identityMaxAge())) {
            return Optional.empty();
        }
        return Optional.of(list.entries());
    }

    public int ignoreListCount() {
        return ignores.size();
    }

    private void forgetIgnores(int userId) {
        ignores.values().removeIf(list -> list.userId() == userId);
        removeIgnoreEntries(entry -> entry.ignoredUserId() == userId);
    }

    /** Takes matching entries off every list (an erased player must not stay on anyone's list). */
    private boolean removeIgnoreEntries(Predicate<UserIgnore> match) {
        boolean changed = false;
        for (Map.Entry<UUID, IgnoreList> e : ignores.entrySet()) {
            IgnoreList list = e.getValue();
            if (list.entries().stream().anyMatch(match)) {
                List<UserIgnore> kept = list.entries().stream().filter(match.negate()).toList();
                ignores.replace(e.getKey(), list, new IgnoreList(list.userId(), kept, list.verifiedAt()));
                changed = true;
            }
        }
        return changed;
    }

    // ---- permissions ----

    /** The API answered a permission check for {@code userId}/{@code node}. */
    public void recordPermission(int userId, String node, boolean allowed) {
        if (node == null) {
            return;
        }
        permissions.put(new PermissionKey(userId, node), new PermissionAnswer(allowed, clock.instant()));
        dirty.set(true);
    }

    /**
     * Drops every remembered permission answer of {@code userId}: their groups or grants changed (realtime
     * {@code UserPermissionsChanged}, PandiO/knk-workspace#179, fixes #76), so an answer from before the change must
     * not decide a check during a later outage. New answers are recorded again as checks reach the API.
     */
    public void forgetPermissionAnswers(int userId) {
        int before = permissions.size();
        forgetPermissions(userId);
        if (permissions.size() != before) {
            dirty.set(true);
        }
    }

    /** The API's last answer for {@code userId}/{@code node}, if within {@code permissionMaxAge}. */
    public Optional<Boolean> permission(int userId, String node) {
        PermissionAnswer answer = node == null ? null : permissions.get(new PermissionKey(userId, node));
        if (answer == null || expired(answer.verifiedAt(), settings.permissionMaxAge())) {
            return Optional.empty();
        }
        return Optional.of(answer.allowed());
    }

    // ---- housekeeping ----

    /** Drop every entry past its max age. Returns how many were removed. */
    public int prune() {
        int before = identities.size() + permissions.size() + ignores.size();
        identities.values().removeIf(identity -> expired(identity.verifiedAt(), settings.identityMaxAge()));
        permissions.values().removeIf(answer -> expired(answer.verifiedAt(), settings.permissionMaxAge()));
        ignores.values().removeIf(list -> expired(list.verifiedAt(), settings.identityMaxAge()));
        int removed = before - identities.size() - permissions.size() - ignores.size();
        if (removed > 0) {
            dirty.set(true);
        }
        return removed;
    }

    public int identityCount() {
        return identities.size();
    }

    public int permissionCount() {
        return permissions.size();
    }

    // ---- Game Settings (hub DESIGN §16.4 decision 7) ----

    /**
     * The last Game Settings the API gave, as JSON text, so the settings and the hub survive a restart while the API is
     * down. Unlike identities and permission answers it does not age out: the last valid snapshot is kept until the
     * API sends another (hub DESIGN §3). Blank is ignored.
     */
    public void recordGameSettings(String json) {
        if (json == null || json.isBlank() || json.equals(gameSettingsJson)) {
            return;
        }
        gameSettingsJson = json;
        gameSettingsSavedAt = clock.instant();
        dirty.set(true);
    }

    /** The last recorded Game Settings JSON, if any. */
    public Optional<String> gameSettingsJson() {
        return Optional.ofNullable(gameSettingsJson);
    }

    /** When the Game Settings were last recorded, if ever. */
    public Optional<Instant> gameSettingsSavedAt() {
        return Optional.ofNullable(gameSettingsSavedAt);
    }

    /** Read the file (if any); expired entries are dropped on the way in. A corrupt file is moved aside. */
    public synchronized void load() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            StoreFile stored = mapper.readValue(file.toFile(), StoreFile.class);
            if (stored.identities() != null) {
                for (IdentityFile f : stored.identities()) {
                    UUID uuid = UUID.fromString(f.uuid());
                    identities.put(uuid, new Identity(uuid, f.userId(), f.username(), parseMode(f.activeMode()),
                        f.frozen(), f.frozenReason(), Instant.parse(f.verifiedAt())));
                }
            }
            if (stored.permissions() != null) {
                for (PermissionFile f : stored.permissions()) {
                    permissions.put(new PermissionKey(f.userId(), f.node()),
                        new PermissionAnswer(f.allowed(), Instant.parse(f.verifiedAt())));
                }
            }
            if (stored.ignores() != null) {
                for (IgnoreListFile f : stored.ignores()) {
                    List<UserIgnore> entries = new ArrayList<>();
                    if (f.entries() != null) {
                        for (IgnoreEntryFile e : f.entries()) {
                            entries.add(new UserIgnore(e.ignoredUserId(), e.ignoredUsername(),
                                e.ignoredUuid() == null ? null : UUID.fromString(e.ignoredUuid()),
                                e.createdAt() == null ? null : OffsetDateTime.parse(e.createdAt())));
                        }
                    }
                    ignores.put(UUID.fromString(f.uuid()),
                        new IgnoreList(f.userId(), List.copyOf(entries), Instant.parse(f.verifiedAt())));
                }
            }
            if (stored.gameSettings() != null && !stored.gameSettings().isBlank()) {
                gameSettingsJson = stored.gameSettings();
                gameSettingsSavedAt = stored.gameSettingsSavedAt() != null ? Instant.parse(stored.gameSettingsSavedAt()) : null;
            }
            prune();
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "[KnK Offline] Could not read " + file + "; starting empty and keeping the file as .corrupt", e);
            try {
                Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                LOGGER.log(Level.WARNING, "[KnK Offline] Could not move " + file + " aside", moveError);
            }
        }
    }

    /**
     * Write the file if anything changed since the last write (temp file + atomic move).
     *
     * @return whether the file now holds everything in memory (written now, or nothing to write); false when there is
     *         no file or the write failed (it is retried on the next flush)
     */
    public synchronized boolean flushIfDirty() {
        if (file == null) {
            return false;
        }
        if (!dirty.getAndSet(false)) {
            return true;
        }
        List<IdentityFile> identityFiles = identities.values().stream()
            .map(i -> new IdentityFile(i.uuid().toString(), i.userId(), i.username(), i.activeMode().name(),
                i.frozen(), i.frozenReason(), i.verifiedAt().toString()))
            .toList();
        List<PermissionFile> permissionFiles = permissions.entrySet().stream()
            .map(e -> new PermissionFile(e.getKey().userId(), e.getKey().node(), e.getValue().allowed(),
                e.getValue().verifiedAt().toString()))
            .toList();
        List<IgnoreListFile> ignoreFiles = ignores.entrySet().stream()
            .map(e -> new IgnoreListFile(e.getKey().toString(), e.getValue().userId(),
                e.getValue().entries().stream()
                    .map(i -> new IgnoreEntryFile(i.ignoredUserId(), i.ignoredUsername(),
                        i.ignoredUuid() == null ? null : i.ignoredUuid().toString(),
                        i.createdAt() == null ? null : i.createdAt().toString()))
                    .toList(),
                e.getValue().verifiedAt().toString()))
            .toList();
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Instant savedAt = gameSettingsSavedAt;
            Files.write(temp, mapper.writeValueAsBytes(new StoreFile(FORMAT_VERSION, identityFiles, permissionFiles, ignoreFiles,
                gameSettingsJson, savedAt != null ? savedAt.toString() : null)));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            dirty.set(true);
            LOGGER.log(Level.WARNING, "[KnK Offline] Could not write " + file, e);
            return false;
        }
        return true;
    }

    private void forgetPermissions(int userId) {
        permissions.keySet().removeIf(key -> key.userId() == userId);
    }

    private boolean expired(Instant verifiedAt, Duration maxAge) {
        return verifiedAt == null || verifiedAt.plus(maxAge).isBefore(clock.instant());
    }

    private static ActiveMode parseMode(String mode) {
        try {
            return mode == null ? ActiveMode.NONE : ActiveMode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            return ActiveMode.NONE;
        }
    }
}
