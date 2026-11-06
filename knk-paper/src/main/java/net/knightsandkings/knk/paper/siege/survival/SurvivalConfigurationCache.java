package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ConfigurationDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ScenarioSummaryDto;
import net.knightsandkings.knk.api.impl.SiegeSurvivalQueryApiImpl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Which scenarios play survival, and their configurations (DESIGN §3 step 2 "freeze that configuration revision").
 * The lobby state machine asks synchronously at the draw (min players, untimed, startable) and at the split (human
 * team), long before a per-match fetch could answer, so the cache is filled from {@code GET
 * api/siege-survival/scenarios} + the scenario-level configuration at startup and on every runtime-config reload; the
 * draw then refreshes the drawn scenario with the lobby id (its all-captured override) and the match freezes whatever
 * is cached when it starts. A scenario the API answers 404 for is an ordinary PvP scenario (remembered as such).
 *
 * <p>Follow-up #265: the Siege runtime config has no survival flag, so this cache is how the plugin learns it.
 * Reads of the cache are main-thread; the fetches run on the API client's threads and land through {@code mainThread}.
 */
public final class SurvivalConfigurationCache {

    private final SiegeSurvivalQueryApiImpl api;
    private final Executor mainThread;
    private final Logger logger;
    private final Map<Integer, SurvivalConfiguration> byScenario = new ConcurrentHashMap<>();
    private final Set<Integer> notSurvival = ConcurrentHashMap.newKeySet();

    public SurvivalConfigurationCache(SiegeSurvivalQueryApiImpl api, Executor mainThread, Logger logger) {
        this.api = api;
        this.mainThread = mainThread;
        this.logger = logger;
    }

    /** The cached configuration (scenario level or the last lobby-level refresh); empty = PvP or not fetched yet. */
    public Optional<SurvivalConfiguration> get(int scenarioId) {
        return Optional.ofNullable(byScenario.get(scenarioId));
    }

    public boolean isSurvival(int scenarioId) {
        return byScenario.containsKey(scenarioId);
    }

    /** True once the API said this scenario has no survival settings. */
    public boolean knownPvp(int scenarioId) {
        return notSurvival.contains(scenarioId);
    }

    public Set<Integer> survivalScenarioIds() {
        return Set.copyOf(byScenario.keySet());
    }

    /** Fetches the survival scenario list and every enabled scenario's configuration (startup, config reload). */
    public CompletableFuture<Void> refreshAll() {
        if (api == null) return CompletableFuture.completedFuture(null);
        return api.listScenarios().thenCompose(summaries -> {
            List<CompletableFuture<Void>> fetches = new java.util.ArrayList<>();
            for (ScenarioSummaryDto summary : summaries == null ? List.<ScenarioSummaryDto>of() : summaries) {
                if (summary == null || summary.siegeScenarioId() == null) continue;
                if (Boolean.FALSE.equals(summary.enabled())) {
                    byScenario.remove(summary.siegeScenarioId());
                    notSurvival.add(summary.siegeScenarioId());
                    continue;
                }
                fetches.add(refresh(summary.siegeScenarioId(), null, c -> { }));
            }
            return CompletableFuture.allOf(fetches.toArray(CompletableFuture[]::new));
        }).exceptionally(e -> {
            logger.log(Level.WARNING, "[Survival] Could not list the survival scenarios: " + rootMessage(e));
            return null;
        });
    }

    /**
     * Fetches one scenario's configuration (with the lobby override when {@code lobbyId} is given) and caches it;
     * {@code onDone} runs on the main thread with the result (empty = PvP scenario or fetch failure).
     */
    public CompletableFuture<Void> refresh(int scenarioId, Integer lobbyId, Consumer<Optional<SurvivalConfiguration>> onDone) {
        if (api == null) {
            mainThread.execute(() -> onDone.accept(Optional.empty()));
            return CompletableFuture.completedFuture(null);
        }
        return api.getConfiguration(scenarioId, lobbyId, null).handle((dto, error) -> {
            Optional<SurvivalConfiguration> result = Optional.empty();
            if (error != null) {
                logger.log(Level.WARNING, "[Survival] Configuration of scenario " + scenarioId + " failed: " + rootMessage(error));
            } else if (dto == null) {
                byScenario.remove(scenarioId);
                notSurvival.add(scenarioId);
            } else {
                try {
                    SurvivalConfiguration mapped = SurvivalConfigurationMapper.map(dto);
                    if (mapped.enabled()) {
                        byScenario.put(scenarioId, mapped);
                        notSurvival.remove(scenarioId);
                        result = Optional.of(mapped);
                    } else {
                        byScenario.remove(scenarioId);
                        notSurvival.add(scenarioId);
                    }
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "[Survival] Configuration of scenario " + scenarioId + " could not be mapped", e);
                }
            }
            Optional<SurvivalConfiguration> done = result;
            mainThread.execute(() -> onDone.accept(done));
            return null;
        });
    }

    /** For tests and the startup sweep: put a configuration without the API. */
    public void put(SurvivalConfiguration configuration) {
        byScenario.put(configuration.scenarioId(), configuration);
        notSurvival.remove(configuration.scenarioId());
    }

    static String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    /** Maps a raw DTO (used by tests and by the fetch). */
    static SurvivalConfiguration map(ConfigurationDto dto) {
        return SurvivalConfigurationMapper.map(dto);
    }
}
