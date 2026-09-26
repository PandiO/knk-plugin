package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryProgressRow;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySkip;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySource;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySummary;
import net.knightsandkings.knk.core.domain.discovery.KnownDiscovery;
import net.knightsandkings.knk.core.exception.ApiException;
import okio.Buffer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Domain discovery (KNG-20): request/response JSON matches knk-web-api's DiscoveryDtos.cs. */
class DiscoveriesApiImplTest {

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int status = 200;
    private String responseJson = "{}";

    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        if (request.body() != null) {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            bodies.add(buffer.readUtf8());
        } else {
            bodies.add(null);
        }
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();

    private final DiscoveriesApiImpl api = new DiscoveriesApiImpl("http://api.test/api", client, mapper,
            new NoAuthProvider(), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private static String fixture(String name) throws Exception {
        try (InputStream in = DiscoveriesApiImplTest.class.getResourceAsStream("/discovery/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void grantPostsRegionIdsAndSourceAndMapsTheWholeResult() throws Exception {
        responseJson = fixture("grant-result.json");

        DiscoveryGrantResult result = api.grant(12, List.of("district_market", "spawn"), DiscoverySource.JOIN_INSIDE).join();

        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/users/12/discoveries", seen.get(0).url().toString());
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals("district_market", body.get("wgRegionIds").get(0).asText());
        assertEquals("JoinInside", body.get("source").asText());
        assertTrue(!body.has("domainIds"), "no domainIds field when none are sent");

        assertEquals(2, result.granted().size());
        assertEquals("Rivia", result.granted().get(0).name());
        assertEquals("town_rivia", result.granted().get(0).wgRegionId());
        assertEquals(2600, result.granted().get(0).coinsBase());
        assertEquals(3120, result.granted().get(0).coins());
        assertEquals("Rivia", result.granted().get(1).parentName());
        assertEquals(List.of(9), result.alreadyDiscovered());
        assertEquals(new DiscoverySkip("gate_north", DiscoverySkip.RATE_LIMITED), result.skipped().get(1));
        assertEquals(72, result.totalExp());
        assertEquals(60, result.totalExpBase());
        assertEquals(1.2, result.coinMultipliers().get(1).value());
        assertEquals("Royal", result.coinMultipliers().get(1).name());
        assertEquals(1, result.gemMultipliers().size());
        assertEquals(3, result.titleBracketId());
        assertEquals(10120, result.newCoins());
        assertEquals(2572, result.newExperiencePoints());
        assertEquals("Reeve", result.titleChange().toTitleName());
        assertTrue(result.titleChange().isPromotion());
        assertEquals(500, result.titleChange().coinBonusGranted());
    }

    @Test
    void grantWithoutTitleChangeOrMultipliersMapsToEmpty() {
        responseJson = "{\"granted\":[],\"alreadyDiscovered\":[1,2],\"skipped\":[],\"newCoins\":5}";

        DiscoveryGrantResult result = api.grant(12, List.of("town_rivia"), DiscoverySource.REGION_ENTER).join();

        assertNull(result.titleChange());
        assertTrue(result.coinMultipliers().isEmpty());
        assertEquals(List.of(1, 2), result.alreadyDiscovered());
        assertEquals(5, result.newCoins());
    }

    @Test
    void aRefusalKeepsTheStatusCodeInTheCauseChain() {
        status = 400;
        responseJson = "{\"error\":\"ValidationFailed\"}";

        CompletionException error = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
                () -> api.grant(12, List.of("x"), DiscoverySource.REGION_ENTER).join());

        Throwable t = error;
        while (t != null && !(t instanceof ApiException)) {
            t = t.getCause();
        }
        assertNotNull(t);
        assertEquals(400, ((ApiException) t).getStatusCode());
    }

    @Test
    void knownReadsDomainAndRegionIds() {
        responseJson = "[{\"domainId\":1,\"wgRegionId\":\"town_rivia\"},{\"domainId\":2,\"wgRegionId\":null}]";

        List<KnownDiscovery> known = api.known(12).join();

        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/users/12/discoveries/known", seen.get(0).url().toString());
        assertEquals(List.of(new KnownDiscovery(1, "town_rivia"), new KnownDiscovery(2, null)), known);
    }

    @Test
    void progressForwardsThePagedQueryAndMapsRows() throws Exception {
        responseJson = "{\"items\":[{\"domainId\":4,\"name\":\"Market\",\"domainType\":\"District\",\"parentName\":\"Rivia\","
                + "\"discovered\":true,\"discoveredAt\":\"2026-09-26T10:00:00\",\"coins\":10,\"gems\":1,\"exp\":5}],"
                + "\"totalCount\":31,\"pageNumber\":2,\"pageSize\":27}";

        Page<DiscoveryProgressRow> page = api.progress(12,
                new PagedQuery(2, 27, null, null, false, Map.of("status", "discovered"))).join();

        assertEquals("http://api.test/api/users/12/discoveries/progress", seen.get(0).url().toString());
        JsonNode body = mapper.readTree(bodies.get(0));
        assertEquals(2, body.get("pageNumber").asInt());
        assertEquals("discovered", body.get("filters").get("status").asText());
        assertEquals(31, page.totalCount());
        DiscoveryProgressRow row = page.items().get(0);
        assertEquals("Market", row.name());
        assertTrue(row.discovered());
        assertEquals(2026, row.discoveredAt().getYear());
    }

    @Test
    void summaryMapsCountsAndLatest() {
        responseJson = "{\"byType\":[{\"domainType\":\"Town\",\"discovered\":3,\"total\":5}],"
                + "\"latest\":{\"domainId\":1,\"name\":\"Rivia\",\"domainType\":\"Town\",\"discovered\":true},"
                + "\"totalDiscovered\":3,\"totalCoins\":100,\"totalGems\":2,\"totalExp\":40}";

        DiscoverySummary summary = api.summary(12).join();

        assertEquals("http://api.test/api/users/12/discoveries/summary", seen.get(0).url().toString());
        assertEquals(5, summary.byType().get(0).total());
        assertEquals("Rivia", summary.latest().name());
        assertEquals(40, summary.totalExp());
    }

    @Test
    void resetDeletesTheDiscovery() {
        status = 204;
        responseJson = "";

        api.reset(42, 12, 4).join();

        assertEquals("DELETE", seen.get(0).method());
        assertEquals("http://api.test/api/users/12/discoveries/4", seen.get(0).url().toString());
        assertEquals("42", seen.get(0).header("X-Acting-User-Id"));
    }

    @Test
    void resetWithoutAnActorSendsNoActorHeader() {
        status = 204;
        responseJson = "";

        api.reset(null, 12, 4).join();

        assertNull(seen.get(0).header("X-Acting-User-Id"));
    }
}
