package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.item.KnkKitClaimResult;
import net.knightsandkings.knk.core.domain.item.KnkKitPurchaseResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Write/action side for Kits (docs/specs/kits/DESIGN.md §4.1) - the grant/claim/purchase
 * methods every real surface ({@code /kit} commands, the first-join hook) calls into. Mirrors
 * {@link UsersCommandApi}'s per-action shape.
 */
public interface KitsCommandApi {

    /** Self-claim: re-validates gating/cooldown/cost server-side, returns the resolved loadout. */
    CompletableFuture<KnkKitClaimResult> claimAsync(int userId, int kitId);

    /** For IsSinglePurchasePremium kits only - deducts PremiumPriceGems, does not itself grant. */
    CompletableFuture<KnkKitPurchaseResult> purchaseAsync(int userId, int kitId);

    /**
     * Staff-initiated grant - bypasses gating/cooldown/cost by design (DESIGN.md §0b/§4.1).
     * Backs {@code /kit give}, deliberately not the same call path as {@link #claimAsync}.
     *
     * @param actorUserId the staff member's user id, sent as {@code X-Acting-User-Id} so the
     *                    API's audit entry names them (null = recorded as system)
     */
    CompletableFuture<KnkKitClaimResult> giveAsync(Integer actorUserId, int targetUserId, int kitId);

    /** Grants every GrantOnFirstJoin kit the user is gated to receive (DESIGN.md §4.4). */
    CompletableFuture<List<KnkKitClaimResult>> grantFirstJoinKitsAsync(int userId);
}
