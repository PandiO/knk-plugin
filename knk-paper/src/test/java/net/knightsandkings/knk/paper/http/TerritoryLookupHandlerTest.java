package net.knightsandkings.knk.paper.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.map.OutlinePoint;
import net.knightsandkings.knk.core.map.RegionOutline;
import net.knightsandkings.knk.core.ports.api.TerritoryApi;
import net.knightsandkings.knk.core.territory.TerritoryHolder;
import net.knightsandkings.knk.core.territory.TerritoryInputs;
import net.knightsandkings.knk.core.territory.TerritoryReport;
import net.knightsandkings.knk.core.territory.TerritoryService;
import net.knightsandkings.knk.core.territory.TerritorySettings;

/**
 * P2c (PandiO/knk-workspace#677): the territory lookup routes on the region HTTP server, as the API's
 * {@code TerritoryLookupClient} calls them (plugin key, {@code holderKind}/{@code holderId}, {@code true}/{@code false}
 * as JSON {@code inside}, 503 while territory is off).
 */
class TerritoryLookupHandlerTest {

    private static final String KEY = "s3cret-key";
    private static final String WORLD = "world";

    private final HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private final AtomicReference<TerritoryService> territory = new AtomicReference<>();
    private final List<String> regionReads = new ArrayList<>();
    private RegionHttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    private static RegionOutline cuboid(String id, int minX, int minZ, int maxX, int maxZ) {
        return new RegionOutline(id, WORLD, List.of(new OutlinePoint(minX, minZ), new OutlinePoint(maxX, minZ),
                new OutlinePoint(maxX, maxZ), new OutlinePoint(minX, maxZ)), 0, 255);
    }

    /** Phoenix (town 1): district d-market (0..15, 0..15), range 40. */
    private static TerritoryService builtTerritory() {
        Map<String, RegionOutline> regions = Map.of("d-market", cuboid("d-market", 0, 0, 15, 15));
        TerritoryInputs inputs = new TerritoryInputs(true, "v1", Map.of(1, "Phoenix"), Map.of(),
                List.of(new TerritoryInputs.Source(TerritoryInputs.SourceType.DISTRICT, 10, "Market", WORLD, "d-market",
                        List.of(), TerritoryHolder.town(1), false, 40)), List.of());
        TerritoryApi api = new TerritoryApi() {
            public CompletableFuture<TerritoryInputs> fetchInputs() {
                return CompletableFuture.completedFuture(inputs);
            }

            public CompletableFuture<TerritoryReport.Result> report(TerritoryReport report) {
                return CompletableFuture.completedFuture(null);
            }
        };
        TerritoryService service = new TerritoryService(new TerritorySettings(true, 10, false, false, false, 0, Map.of()),
                api, (world, regionId) -> Optional.ofNullable(regions.get(regionId)), null, null, Runnable::run,
                Runnable::run, Logger.getAnonymousLogger());
        service.refresh("test", false).join();
        return service;
    }

    private InetSocketAddress start() throws Exception {
        server = new RegionHttpServer(mock(Plugin.class), null, "127.0.0.1", 0, KEY);
        Map<String, List<RegionOutline>> unions = Map.of(
                "quarry", List.of(cuboid("quarry", 20, 0, 30, 10)),
                "outpost", List.of(cuboid("outpost", 300, 300, 310, 310)),
                "garrison", List.of(cuboid("garrison", 20, 0, 25, 5), cuboid("garrison-tower", 200, 0, 205, 5)));
        server.setTerritoryLookup(new TerritoryLookupHandler(territory::get, (world, regionId) -> {
            regionReads.add(world + "/" + regionId);
            return Optional.ofNullable(unions.get(regionId));
        }, () -> WORLD, Runnable::run));
        server.start();
        return server.getAddress();
    }

