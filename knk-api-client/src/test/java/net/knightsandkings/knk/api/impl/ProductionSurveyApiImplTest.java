package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.ProductionSurveyApi;
import net.knightsandkings.knk.core.production.survey.AreaShape;
import net.knightsandkings.knk.core.production.survey.AreaSpec;
import net.knightsandkings.knk.core.production.survey.SurveyDue;
import net.knightsandkings.knk.core.production.survey.SurveyProfile;
import net.knightsandkings.knk.core.production.survey.SurveyUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** PandiO/knk-workspace#679: the survey queue, the report, "survey now" and the area list (contract of #138). */
class ProductionSurveyApiImplTest {

    private static final String DUE = """
            [{"area":{"id":12,"productionStructureId":4,"productionStructure":{"id":4,"name":"North logger"},
                "name":"Forest","model":"SharedRadius","shape":"Radius","exclusive":false,"worldName":"world",
                "centerX":100,"centerY":64,"centerZ":-50,"radius":32,"minX":68,"minY":-64,"minZ":-82,"maxX":132,
                "maxY":319,"maxZ":-18,"size":32,"enabled":true},
              "productionKindId":2,"productionKindName":"Logger","surveyProfileJson":"{\\"unitTypes\\":[\\"TREE\\"]}",
              "surveyProfileVersion":3,"trigger":"Admin",
              "overlappingSharedAreas":[{"productionAreaId":15,"name":"Other forest","productionStructureId":9,
                "productionStructureName":"South logger","model":"SharedRadius","shape":"Polygon","exclusive":false,
                "points":[{"x":90,"z":-60},{"x":120,"z":-60},{"x":120,"z":-30}],"minX":90,"minY":0,"minZ":-60,
                "maxX":120,"maxY":200,"maxZ":-30}]},
             {"area":{"id":0,"productionStructureId":4,"shape":"Cuboid"},"trigger":"Scheduled"}]
            """;

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private volatile int status = 200;
    private volatile String body = DUE;
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
    private final ProductionSurveyApiImpl api = new ProductionSurveyApiImpl("http://api.test/api", client,
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
            new ApiKeyAuthProvider("secret"), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void surveyDue_readsAreaProfileTriggerAndOverlaps_andDropsAreasWithoutId() {
        List<SurveyDue> due = api.surveyDue(20).join();

        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/ProductionAreas/survey-due?limit=20", seen.get(0).url().toString());
        assertEquals("secret", seen.get(0).header("X-API-Key"));
        assertEquals(1, due.size());
        SurveyDue item = due.get(0);
        assertEquals("Admin", item.trigger());
        assertEquals(3, item.profileVersion());
        assertEquals("{\"unitTypes\":[\"TREE\"]}", item.profileJson());
        AreaSpec area = item.area();
        assertEquals(12, area.areaId());
        assertEquals("North logger", area.structureName());
        assertEquals(new AreaShape.Radius(100, -50, 32, -64, 319), area.resolve(null, -64, 320));
        AreaSpec overlap = item.overlaps().get(0);
        assertEquals(15, overlap.areaId());
        assertEquals(9, overlap.structureId());
        assertEquals("world", overlap.worldName(), "an overlap is in the surveyed area's world");
        assertTrue(overlap.resolve(null, -64, 320).contains(118, 100, -55));
        assertEquals(null, item.unitTypesOf(15), "an overlap without unit types shares every unit (before #875)");
    }

    @Test
    void surveyDue_readsEachOverlapsUnitTypes_fromUnitTypesOrItsProfile_875() {
        body = """
                [{"area":{"id":12,"productionStructureId":4,"shape":"Radius","worldName":"world","centerX":0,"centerY":64,
                    "centerZ":0,"radius":8},"surveyProfileJson":"{\\"unitTypes\\":[\\"CROP\\"]}","trigger":"Admin",
                  "overlappingSharedAreas":[
                    {"productionAreaId":15,"productionStructureId":9,"shape":"Radius","centerX":0,"centerZ":0,"radius":4,
                     "unitTypes":["TREE","sapling","NOPE"]},
                    {"productionAreaId":16,"productionStructureId":10,"shape":"Radius","centerX":0,"centerZ":0,"radius":4,
                     "surveyProfileJson":"{\\"unitTypes\\":[\\"BLOCK\\"]}"},
                    {"productionAreaId":17,"productionStructureId":11,"shape":"Radius","centerX":0,"centerZ":0,"radius":4}]}]
                """;
        SurveyDue item = api.surveyDue(5).join().get(0);
        assertEquals(java.util.EnumSet.of(SurveyProfile.UnitType.TREE, SurveyProfile.UnitType.SAPLING), item.unitTypesOf(15));
        assertEquals(java.util.EnumSet.of(SurveyProfile.UnitType.BLOCK), item.unitTypesOf(16));
        assertEquals(null, item.unitTypesOf(17));
    }

    @Test
    void report_postsTheUnitsWithTheKey_andReadsTheMaterialization() {
        body = "{\"id\":77,\"totalUnits\":13,\"materialization\":{\"linesCreated\":[1,2],\"linesUpdated\":[3]}}";
        ProductionSurveyApi.Report report = new ProductionSurveyApi.Report(Instant.parse("2026-11-19T22:00:00Z"), 3,
                "NotableChange", "abc", "blocks 10",
                List.of(new SurveyUnit("TREE:OAK:LARGE", 12, 1, null), new SurveyUnit("TREE:OAK:LARGE", 1, 2, null)));

        ProductionSurveyApi.ReportResult result = api.report(12, report).join();

        assertEquals("POST", seen.get(0).method());
        assertEquals("http://api.test/api/ProductionAreas/12/surveys", seen.get(0).url().toString());
        assertEquals("secret", seen.get(0).header("X-API-Key"));
        assertEquals("{\"surveyedAt\":\"2026-11-19T22:00:00Z\",\"profileVersion\":3,\"trigger\":\"NotableChange\","
                + "\"contentHash\":\"abc\",\"note\":\"blocks 10\",\"units\":[{\"unitKey\":\"TREE:OAK:LARGE\",\"count\":12,"
                + "\"sharedBy\":1,\"extra\":null},{\"unitKey\":\"TREE:OAK:LARGE\",\"count\":1,\"sharedBy\":2,\"extra\":null}]}",
                bodies.get(0));
        assertEquals(new ProductionSurveyApi.ReportResult(77, 13, 2, 1), result);
    }

    @Test
    void report_refusedWhileTheApiFlagIsOff_failsWith409() {
        status = 409;
        body = "{\"error\":\"Production is off\"}";
        CompletionException error = assertThrows(CompletionException.class, () -> api.report(1,
                new ProductionSurveyApi.Report(null, 1, null, "h", null, List.of())).join());
        Throwable cause = error;
        while (cause != null && !(cause instanceof ApiException)) {
            cause = cause.getCause();
        }
        assertEquals(409, ((ApiException) cause).getStatusCode());
    }

    @Test
    void requestSurveyAndAreas_useTheirRoutes() {
        body = "[{\"id\":12,\"productionStructureId\":4,\"shape\":\"Cuboid\",\"minX\":0,\"minZ\":0,\"maxX\":5,\"maxZ\":5}]";
        List<AreaSpec> requested = api.requestSurvey(4, 12).join();
        List<AreaSpec> all = api.areas().join();

        assertEquals("http://api.test/api/ProductionStructures/4/survey", seen.get(0).url().toString());
        assertEquals("POST", seen.get(0).method());
        assertEquals("{\"productionAreaId\":12}", bodies.get(0));
        assertEquals("http://api.test/api/ProductionAreas", seen.get(1).url().toString());
        assertEquals(12, requested.get(0).areaId());
        assertEquals(1, all.size());
    }
}
