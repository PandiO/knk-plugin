package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.territory.TerritoryHolder;
import net.knightsandkings.knk.core.territory.TerritoryInputs;
import net.knightsandkings.knk.core.territory.TerritoryMap;
import net.knightsandkings.knk.core.territory.TerritoryReport;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** PandiO/knk-workspace#689: the routes, the plugin key, reading the inputs and writing the report (contract of #326). */
class TerritoryApiImplTest {

    private static final String INPUTS = """
            {"productionEnabled":true,"settings":{"baseRange":100,"powerFactor":2,"capitalMultiplier":1.5},
             "version":"abc123","generatedAt":"2026-11-19T12:00:00Z",
             "kingdoms":[{"id":7,"name":"Aurelia"}],
             "towns":[{"id":1,"name":"Phoenix","kingdomId":7,"provinceId":null,"power":50,"range":200}],
             "districts":[
               {"id":10,"name":"Old Town","world":"world","wgRegionId":"district_10","subRegionIds":["district_10_b"],
                "holder":{"kind":"Town","id":1,"name":"Phoenix"},"isCapital":true,"rank":1,"range":300},
               {"id":11,"name":"No region","world":null,"wgRegionId":"","subRegionIds":[],
                "holder":{"kind":"Town","id":1},"isCapital":false,"rank":1,"range":200},
               {"id":12,"name":"Odd holder","world":null,"wgRegionId":"district_12","subRegionIds":[],
                "holder":{"kind":"Province","id":3},"isCapital":false,"rank":1,"range":200}],
             "crownLands":[{"id":20,"name":"Royal forest","world":"","wgRegionId":"domain_20","subRegionIds":null,
                "holder":{"kind":"Kingdom","id":7,"name":"Aurelia"},"isCapital":false,"rank":2,"range":80}],
             "awards":[{"conflictId":5,"world":"world","holder":{"kind":"Town","id":1},"chunks":[[4,2],[5,2],[9]]}]}
            """;

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private volatile int status = 200;
    private volatile String body = INPUTS;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        seen.add(chain.request());
        if (chain.request().body() != null) {
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            bodies.add(buffer.readUtf8());
        }
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }).build();
    private final TerritoryApiImpl api = new TerritoryApiImpl("http://api.test/api", client,
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
            new ApiKeyAuthProvider("secret"), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void fetchInputs_readsSourcesHoldersAndAwards_andDropsWhatItCantUse() {
        TerritoryInputs inputs = api.fetchInputs().join();

        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/Territory/inputs", seen.get(0).url().toString());
        assertEquals("secret", seen.get(0).header("X-API-Key"));
        assertTrue(inputs.productionEnabled());
        assertEquals("abc123", inputs.version());
        assertEquals("Phoenix", inputs.nameOf(TerritoryHolder.town(1)));
        assertEquals("Aurelia", inputs.nameOf(TerritoryHolder.kingdom(7)));
        assertEquals(2, inputs.sources().size(), "no region and an unknown holder kind are dropped");

        TerritoryInputs.Source oldTown = inputs.sources().get(0);
        assertEquals(TerritoryInputs.SourceType.DISTRICT, oldTown.type());
        assertEquals(List.of("district_10", "district_10_b"), oldTown.regionIds());
        assertTrue(oldTown.capital());
        assertEquals(300, oldTown.range());
        assertEquals(1, inputs.townOfDistrict(10));

        TerritoryInputs.Source forest = inputs.sources().get(1);
        assertEquals(TerritoryInputs.SourceType.CROWN_LAND, forest.type());
        assertEquals(TerritoryHolder.kingdom(7), forest.holder());
        assertEquals(null, forest.world(), "a blank world is unknown");

        TerritoryInputs.Award award = inputs.awards().get(0);
        assertEquals(TerritoryHolder.town(1), award.holder());
        assertEquals(2, award.chunks().size(), "the broken pair is dropped");
        assertTrue(award.chunks().contains(TerritoryMap.chunkKey(4, 2)));
    }

    @Test
    void report_putsTheSnapshotWithTheKey_andReadsTheResult() {
        body = "{\"world\":\"world\",\"conflictsCreated\":1,\"conflictsUpdated\":0,\"conflictsNoLongerOverlapping\":2,"
                + "\"structuresFlagged\":1,\"structuresCleared\":0,\"skipped\":[\"x\"]}";
        TerritoryReport report = new TerritoryReport("world", "abc123",
                List.of(new TerritoryReport.Overlap(TerritoryHolder.town(1), TerritoryHolder.town(2),
                        List.of(new int[] { 4, 2 }))),
                List.of(new TerritoryReport.StructureFlag(100, TerritoryReport.StructureFlag.OUTSIDE, null, null)));

        TerritoryReport.Result result = api.report(report).join();

        assertEquals("PUT", seen.get(0).method());
        assertEquals("http://api.test/api/Territory/report", seen.get(0).url().toString());
        assertEquals("secret", seen.get(0).header("X-API-Key"));
        assertEquals("{\"world\":\"world\",\"inputsVersion\":\"abc123\",\"overlaps\":[{\"holderA\":{\"kind\":\"Town\",\"id\":1},"
                + "\"holderB\":{\"kind\":\"Town\",\"id\":2},\"chunks\":[[4,2]]}],\"structures\":[{\"structureId\":100,"
                + "\"flag\":\"OutsideTerritory\",\"holderA\":null,\"holderB\":null}]}", bodies.get(0));
        assertEquals(1, result.conflictsCreated());
        assertEquals(2, result.conflictsNoLongerOverlapping());
        assertEquals(List.of("x"), result.skipped());
    }

    @Test
    void report_refusedWhileTheApiFlagIsOff_failsWith409() {
        status = 409;
        body = "{\"error\":\"off\",\"code\":\"ProductionDisabled\"}";

        CompletionException error = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
                () -> api.report(new TerritoryReport("world", "v", List.of(), List.of())).join());

        Throwable cause = error;
        while (cause != null && !(cause instanceof ApiException)) {
            cause = cause.getCause();
        }
        assertFalse(cause == null);
        assertEquals(409, ((ApiException) cause).getStatusCode());
    }
}
