package net.knightsandkings.knk.api.impl;

import net.knightsandkings.knk.api.client.KnkApiClient;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeReadiness;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Siege Phase 4: the real HTTP path against a running knk-web-api. Skipped unless
 * {@code KNK_LIVE_API_BASE_URL} is set, e.g. {@code http://127.0.0.1:5099/api}. Read-only.
 */
@EnabledIfEnvironmentVariable(named = "KNK_LIVE_API_BASE_URL", matches = ".+")
class SiegeQueryApiLiveTest {

    private final KnkApiClient client = KnkApiClient.builder()
            .baseUrl(System.getenv("KNK_LIVE_API_BASE_URL"))
            .build();

    @Test
    void fetchesRuntimeConfig() throws Exception {
        KnkSiegeRuntimeConfig config = client.getSiegeLobbiesQueryApi().getRuntimeConfig().get(10, TimeUnit.SECONDS);

        assertNotNull(config.configuration());
        assertNotNull(config.generatedAt());
        config.lobbies().forEach(lobby -> System.out.printf("lobby %d %s: %d ready, %d skipped%n",
                lobby.id(), lobby.key(), lobby.rotation().size(), lobby.skippedScenarios().size()));
    }

    @Test
    void readinessOfAMissingScenarioIsNull() throws Exception {
        KnkSiegeReadiness readiness = client.getSiegeScenariosQueryApi().getReadiness(Integer.MAX_VALUE).get(10, TimeUnit.SECONDS);

        assertNull(readiness);
    }
}
