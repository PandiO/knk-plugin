package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;
import net.knightsandkings.knk.core.domain.teleport.KnkTeleportPolicy;

/**
 * {@code GET api/teleport-destinations?userId=} (knk-web-api TeleportDestinationsController,
 * docs/specs/teleport/DESIGN.md §3.7.3): every warp destination with its lock state for one player.
 * Game-server only (the API key).
 */
public interface TeleportDestinationsQueryApi {
    CompletableFuture<List<KnkTeleportDestination>> listForUser(int userId);

    /**
     * {@code GET api/teleport-destinations/policy?userId=} (Linear KNG-41): the player's teleport fees
     * and cooldowns from their permission groups. The default (fakes, an older API) is "no group sets
     * anything".
     */
    default CompletableFuture<KnkTeleportPolicy> policyForUser(int userId) {
        return CompletableFuture.completedFuture(KnkTeleportPolicy.DEFAULT);
    }
}
