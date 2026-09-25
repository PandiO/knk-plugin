package net.knightsandkings.knk.core.ports.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
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
}
