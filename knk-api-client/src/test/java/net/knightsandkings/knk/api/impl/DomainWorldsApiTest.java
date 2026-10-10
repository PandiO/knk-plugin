package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.core.domain.domains.DomainAccessRule;
import net.knightsandkings.knk.core.domain.domains.DomainRegionQuery;
import net.knightsandkings.knk.core.domain.domains.DomainRegionSummary;
import net.knightsandkings.knk.core.ports.api.DomainWorldBackfillApi;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** KNG-112: the domain world travels with region decisions, access rules and the world backfill. */
class DomainWorldsApiTest {

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private String responseJson = "[]";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        if (request.body() != null) {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            bodies.add(buffer.readUtf8());
        }
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();
    // As KnkApiClient configures it.
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void aRegionDecisionQueryNamesItsWorldAndTheAnswerCarriesTheDomainsWorld() throws Exception {
        DomainsQueryApiImpl api = new DomainsQueryApiImpl("http://api.test/api", client, mapper, new NoAuthProvider(), executor, false);
        responseJson = """
                {"0":{"id":4,"name":"Hubtown","description":"","wgRegionId":"town_1","allowEntry":true,"allowExit":true,
                      "domainType":"Town","worldName":"hub","parentDomainDecisions":[]}}
                """;

        HashMap<Integer, DomainRegionSummary> answer =
            api.searchDomainRegionDecisions(new DomainRegionQuery(Set.of("town_1"), true, "hub")).join();

        assertEquals("hub", mapper.readTree(bodies.get(0)).get("worldName").asText());
        assertEquals("hub", answer.get(0).worldName());
        assertEquals("Town", answer.get(0).domainType());
    }

    @Test
    void aSummaryWithoutAWorldStillReads() {
        DomainsQueryApiImpl api = new DomainsQueryApiImpl("http://api.test/api", client, mapper, new NoAuthProvider(), executor, false);
        responseJson = """
                {"0":{"id":4,"name":"Old","description":"","wgRegionId":"town_1","allowEntry":true,"allowExit":true,
                      "domainType":"Town","parentDomainDecisions":[]}}
                """;

        assertNull(api.searchDomainRegionDecisions(new DomainRegionQuery(Set.of("town_1"), true)).join().get(0).worldName());
    }

    @Test
    void anAccessRuleCarriesItsWorld() {
        DomainAccessRulesApiImpl api = new DomainAccessRulesApiImpl("http://api.test/api", client, mapper, new NoAuthProvider(), executor, false);
        responseJson = """
                [{"id":7,"name":"Hubtown","wgRegionId":"town_1","allowEntry":true,"allowExit":true,"domainType":"Town","worldName":"hub"}]
                """;

        assertEquals(new DomainAccessRule(7, "Hubtown", "town_1", true, true, "Town", "hub"), api.listAccessRules().join().get(0));
    }

    @Test
    void theBackfillReportsEachRegionsWorldsAndReadsWhatIsLeft() throws Exception {
        DomainWorldBackfillApiImpl api = new DomainWorldBackfillApiImpl("http://api.test/api", client, mapper, new NoAuthProvider(), executor, false);
        responseJson = """
                {"updated":1,"unresolved":[{"id":3,"name":"Ambiguous","domainType":"Town","wgRegionId":"town_3",
                                            "candidateWorlds":["hub","gameplay"]}]}
                """;

        DomainWorldBackfillApi.Result result = api.backfill(Map.of("town_3", List.of("hub", "gameplay"))).join();

        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/Domains/world/backfill", seen.get(0).url().toString());
        assertEquals("town_3", mapper.readTree(bodies.get(0)).get("regions").get(0).get("wgRegionId").asText());
        assertEquals(1, result.updated());
        assertEquals(List.of("hub", "gameplay"), result.unresolved().get(0).candidateWorlds());
    }

    @Test
    void noMissingDomainsIsAnEmptyList() {
        DomainWorldBackfillApiImpl api = new DomainWorldBackfillApiImpl("http://api.test/api", client, mapper, new NoAuthProvider(), executor, false);

        assertTrue(api.listMissing().join().isEmpty());
        assertEquals("http://api.test/api/Domains/world/missing", seen.get(0).url().toString());
    }
}
