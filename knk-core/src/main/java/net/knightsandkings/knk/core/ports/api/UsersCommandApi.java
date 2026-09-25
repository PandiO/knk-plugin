package net.knightsandkings.knk.core.ports.api;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.BalanceAdjustmentResult;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.SalaryPayoutResult;
import net.knightsandkings.knk.core.domain.users.UserDetail;

/**
 * Command-side operations for users (CQRS).
 * TODO: Enable only when migration mode allows writes.
 */
public interface UsersCommandApi {
    CompletableFuture<Void> setCoinsById(int id, int coins);
    CompletableFuture<Void> setCoinsByUuid(UUID uuid, int coins);
    CompletableFuture<UserDetail> create(UserDetail user);

    /**
     * Persist a player's preferred gate pass-through method (set via /knk gate passthrough).
     */
    CompletableFuture<Void> setGatePassThroughMethodById(int id, GatePassThroughMethod method);

    /**
     * Report online presence for the moderation view's "currently online" filter
     * (docs/specs/user-management/DESIGN.md §5/§7 item 2). Called from PlayerListener's
     * PlayerJoinEvent (true) and PlayerQuitEvent (false) handlers.
     */
    CompletableFuture<Void> setPresenceById(int id, boolean isOnline);

    /**
     * Persist a player's owner/staff mode (set via /ownermode or /staffmode) so it can be
     * restored on their next login.
     */
    CompletableFuture<Void> setActiveModeById(int id, ActiveMode mode);

    /**
     * Pays out the salary gap since this user's last payout, if at least an hour has passed
     * (docs/specs/user-features/IMPLEMENTATION_PLAN.md §6's intended join-time trigger). Always
     * completes with a result - check SalaryPayoutResult#paid() to distinguish an actual payout
     * from "not yet eligible" rather than relying on the future failing.
     */
    CompletableFuture<SalaryPayoutResult> payOutSalaryById(int id);

    /**
     * Adjusts a user's coins/gems/experience by a signed delta, with an audit reason (backs
     * /knk user &lt;player&gt; coins|gems|xp set|add|remove, developer request 2026-09-25).
     * Server-side rejects any delta that would take a balance negative, and - for a non-zero
     * experienceDelta - automatically resolves and audit-logs a title change if the new XP total
     * crosses a bracket boundary (same PUT /api/users/{id}/balances endpoint the web admin's
     * quick actions already use, so this gets that behavior for free).
     */
    CompletableFuture<BalanceAdjustmentResult> adjustBalancesById(int id, int coinsDelta, int gemsDelta, int experienceDelta, String reason);

    /**
     * Adds/updates this user's membership in the given PermissionGroup (backs
     * /knk user &lt;player&gt; group add &lt;groupName&gt; [duration]). Multi-membership: does not
     * touch any other group the user currently holds. expiresAt null = permanent.
     */
    CompletableFuture<Void> addGroupMembership(int userId, int groupId, OffsetDateTime expiresAt);

    /**
     * Removes this user's membership in the given PermissionGroup, if any (backs
     * /knk user &lt;player&gt; group remove &lt;groupName&gt;).
     */
    CompletableFuture<Void> removeGroupMembership(int userId, int groupId);

    /**
     * Grants (or updates) a single permission node directly on this user, e.g.
     * "knk.mode.staff" (backs /knk user &lt;player&gt; perm grant &lt;node&gt; [duration]).
     * expiresAt null = permanent.
     */
    CompletableFuture<Void> grantPermission(int userId, String node, OffsetDateTime expiresAt);

    /**
     * Revokes a directly-granted permission node from this user (backs
     * /knk user &lt;player&gt; perm revoke &lt;node&gt;).
     */
    CompletableFuture<Void> revokePermission(int userId, String node);

    /**
     * Rebuild of v1's FreezeCommands (a dead no-op stub there). Works on offline targets -
     * writes through immediately and the plugin restores/enforces the lock on next join.
     */
    CompletableFuture<Void> freezeById(int userId, String reason);

    CompletableFuture<Void> unfreezeById(int userId);
}
