package net.knightsandkings.knk.core.ports.api;

import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.BalanceAdjustmentResult;
import net.knightsandkings.knk.core.domain.users.BalanceCurrency;
import net.knightsandkings.knk.core.domain.users.BalanceOperation;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.SalaryPayoutResult;
import net.knightsandkings.knk.core.domain.users.UserDetail;

/**
 * Command-side operations for users (CQRS).
 * TODO: Enable only when migration mode allows writes.
 */
public interface UsersCommandApi {
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
     * Pays out the salary gap since this user's last payout, if at least an hour has passed:
     * the title's hourly Salary x global x personal x rank multipliers x hours elapsed. Called on
     * join (covers an offline gap) and hourly while online by the plugin's SalaryPayoutScheduler. Always
     * completes with a result - check SalaryPayoutResult#paid() to distinguish an actual payout
     * from "not yet eligible" rather than relying on the future failing.
     */
    CompletableFuture<SalaryPayoutResult> payOutSalaryById(int id);

    /**
     * One staff change to a user's coins, gems or XP (backs /knk user &lt;player&gt;
     * coins|gems|xp set|add|remove and the Player manager): {@code mode} ADD/REMOVE take a positive
     * {@code amount}, SET the target balance. The API posts it to the currency ledger and applies
     * a SET itself under the row lock - the plugin never computes a delta from a cached balance
     * (currency DESIGN.md §1.4 A6); show the numbers in the result. A non-zero XP change resolves
     * and audit-logs a title change (bracket bonuses are paid once per player, ever).
     * <p>
     * Every call carries a fresh {@code Idempotency-Key}; the HTTP client's own retry of the same
     * request reuses it, so a lost response can't apply the change twice. {@code reason} is
     * required by the API.
     *
     * @param notifyPlayer whether the API should queue a resulting title change for
     *     PlayerNotificationPoller to show in-game. Pass false when the caller shows
     *     {@link BalanceAdjustmentResult#titleChange()} to the online target itself.
     */
    CompletableFuture<BalanceAdjustmentResult> adjustBalanceById(int id, BalanceCurrency currency, BalanceOperation mode, long amount,
                                                                 String reason, boolean notifyPlayer);

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

    /**
     * InventoryMenu content port CP7: the same API, with every request attributed to
     * {@code actorUserId} - the in-game staff member acting - so knk-web-api can audit-log the
     * change under them instead of a null actor. The implementation sends it as the
     * {@code X-Acting-User-Id} header on every call made through the returned instance; the
     * receiver keeps no state beyond that, so callers create one per action (cheap). Whether and
     * how the server honours the header is CONTENT_PORT_PLAN.md CP7's open server-side question.
     */
    UsersCommandApi withActor(int actorUserId);
}
