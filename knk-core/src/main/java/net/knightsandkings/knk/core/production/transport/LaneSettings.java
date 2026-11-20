package net.knightsandkings.knk.core.production.transport;

/**
 * {@code production.enabled} and {@code production.logistics.lanes.*} (PandiO/knk-workspace#681).
 *
 * @param enabled             {@code production.enabled}: nothing measures without it (A21)
 * @param intervalMinutes     how often the headless job reads the lane list (0 = only on {@code /knk logistics lanes
 *                            measure})
 * @param batch               pairs read per run (the API's {@code limit})
 * @param maxSnapDistance     how far (blocks, 3D) a structure's position may be from a road and still join it
 * @param roadConnections     whether the job also sets the structures' road connection
 */
public record LaneSettings(boolean enabled, int intervalMinutes, int batch, double maxSnapDistance,
                           boolean roadConnections) {

    public static final int DEFAULT_INTERVAL_MINUTES = 60;
    public static final int DEFAULT_BATCH = 100;
    public static final double DEFAULT_MAX_SNAP_DISTANCE = 48;
    /** The API's limit per report call. */
    public static final int MAX_REPORT = 500;

    public LaneSettings {
        intervalMinutes = Math.max(0, intervalMinutes);
        batch = Math.max(1, Math.min(batch, 1000));
        maxSnapDistance = maxSnapDistance <= 0 ? DEFAULT_MAX_SNAP_DISTANCE : maxSnapDistance;
    }

    public static LaneSettings defaults(boolean enabled) {
        return new LaneSettings(enabled, DEFAULT_INTERVAL_MINUTES, DEFAULT_BATCH, DEFAULT_MAX_SNAP_DISTANCE, true);
    }
}