    private HttpResponse<String> get(InetSocketAddress address, String path, String apiKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + address.getPort() + path)).GET();
        if (apiKey != null) {
            request.header("X-API-Key", apiKey);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void withProductionOff_bothRoutesAnswer503_territoryUnavailable_withoutReadingARegion() throws Exception {
        InetSocketAddress address = start();

        HttpResponse<String> region = get(address, "/api/territory/contains-region/quarry?holderKind=Town&holderId=1", KEY);
        HttpResponse<String> location = get(address,
                "/api/territory/contains-location?holderKind=Town&holderId=1&x=20&z=5", KEY);

        assertEquals(503, region.statusCode());
        assertEquals(503, location.statusCode());
        assertTrue(region.body().contains("production.enabled: false"), region.body());
        assertTrue(region.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(regionReads.isEmpty());
    }

    @Test
    void aRegion_insideItsTownsTerritory_isTrue_outsideOrPartlyOutside_isFalse() throws Exception {
        territory.set(builtTerritory());
        InetSocketAddress address = start();

        HttpResponse<String> quarry = get(address,
                "/api/territory/contains-region/quarry?holderKind=Town&holderId=1&world=world", KEY);
        HttpResponse<String> outpost = get(address, "/api/territory/contains-region/outpost?holderKind=Town&holderId=1", KEY);
        HttpResponse<String> garrison = get(address, "/api/territory/contains-region/garrison?holderKind=Town&holderId=1", KEY);
        HttpResponse<String> otherTown = get(address, "/api/territory/contains-region/quarry?holderKind=Town&holderId=2", KEY);

        assertEquals(200, quarry.statusCode());
        assertTrue(quarry.body().startsWith("{\"inside\":true,"), quarry.body());
        assertTrue(outpost.body().startsWith("{\"inside\":false,"), outpost.body());
        assertTrue(garrison.body().startsWith("{\"inside\":false,"), "its sub-region is outside: " + garrison.body());
        assertTrue(otherTown.body().startsWith("{\"inside\":false,"));
        assertEquals("world/quarry", regionReads.get(0));
        assertEquals("null/outpost", regionReads.get(1));
    }

    @Test
    void aLocation_isAnsweredFromTheCache_inTheDefaultWorldWithoutAWorld() throws Exception {
        territory.set(builtTerritory());
        InetSocketAddress address = start();

        HttpResponse<String> inside = get(address,
                "/api/territory/contains-location?holderKind=Town&holderId=1&x=50.5&z=8", KEY);
        HttpResponse<String> outside = get(address,
                "/api/territory/contains-location?holderKind=Town&holderId=1&x=56&z=8&world=world", KEY);
        HttpResponse<String> otherWorld = get(address,
                "/api/territory/contains-location?holderKind=Town&holderId=1&x=50&z=8&world=nether", KEY);

        assertTrue(inside.body().startsWith("{\"inside\":true,"), inside.body());
        assertTrue(outside.body().startsWith("{\"inside\":false,"), outside.body());
        assertTrue(otherWorld.body().startsWith("{\"inside\":false,"), otherWorld.body());
    }

    @Test
    void badRequests_unknownRegions_andMissingKeys_areNamed() throws Exception {
        territory.set(builtTerritory());
        InetSocketAddress address = start();

        assertEquals(401, get(address, "/api/territory/contains-region/quarry?holderKind=Town&holderId=1", null).statusCode());
        assertEquals(401, get(address, "/api/territory/contains-region/quarry?holderKind=Town&holderId=1", "wrong").statusCode());
        HttpResponse<String> badKind = get(address, "/api/territory/contains-region/quarry?holderKind=Duchy&holderId=1", KEY);
        assertEquals(400, badKind.statusCode());
        assertTrue(badKind.body().contains("holderKind"));
        assertEquals(400, get(address, "/api/territory/contains-location?holderKind=Town&holderId=1&x=1", KEY).statusCode());
        HttpResponse<String> missing = get(address, "/api/territory/contains-region/nowhere?holderKind=Town&holderId=1", KEY);
        assertEquals(404, missing.statusCode());
        assertTrue(missing.body().contains("region nowhere not found"));
        assertEquals(404, get(address, "/api/territory/somewhere-else", KEY).statusCode());
        HttpResponse<String> post = client.send(HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + address.getPort() + "/api/territory/contains-location")).header("X-API-Key", KEY)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, post.statusCode());
    }

    @Test
    void withoutTheLookupSet_theRouteIsTheCatchAll404_asBeforeP2c() throws Exception {
        server = new RegionHttpServer(mock(Plugin.class), null, "127.0.0.1", 0, KEY);
        server.start();

        assertEquals(404, get(server.getAddress(), "/api/territory/contains-location?holderKind=Town&holderId=1&x=1&z=1",
                KEY).statusCode());
    }
}
