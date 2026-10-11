package net.knightsandkings.knk.core.domain.statistics;

import java.util.List;
import java.util.UUID;

/**
 * knk-web-api's answer to a statistics batch: {@code duplicate} when the batch id was already
 * ingested (nothing applied this time), otherwise how many entries were accepted and which were
 * rejected (final per entry: logged, never resent).
 */
public record StatisticsBatchResult(UUID batchId, boolean duplicate, int accepted, List<Rejection> rejected) {

    public StatisticsBatchResult {
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
    }

    /**
     * One rejected entry: {@code section} is {@code sessions}, {@code durations}, {@code counters},
     * {@code records} or {@code pvpKills}; {@code index} its position in that list; {@code code} one
     * of {@code UnknownMetric, NotPluginWritable, InvalidContext, OutOfRange, TooOld, InFuture,
     * UnknownSession, InvalidInterval, UnknownUser, InvalidEntry}.
     */
    public record Rejection(String section, int index, String code) {
    }
}
