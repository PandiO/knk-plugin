package net.knightsandkings.knk.core.ports.api;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.location.LocationOrphanPage;
import net.knightsandkings.knk.core.domain.location.LocationTeleportTarget;

/**
 * knk-web-api's api/location-retention (KNG-80) as the game server uses it. Both routes accept the
 * plugin service key (RequireServiceOrPermission), so the commands check the staff member's node
 * in game before calling.
 */
public interface LocationRetentionApi {
    /** Orphaned Locations, newest first. {@code status}: open, kept, deleted, resolved or all. */
    CompletableFuture<LocationOrphanPage> listOrphans(String status, int page, int pageSize);

    /** Any Location's world and position; empty when it doesn't exist. */
    CompletableFuture<Optional<LocationTeleportTarget>> teleportTarget(int locationId);
}
