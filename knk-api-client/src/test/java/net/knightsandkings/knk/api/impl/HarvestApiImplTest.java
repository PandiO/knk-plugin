package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.HarvestApi;
import net.knightsandkings.knk.core.production.harvest.HarvestArea;
import net.knightsandkings.knk.core.production.harvest.HarvestCatalog;
import net.knightsandkings.knk.core.production.harvest.HarvestRule;
import net.knightsandkings.knk.core.production.survey.AreaShape;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** PandiO/knk-workspace#134: the harvest catalogue and the batched reports (the contract of #685, knk-web-api#72). */
class HarvestApiImplTest {

    private static final String CATALOG = """
            {"featureEnabled":true,"generatedAt":"2026-11-21T10:00:00Z",
             "rules":[{"id":7,"name":"Quarry stone","productionKindId":3,"enabled":true,"targetKind":"Block",
                "targets":["STONE","*_ORE"],"minBlockAge":0,"requiredPermission":"knk.harvest.quarry","minTitleBracketId":2,
                "toolClass":"Pickaxe","minToolTier":"Stone","cooldownSeconds":0,"yieldItemBlueprintId":41,"yieldPerBreak":1,
                "workerShare":0.25,"poolCost":1,"placeholder":"DepletedBlock","placeholderMaterial":null,"regenSeconds":60},
               {"id":9,"productionKindId":1,"targetKind":"Entity","targets":["COW"],"toolClass":"Any","minToolTier":"Any",
                "yieldItemBlueprintId":43,"placeholder":"None","regenSeconds":0},
               {"id":0,"productionKindId":1,"yieldItemBlueprintId":1},
               {"id":10,"productionKindId":1}],
             "areas":[{"productionAreaId":5,"name":"Pit","productionStructureId":4,"productionStructureName":"Quarry",
                "productionKindId":3,"productionEnabled":true,"worldName":"world","shape":"Cuboid","minX":0,"minY":60,
                "minZ":0,"maxX":9,"maxY":69,"maxZ":9,"poolFill":0.25},
               {"productionAreaId":6,"productionStructureId":2,"productionKindId":2,"productionEnabled":false,
                "worldName":"world","shape":"Polygon","points":[{"x":0,"z":0},{"x":10,"z":0},{"x":10,"z":10}],
                "minX":0,"minY":-64,"minZ":0,"maxX":10,"maxY":319,"maxZ":10,"poolFill":1},
               {"productionAreaId":0,"productionStructureId":2}]}
            """;

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private volatile int status = 200;
    private volatile String body = CATALOG;
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
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final HarvestApiImpl api = new HarvestApiImpl("http://api.test/api", client, mapper,
            new ApiKeyAuthProvider("secret"), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void catalog_readsRulesAndAreas_andDropsIncompleteOnes() {
        HarvestCatalog catalog = api.catalog().join();

        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/Harvest/catalog", seen.get(0).url().toString());
        assertEquals("secret", seen.get(0).header("X-API-Key"));
        assertTrue(catalog.featureEnabled());
        assertEquals(Instant.parse("2026-11-21T10:00:00Z"), catalog.generatedAt());
        assertEquals(2, catalog.rules().size());
        HarvestRule stone = catalog.rules().get(0);
        assertEquals(HarvestRule.TargetKind.BLOCK, stone.targetKind());
        assertTrue(stone.targets("IRON_ORE"));
        assertEquals("knk.harvest.quarry", stone.requiredPermission());
        assertEquals(2, stone.minTitleBracketId());
        assertEquals(HarvestRule.ToolClass.PICKAXE, stone.toolClass());
        assertEquals(HarvestRule.ToolTier.STONE, stone.minToolTier());
        assertEquals(HarvestRule.Placeholder.DEPLETED_BLOCK, stone.placeholder());
        assertEquals(60, stone.regenSeconds());
        assertEquals(41, stone.yieldItemBlueprintId());
        assertEquals(HarvestRule.TargetKind.ENTITY, catalog.rules().get(1).targetKind());

        assertEquals(2, catalog.areas().size());
        HarvestArea pit = catalog.areas().get(0);
        assertEquals(5, pit.areaId());
        assertEquals(3, pit.productionKindId());
        assertEquals(0.25, pit.poolFill());
        assertEquals(new AreaShape.Cuboid(0, 60, 0, 9, 69, 9), pit.spec().resolve(null, -64, 320));
        HarvestArea polygon = catalog.areas().get(1);
        assertFalse(polygon.productionEnabled());
        assertTrue(polygon.spec().resolve(null, -64, 320).contains(8, 70, 2));
    }

    @Test
    void catalog_whileTheFlagIsOff_isEmpty() {
        body = "{\"featureEnabled\":false,\"rules\":[],\"areas\":[]}";
        HarvestCatalog catalog = api.catalog().join();
        assertFalse(catalog.featureEnabled());
        assertTrue(catalog.rules().isEmpty());
    }

    @Test
    void report_sendsOneKeyPerEntry_andReadsEachAnswer() throws Exception {
        body = """
                {"featureEnabled":true,"entries":[
                  {"idempotencyKey":"harvest-s-1","status":"Applied","itemBlueprintId":41,"workerUnits":3,"townUnits":9,
                   "regenSeconds":90,"poolBefore":100,"poolAfter":96,"poolCapacity":200},
                  {"idempotencyKey":"harvest-s-2","status":"Rejected","error":"The rule is switched off.","workerUnits":0}]}
                """;
        HarvestApi.ReportResult result = api.report(List.of(
                new HarvestApi.ReportEntry("harvest-s-1", 3, 7, 5, 4, 2, Instant.parse("2026-11-21T10:00:00Z")),
                new HarvestApi.ReportEntry("harvest-s-2", 3, 8, 5, 0, -1, null))).join();

        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/Harvest/reports", seen.get(0).url().toString());
        JsonNode sent = mapper.readTree(bodies.get(0));
        JsonNode first = sent.get("entries").get(0);
        assertEquals("harvest-s-1", first.get("idempotencyKey").asText());
        assertEquals(3, first.get("userId").asInt());
        assertEquals(7, first.get("harvestRuleId").asInt());
        assertEquals(5, first.get("productionAreaId").asInt());
        assertEquals(4, first.get("breaks").asInt());
        assertEquals(2, first.get("fortuneLevel").asInt());
        assertEquals("2026-11-21T10:00:00Z", first.get("harvestedAt").asText());
        assertEquals(1, sent.get("entries").get(1).get("breaks").asInt());
        assertEquals(0, sent.get("entries").get(1).get("fortuneLevel").asInt());

        assertTrue(result.featureEnabled());
        HarvestApi.EntryResult applied = result.entries().get(0);
        assertTrue(applied.booked());
        assertEquals(41, applied.itemBlueprintId());
        assertEquals(3, applied.workerUnits());
        assertEquals(90, applied.regenSeconds());
        assertEquals(96, applied.poolAfter());
        HarvestApi.EntryResult rejected = result.entries().get(1);
        assertFalse(rejected.booked());
        assertEquals("The rule is switched off.", rejected.error());
    }

    @Test
    void report_whileTheFlagIsOff_fails409() {
        status = 409;
        body = "{\"code\":\"ProductionDisabled\"}";
        CompletionException e = assertThrows(CompletionException.class, () -> api.report(List.of(
                new HarvestApi.ReportEntry("k", 3, 7, 5, 1, 0, null))).join());
        ApiException api = assertInstanceOf(ApiException.class, e.getCause());
        assertEquals(409, api.getStatusCode());
    }
}
