package net.knightsandkings.knk.core.production.survey;

/**
 * Plugin settings of yield-area surveys (PandiO/knk-workspace#679), {@code production.surveys.*} in {@code config.yml}.
 * Nothing runs unless {@code production.enabled} is true (A21, G16).
 *
 * @param enabled               {@code production.enabled} (default false)
 * @param pollMinutes           how often the API's survey queue is read (0 = only on {@code /knk production survey})
 * @param batch                 queue items read per poll
 * @param chunksPerTick         chunk snapshots captured per server tick (the main-thread budget)
 * @param maxAreaBlocks         an area with more blocks (columns × height) is not surveyed (server cost)
 * @param maxAreaChunks         an area spanning more chunks is not surveyed
 * @param scheduledLoadsChunks  whether a Scheduled survey may load unloaded chunks (requested ones always may)
 * @param notableChangeBlocks   block breaks and places in an area that ask for a NotableChange survey (0 = off)
 * @param notableCooldownMinutes at most one notable-change survey per area per this many minutes
 * @param areasRefreshMinutes   how often the list of areas is read for the notable-change counters and area show
 */
public record SurveySettings(boolean enabled, int pollMinutes, int batch, int chunksPerTick, long maxAreaBlocks,
                             int maxAreaChunks, boolean scheduledLoadsChunks, int notableChangeBlocks,
                             int notableCooldownMinutes, int areasRefreshMinutes) {

    public static final int DEFAULT_POLL_MINUTES = 5;
    public static final int DEFAULT_BATCH = 20;
    public static final int DEFAULT_CHUNKS_PER_TICK = 2;
    public static final long DEFAULT_MAX_AREA_BLOCKS = 16_000_000L;
    public static final int DEFAULT_MAX_AREA_CHUNKS = 1024;
    public static final int DEFAULT_NOTABLE_CHANGE_BLOCKS = 64;
    public static final int DEFAULT_NOTABLE_COOLDOWN_MINUTES = 30;
    public static final int DEFAULT_AREAS_REFRESH_MINUTES = 10;

    public SurveySettings {
        pollMinutes = Math.max(0, pollMinutes);
        batch = Math.max(1, Math.min(200, batch));
        chunksPerTick = Math.max(1, Math.min(64, chunksPerTick));
        maxAreaBlocks = Math.max(1, maxAreaBlocks);
        maxAreaChunks = Math.max(1, maxAreaChunks);
        notableChangeBlocks = Math.max(0, notableChangeBlocks);
        notableCooldownMinutes = Math.max(0, notableCooldownMinutes);
        areasRefreshMinutes = Math.max(0, areasRefreshMinutes);
    }

    public static SurveySettings defaults(boolean enabled) {
        return new SurveySettings(enabled, DEFAULT_POLL_MINUTES, DEFAULT_BATCH, DEFAULT_CHUNKS_PER_TICK,
                DEFAULT_MAX_AREA_BLOCKS, DEFAULT_MAX_AREA_CHUNKS, false, DEFAULT_NOTABLE_CHANGE_BLOCKS,
                DEFAULT_NOTABLE_COOLDOWN_MINUTES, DEFAULT_AREAS_REFRESH_MINUTES);
    }

    /** Why an area is too large to survey here, or null when it fits. */
    public String refusal(AreaShape shape) {
        if (shape.chunkCount() > maxAreaChunks) {
            return "spans " + shape.chunkCount() + " chunks (production.surveys.max-area-chunks " + maxAreaChunks + ")";
        }
        long blocks = shape.blockCount();
        if (blocks > maxAreaBlocks) {
            return "holds " + blocks + " blocks (production.surveys.max-area-blocks " + maxAreaBlocks + ")";
        }
        return null;
    }
}
