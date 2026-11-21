package net.knightsandkings.knk.core.production.harvest;

/**
 * Plugin settings of player harvesting (PandiO/knk-workspace#134), {@code production.harvest.*} in {@code config.yml}.
 * Nothing runs unless {@code production.enabled} is true (A21, G16).
 *
 * @param enabled               {@code production.enabled} (default false)
 * @param catalogRefreshMinutes how often {@code GET api/Harvest/catalog} is read (rules, areas, pool fill)
 * @param reportFlushSeconds    how often waiting harvests are sent ({@code POST api/Harvest/reports})
 * @param reportBatch           entries per report (the API takes at most {@code maxEntriesPerReport}, 200)
 * @param regenBlocksPerTick    blocks restored per server tick at most (main-thread budget)
 * @param regenRetrySeconds     a restore blocked by a player or mob in the block is tried again after this
 * @param saveSeconds           how often changed queues are written to disk
 * @param denyAtEmptyPool       refuse harvests in an area whose pool is empty (the API books them either way)
 * @param treeMaxLogs           a felled tree with more logs is not felled whole (a building, or a giant tree)
 * @param treeMaxLeaves         leaves taken with a felled tree at most
 * @param maxAreaChunks         an area spanning more chunks is left out of harvesting
 */
public record HarvestSettings(boolean enabled, int catalogRefreshMinutes, int reportFlushSeconds, int reportBatch,
                              int regenBlocksPerTick, int regenRetrySeconds, int saveSeconds, boolean denyAtEmptyPool,
                              int treeMaxLogs, int treeMaxLeaves, int maxAreaChunks, Messages messages) {

    /** Player messages ({@code &} colour codes); a null message is the default. */
    public record Messages(String denied, String regenerating, String paid, String rejected) {
        static final String DEFAULT_DENIED = "&cYou can't harvest this here: {reason}.";
        static final String DEFAULT_REGENERATING = "&7This block is regenerating.";
        static final String DEFAULT_PAID = "&a+{amount} {item} &7(your share of the harvest)";
        static final String DEFAULT_REJECTED = "&cYour harvest at {area} was not booked: {reason}.";

        public static final Messages DEFAULT = new Messages(null, null, null, null);

        public Messages {
            denied = denied == null ? DEFAULT_DENIED : denied;
            regenerating = regenerating == null ? DEFAULT_REGENERATING : regenerating;
            paid = paid == null ? DEFAULT_PAID : paid;
            rejected = rejected == null ? DEFAULT_REJECTED : rejected;
        }
    }

    public static final int DEFAULT_CATALOG_REFRESH_MINUTES = 5;
    public static final int DEFAULT_REPORT_FLUSH_SECONDS = 2;
    public static final int DEFAULT_REPORT_BATCH = 200;
    public static final int DEFAULT_REGEN_BLOCKS_PER_TICK = 64;
    public static final int DEFAULT_REGEN_RETRY_SECONDS = 5;
    public static final int DEFAULT_SAVE_SECONDS = 10;
    public static final int DEFAULT_TREE_MAX_LOGS = 256;
    public static final int DEFAULT_TREE_MAX_LEAVES = 1500;
    public static final int DEFAULT_MAX_AREA_CHUNKS = 256;

    public HarvestSettings {
        catalogRefreshMinutes = Math.max(1, catalogRefreshMinutes);
        reportFlushSeconds = Math.max(1, reportFlushSeconds);
        reportBatch = Math.max(1, Math.min(200, reportBatch));
        regenBlocksPerTick = Math.max(1, regenBlocksPerTick);
        regenRetrySeconds = Math.max(1, regenRetrySeconds);
        saveSeconds = Math.max(1, saveSeconds);
        treeMaxLogs = Math.max(1, treeMaxLogs);
        treeMaxLeaves = Math.max(0, treeMaxLeaves);
        maxAreaChunks = Math.max(1, maxAreaChunks);
        messages = messages == null ? Messages.DEFAULT : messages;
    }

    public static HarvestSettings defaults(boolean enabled) {
        return new HarvestSettings(enabled, DEFAULT_CATALOG_REFRESH_MINUTES, DEFAULT_REPORT_FLUSH_SECONDS,
                DEFAULT_REPORT_BATCH, DEFAULT_REGEN_BLOCKS_PER_TICK, DEFAULT_REGEN_RETRY_SECONDS, DEFAULT_SAVE_SECONDS, false,
                DEFAULT_TREE_MAX_LOGS, DEFAULT_TREE_MAX_LEAVES, DEFAULT_MAX_AREA_CHUNKS, Messages.DEFAULT);
    }
}
