package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ConfigurationDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.PreviewDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ReadinessDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ScenarioSummaryDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtosTest;
import net.knightsandkings.knk.core.exception.ApiException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Survival phase 1 (PandiO/knk-workspace#41, link S4): the survival reads and their URLs. */
class SiegeSurvivalQueryApiImplTest {

    private final List<Request> seen = new ArrayList<>();
    private String responseJson = "{}";
    private int responseCode = 200;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(responseCode).message(responseCode == 200 ? "OK" : "Not Found")
                .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();
    private final SiegeSurvivalQueryApiImpl api = new SiegeSurvivalQueryApiImpl("http://api.test/api", client,
            new ObjectMapper(), new NoAuthProvider(), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void getConfiguration_BuildsTheUrl_AndReadsThePayload() {
        responseJson = SiegeSurvivalDtosTest.CONFIGURATION_JSON;

        ConfigurationDto config = api.getConfiguration(100, 7, 10).join();

        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/siege-survival/scenarios/100/configuration?lobbyId=7&difficultyProfileId=10", seen.get(0).url().toString());
        assertEquals(100, config.siegeScenarioId());
        assertEquals("Kits", config.equipment().mode());
        assertTrue(config.readiness().isReady());
    }

    @Test
    void getConfiguration_WithoutLobbyOrProfile_HasNoQuery_AndA404IsNull() {
        responseJson = SiegeSurvivalDtosTest.CONFIGURATION_JSON;
        api.getConfiguration(100, null, null).join();
        assertEquals("http://api.test/api/siege-survival/scenarios/100/configuration", seen.get(0).url().toString());

        api.getConfiguration(100, null, 10).join();
        assertEquals("http://api.test/api/siege-survival/scenarios/100/configuration?difficultyProfileId=10", seen.get(1).url().toString());

        responseCode = 404;
        responseJson = "\"SiegeScenario 100 has no survival settings.\"";
        assertNull(api.getConfiguration(100, null, null).join());
    }

    @Test
    void getReadiness_ReadsErrorsAndWarnings() {
        responseJson = """
                {"siegeScenarioId":100,"siegeLobbyId":null,"difficultyProfileId":10,"isReady":false,
                 "errors":[{"code":"box.total","message":"Pool chances add up to 99.99 %, not exactly 100 %","entityType":"SurvivalMysteryBoxPool","entityId":40}],
                 "warnings":[{"code":"end.wipe-only","message":"Only a team wipe can end the game"}]}
                """;

        ReadinessDto readiness = api.getReadiness(100, null, 10).join();

        assertEquals("http://api.test/api/siege-survival/scenarios/100/readiness?difficultyProfileId=10", seen.get(0).url().toString());
        assertFalse(readiness.isReady());
        assertEquals("box.total", readiness.errors().get(0).code());
        assertEquals(40, readiness.errors().get(0).entityId());
        assertEquals("end.wipe-only", readiness.warnings().get(0).code());
        assertNull(readiness.warnings().get(0).entityId());
    }

    @Test
    void listScenarios_AndPreview() {
        responseJson = """
                [{"siegeScenarioId":100,"siegeScenarioName":"Siege of Cinix","enabled":true,"equipmentMode":"OwnInventory","difficultyProfileCount":2}]
                """;
        List<ScenarioSummaryDto> scenarios = api.listScenarios().join();
        assertEquals("http://api.test/api/siege-survival/scenarios", seen.get(0).url().toString());
        assertEquals(1, scenarios.size());
        assertEquals("OwnInventory", scenarios.get(0).equipmentMode());
        assertEquals(2, scenarios.get(0).difficultyProfileCount());

        responseJson = """
                {"difficultyProfileId":10,"players":3,"rounds":[{"round":1,"enemyCount":9,"curveValue":1,"health":1,"walkingSpeed":1,"meleeDamage":1,"rangedDamage":1,"attackSpeed":1}]}
                """;
        PreviewDto preview = api.getPreview(100, 10, 1, 1, 3).join();
        assertEquals("http://api.test/api/SiegeSurvival/scenarios/100/preview?fromRound=1&toRound=1&players=3&difficultyProfileId=10", seen.get(1).url().toString());
        assertEquals(9, preview.rounds().get(0).enemyCount());
    }

    @Test
    void otherErrors_Propagate() {
        responseCode = 500;
        responseJson = "{\"error\":\"boom\"}";
        CompletionException ex = assertThrows(CompletionException.class, () -> api.getReadiness(100, null, null).join());
        assertTrue(ex.getCause() instanceof ApiException);
    }

    @Test
    void query_JoinsTheParametersThatAreSet() {
        assertEquals("", SiegeSurvivalQueryApiImpl.query(null, null));
        assertEquals("?lobbyId=7", SiegeSurvivalQueryApiImpl.query(7, null));
        assertEquals("?lobbyId=7&difficultyProfileId=10", SiegeSurvivalQueryApiImpl.query(7, 10));
    }
}
