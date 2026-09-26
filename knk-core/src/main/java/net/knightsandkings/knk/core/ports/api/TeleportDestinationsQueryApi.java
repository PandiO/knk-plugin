package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;

/**
 * {@code GET api/teleport-destinations?userId=} (knk-web-api TeleportDestinationsController,
 * docs/specs/teleport/DESIGN.md §3.7.3): every warp destination with its lock state for one player.
 * Game-server only (the API key).
 */
public interface TeleportDestinationsQueryApi {
    CompletableFuture<List<KnkTeleportDestination>> listForUser(int userId);
}
