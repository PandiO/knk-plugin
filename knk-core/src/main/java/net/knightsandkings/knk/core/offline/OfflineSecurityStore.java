package net.knightsandkings.knk.core.offline;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.UserSummary;

/**
 * The last-known security state the game server needs when knk-web-api can't be reached (KNG-58):
 * which knk account a Minecraft UUID belongs to, that account's active mode and freeze, and the
 * permission answers the API gave for it. Kept in memory and written to one JSON file, so it
 * survives a restart during an outage.
 *
 * <p>Rules (KNG-58, KNG-34 D14-D16):
 * <ul>
 *   <li><b>Fallback only.</b> Callers ask the API (or its in-memory cache) first; this store answers
 *       only when that couldn't be done. Every successful API answer overwrites it.</li>
 *   <li><b>Bounded age.</b> An identity is used for at most {@code identityMaxAge} after the API
 *       last confirmed it (at most the 30-day erasure deadline), a permission answer for at most
 *       {@code permissionMaxAge}. Older entries count as unknown - for permissions that means
 *       denied - and are pruned.</li>
 *   <li><b>Erasure.</b> {@link #forgetUser} / {@link #forgetUserId} delete everything about a
 *       player. {@code OfflineIdentityVerifier} calls them for accounts the API no longer knows
 *       under that UUID (erased, pseudonymized, replaced), and the KNG-57 push will too.</li>
 *   <li><b>Minimal.</b> Only what the server needs to enforce rules: UUID, user id, name, active
 *       mode, freeze and reason, permission answers - no balances, email or profile data.</li>
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

    // ---- file format (strings for enums and instants so it doesn't depend on Jackson modules) ----

    record IdentityFile(String uuid, int userId, String username, String activeMode, boolean frozen,
                        String frozenReason, String verifiedAt) {
    }

    record PermissionFile(int userId, String node, boolean allowed, String verifiedAt) {
    }

    record StoreFile(int version, List<IdentityFile> identities, List<PermissionFile> permissions) {
    }

    private final Path file;
    private final Settings settings;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<UUID, Identity> identities = new ConcurrentHashMap<>();
    private final Map<PermissionKey, PermissionAnswer> permissions = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();

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
        Identity removed = uuid == null ? null : identities.remove(uuid);
        if (removed != null) {
            forgetPermissions(removed.userId());
            dirty.set(true);
        }
    }

    /** Delete everything about knk account {@code userId}. */
    public void forgetUserId(int userId) {
        identities.values().removeIf(identity -> identity.userId() == userId);
        forgetPermissions(userId);
        dirty.set(true);
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
        int before = identities.size() + permissions.size();
        identities.values().removeIf(identity -> expired(identity.verifiedAt(), settings.identityMaxAge()));
        permissions.values().removeIf(answer -> expired(answer.verifiedAt(), settings.permissionMaxAge()));
        int removed = before - identities.size() - permissions.size();
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

    /** Write the file if anything changed since the last write (temp file + atomic move). */
    public synchronized void flushIfDirty() {
        if (file == null || !dirty.getAndSet(false)) {
            return;
        }
        List<IdentityFile> identityFiles = identities.values().stream()
            .map(i -> new IdentityFile(i.uuid().toString(), i.userId(), i.username(), i.activeMode().name(),
                i.frozen(), i.frozenReason(), i.verifiedAt().toString()))
            .toList();
        List<PermissionFile> permissionFiles = permissions.entrySet().stream()
            .map(e -> new PermissionFile(e.getKey().userId(), e.getKey().node(), e.getValue().allowed(),
                e.getValue().verifiedAt().toString()))
            .toList();
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, mapper.writeValueAsBytes(new StoreFile(FORMAT_VERSION, identityFiles, permissionFiles)));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            dirty.set(true);
            LOGGER.log(Level.WARNING, "[KnK Offline] Could not write " + file, e);
        }
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
