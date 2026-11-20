package net.knightsandkings.knk.core.production.menu;

import java.time.Instant;
import java.util.Set;

/**
 * One transport order in a structure's orders list ({@code GET api/StructureMenus/{id}/orders}, M1): cargo units, from,
 * to, state and expected arrival. Cancel and retry follow the API's rules (knk-web-api {@code TransportService}).
 */
public record TransportOrderRow(int id, String origin, String status, String statusReason, String sourceStructureName,
                                String sourceStorageName, String destinations, long unitsRequested, long unitsLoaded,
                                long unitsDelivered, long unitsReturned, Instant createdAt, Instant etaAt,
                                Instant completedAt) {

    /** Final states: nothing moves any more. */
    static final Set<String> FINAL = Set.of("Delivered", "Returned", "Cancelled", "Lost");
    /** States a cancel can't change: final, or already bringing the cargo back. */
    static final Set<String> NOT_CANCELLABLE = Set.of("Delivered", "Returned", "Cancelled", "Lost", "Returning",
            "ReturnBlocked");
    /** States a retry refuses: delivered, lost, or on the road. */
    static final Set<String> NOT_RETRYABLE = Set.of("Delivered", "Lost", "InTransit", "Returning");

    public boolean open() {
        return status != null && !FINAL.contains(status);
    }

    public boolean canCancel() {
        return status != null && !NOT_CANCELLABLE.contains(status);
    }

    /** Waiting orders run now; a returned or cancelled one is sent again once. */
    public boolean canRetry() {
        return status != null && !NOT_RETRYABLE.contains(status);
    }
}
