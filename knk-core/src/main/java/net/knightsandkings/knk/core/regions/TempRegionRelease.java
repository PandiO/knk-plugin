package net.knightsandkings.knk.core.regions;

/**
 * PandiO/knk-workspace#269: a temp region knk-web-api released for removal ({@code GET api/WorldTasks/temp-region-releases}):
 * its world task's draft was deleted, the task closed without a result, or the world-task sweep removed its session, and
 * nothing the API knows of uses it. The game server checks again before removing it ({@link TempRegionCleanupPolicy#decideRelease}).
 *
 * @param id          the release, settled with {@link Outcome}
 * @param worldTaskId the task that drew it; the region is {@code tempregion_worldtask_<worldTaskId>}
 * @param regionId    the region id the API names
 * @param world       the world from the task's result, or null (look in every loaded world)
 * @param reason      DraftDeleted, TaskCancelled, TaskExpired, TaskFailed or SessionRemoved
 */
public record TempRegionRelease(int id, int worldTaskId, String regionId, String world, String reason) {

    /** What the game server did; the API deletes the release on any of them. */
    public enum Outcome {
        REMOVED("Removed"), MISSING("Missing"), KEPT("Kept");

        private final String wire;

        Outcome(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** Whether {@link #regionId} is the temp name of {@link #worldTaskId}'s region (anything else is never removed). */
    public boolean namesItsOwnTaskRegion() {
        return regionId != null && worldTaskId > 0
                && regionId.trim().equalsIgnoreCase(TempRegionCleanupPolicy.TEMP_REGION_PREFIX + worldTaskId);
    }
}
