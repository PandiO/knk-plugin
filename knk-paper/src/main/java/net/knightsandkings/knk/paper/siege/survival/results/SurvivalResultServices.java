package net.knightsandkings.knk.paper.siege.survival.results;

import net.knightsandkings.knk.api.impl.SiegeSurvivalMatchApiImpl;
import net.knightsandkings.knk.paper.siege.survival.recovery.SurvivalRecoveryStore;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * What phase 5 (link S8b) needs from the plugin: the survival match API (result, checkpoint), the recovery store under
 * the plugin data folder, the main-thread executor and the checkpoint interval ({@code siege.survival.checkpoint-interval-seconds}).
 */
public record SurvivalResultServices(SiegeSurvivalMatchApiImpl api, SurvivalRecoveryStore store, Executor mainThread,
                                     long checkpointIntervalMillis, SurvivalResultPoster poster) {

    public SurvivalResultServices {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(mainThread, "mainThread");
        Objects.requireNonNull(poster, "poster");
        if (checkpointIntervalMillis < 0) checkpointIntervalMillis = 0;
    }

    public static SurvivalResultServices create(SiegeSurvivalMatchApiImpl api, SurvivalRecoveryStore store, Executor mainThread,
                                                int checkpointIntervalSeconds, Logger logger) {
        return new SurvivalResultServices(api, store, mainThread, Math.max(0, checkpointIntervalSeconds) * 1000L,
            new SurvivalResultPoster(api, logger));
    }
}
