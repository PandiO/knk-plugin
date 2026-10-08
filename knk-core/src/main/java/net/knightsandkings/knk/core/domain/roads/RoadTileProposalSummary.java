package net.knightsandkings.knk.core.domain.roads;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * A tile's stored proposal without its items ({@code GET api/road-tiles/proposals?world=}, plan §5.7 D5).
 * Mirrors the web-api's {@code RoadTileProposalSummaryDto}. Bukkit-free.
 *
 * @param tileVersion the tile's current version; another value than {@code baseVersion} means its graph
 *                    changed since the proposal was made
 */
public record RoadTileProposalSummary(int tileId, String world, int tileX, int tileZ, int baseVersion, int tileVersion,
                                      int builderVersion, String createdBy, OffsetDateTime createdAt,
                                      OffsetDateTime updatedAt, int addedCount, int removedCount, int changedCount,
                                      int movedCount, int rejectedCount) {
    public RoadTileProposalSummary {
        Objects.requireNonNull(world, "world");
    }

    /** Pending items. */
    public int pendingCount() {
        return addedCount + removedCount + changedCount + movedCount;
    }
}
