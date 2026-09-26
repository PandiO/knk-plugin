package net.knightsandkings.knk.paper.teleport;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.core.teleport.TeleportAudit;
import net.knightsandkings.knk.core.teleport.TeleportKind;

/**
 * Sends every staff teleport to the web API's audit log (docs/specs/teleport/DESIGN.md §3.10,
 * Phase 2) as a {@code PlayerTeleported} entry, attributed to the staff member through
 * {@link UsersCommandApi#withActor}.
 * <p>
 * Fire-and-forget: {@link #record} snapshots what it needs on the main thread and returns at once;
 * user ids are looked up and the call is made off the main thread, retried once after
 * {@value #RETRY_DELAY_SECONDS} s, and a final failure is only logged - the teleport has already
 * happened and the player is never told. {@link TeleportService}'s own INFO line keeps a local
 * record of every staff teleport, so nothing is lost while the API is down.
 */
public class TeleportAuditor {

    private static final Logger LOGGER = Logger.getLogger(TeleportAuditor.class.getName());
    static final long RETRY_DELAY_SECONDS = 5;

    /** A player's knk user id, completing with null when they have no account (or the lookup failed). */
    @FunctionalInterface
    public interface UserIdLookup {
        CompletableFuture<Integer> idOf(UUID player);
    }

    private final UsersCommandApi api;
    private final UserIdLookup userIds;
    private final Executor retryExecutor;

    public TeleportAuditor(UsersCommandApi api, UserIdLookup userIds) {
        this(api, userIds, CompletableFuture.delayedExecutor(RETRY_DELAY_SECONDS, TimeUnit.SECONDS));
    }

    /** @param retryExecutor runs the single retry (the default waits {@value #RETRY_DELAY_SECONDS} s first) */
    public TeleportAuditor(UsersCommandApi api, UserIdLookup userIds, Executor retryExecutor) {
        this.api = Objects.requireNonNull(api, "api must not be null");
        this.userIds = Objects.requireNonNull(userIds, "userIds must not be null");
        this.retryExecutor = Objects.requireNonNull(retryExecutor, "retryExecutor must not be null");
    }

    /**
     * Audit a teleport that has just happened. Main thread (reads the plan's players); never throws.
     * The returned future is for tests - it completes (never exceptionally) once the entry was sent
     * or given up on.
     */
    public CompletableFuture<Void> record(TeleportPlan plan, Location from, Location to) {
        try {
            Snapshot snapshot = Snapshot.of(plan, from, to);
            return snapshot.send(this).exceptionally(ex -> {
                LOGGER.log(Level.WARNING, "[KnK Teleport] Could not audit " + snapshot.describe(), ex);
                return null;
            });
        } catch (RuntimeException ex) {
            LOGGER.log(Level.WARNING, "[KnK Teleport] Could not audit the teleport of " + plan.subject().getName(), ex);
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<Integer> idOf(UUID player) {
        if (player == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            CompletableFuture<Integer> lookup = userIds.idOf(player);
            return lookup != null ? lookup.exceptionally(ex -> null) : CompletableFuture.completedFuture(null);
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<Void> post(TeleportAudit audit) {
        try {
            UsersCommandApi acting = audit.actorUserId() != null ? api.withActor(audit.actorUserId()) : api;
            return acting.recordTeleportAudit(audit);
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    /** Everything the audit needs, copied off the Bukkit objects on the main thread. */
    private record Snapshot(
        TeleportKind kind,
        boolean silent,
        TeleportAudit.Point from,
        TeleportAudit.Point to,
        UUID actor,
        boolean console,
        String actorName,
        UUID subject,
        String subjectName,
        UUID visited
    ) {
        static Snapshot of(TeleportPlan plan, Location from, Location to) {
            Player actorPlayer = plan.actor() instanceof Player player ? player : null;
            Player visited = plan.visited();
            return new Snapshot(plan.kind(), plan.silent(), point(from), point(to),
                actorPlayer != null ? actorPlayer.getUniqueId() : null, actorPlayer == null, plan.actor().getName(),
                plan.subject().getUniqueId(), plan.subject().getName(),
                visited != null ? visited.getUniqueId() : null);
        }

        CompletableFuture<Void> send(TeleportAuditor auditor) {
            CompletableFuture<Integer> actorId = auditor.idOf(actor);
            CompletableFuture<Integer> subjectId = auditor.idOf(subject);
            CompletableFuture<Integer> visitedId = auditor.idOf(visited);
            return CompletableFuture.allOf(actorId, subjectId, visitedId).thenCompose(ignored -> {
                Integer subjectUserId = subjectId.join();
                if (subjectUserId == null) {
                    LOGGER.warning("[KnK Teleport] Not audited, " + subjectName + " has no knk account: " + describe());
                    return CompletableFuture.completedFuture(null);
                }
                TeleportAudit audit = new TeleportAudit(kind, actorId.join(), subjectUserId, visitedId.join(),
                    from, to, silent, null, console);
                return auditor.post(audit)
                    .exceptionallyComposeAsync(first -> {
                        LOGGER.log(Level.FINE, "Teleport audit failed once, retrying: " + describe(), first);
                        return auditor.post(audit);
                    }, auditor.retryExecutor);
            });
        }

        String describe() {
            return actorName + " -> " + subjectName + " (" + kind + ") from " + from.world() + " "
                + (int) Math.floor(from.x()) + "," + (int) Math.floor(from.y()) + "," + (int) Math.floor(from.z())
                + " to " + to.world() + " "
                + (int) Math.floor(to.x()) + "," + (int) Math.floor(to.y()) + "," + (int) Math.floor(to.z());
        }

        private static TeleportAudit.Point point(Location location) {
            String world = location.getWorld() != null ? location.getWorld().getName() : "?";
            return new TeleportAudit.Point(world, location.getX(), location.getY(), location.getZ());
        }
    }
}
