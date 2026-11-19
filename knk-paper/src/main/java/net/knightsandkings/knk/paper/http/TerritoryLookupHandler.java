package net.knightsandkings.knk.paper.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import net.knightsandkings.knk.core.map.RegionOutline;
import net.knightsandkings.knk.core.territory.TerritoryLookup;
import net.knightsandkings.knk.core.territory.TerritoryService;

/**
 * P2c (PandiO/knk-workspace#677): the territory lookup the API's {@code InsideTerritory} placement rule calls, on the
 * region HTTP server behind the plugin key ({@link RegionHttpAuthFilter}).
 * <ol>
 *   <li>{@code GET /api/territory/contains-region/{regionId}?holderKind=Town&holderId=12[&world=W]}</li>
 *   <li>{@code GET /api/territory/contains-location?holderKind=Town&holderId=12&x=..&z=..[&world=W]}</li>
 * </ol>
 * Answers from the territory cache ({@link TerritoryLookup}); 503 while {@code production.enabled} is off or the cache is
 * not built, which the API shows as "not checked" (never a block). Region outlines are read on the main thread.
 */
public final class TerritoryLookupHandler implements HttpHandler {

    private static final Logger LOGGER = Logger.getLogger(TerritoryLookupHandler.class.getName());
    private static final String PREFIX = "/api/territory/";
    private static final long REGION_TIMEOUT_SECONDS = 8;

    /** A region and its sub-regions (KNG-257) as outlines, in one world; empty when there is no such region. */
    @FunctionalInterface
    public interface RegionUnionSource {
        Optional<List<RegionOutline>> union(String world, String regionId);
    }

    private final Supplier<TerritoryService> territory;
    private final RegionUnionSource regions;
    private final Supplier<String> defaultWorld;
    private final Executor main;

    /**
     * @param territory    the running territory service, or null while {@code production.enabled} is off
     * @param regions      read on {@code main}
     * @param defaultWorld the world a location without {@code world} is in (the server's first world)
     * @param main         the thread where WorldGuard may be read
     */
    public TerritoryLookupHandler(Supplier<TerritoryService> territory, RegionUnionSource regions,
                                  Supplier<String> defaultWorld, Executor main) {
        this.territory = territory;
        this.regions = regions;
        this.defaultWorld = defaultWorld;
        this.main = main;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        TerritoryLookup.Answer answer;
        try {
            answer = answer(exchange.getRequestMethod(), exchange.getRequestURI());
        } catch (RuntimeException e) {
            LOGGER.warning("[KnK Territory] Lookup " + exchange.getRequestURI() + " failed: " + e);
            answer = TerritoryLookup.Answer.unavailable("the lookup failed on the game server");
        }
        if (answer == null) {
            send(exchange, 405, "{\"inside\":null,\"available\":true,\"reason\":\"use GET\"}");
            return;
        }
        send(exchange, answer.status(), answer.json());
    }

    /** The answer for a request; null for a method other than GET. */
    TerritoryLookup.Answer answer(String method, URI uri) {
        if (!"GET".equalsIgnoreCase(method)) {
            return null;
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String rest = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
        Map<String, String> query = parseQuery(uri);
        if (rest.equals("contains-location")) {
            Object parsed = TerritoryLookup.parse(query, true);
            if (parsed instanceof TerritoryLookup.Answer bad) {
                return bad;
            }
            TerritoryLookup.Request request = (TerritoryLookup.Request) parsed;
            TerritoryService service = territory.get();
            TerritoryLookup.Answer off = TerritoryLookup.unavailable(service);
            if (off != null) {
                return off;
            }
            String world = request.world() != null ? request.world() : defaultWorld.get();
            return TerritoryLookup.location(service.cache(), request.holder(), world, request.x(), request.z());
        }
        if (rest.startsWith("contains-region/") && rest.length() > "contains-region/".length()
                && rest.indexOf('/', "contains-region/".length()) < 0) {
            String regionId = URLDecoder.decode(rest.substring("contains-region/".length()), StandardCharsets.UTF_8).trim();
            Object parsed = TerritoryLookup.parse(query, false);
            if (parsed instanceof TerritoryLookup.Answer bad) {
                return bad;
            }
            if (regionId.isEmpty()) {
                return TerritoryLookup.Answer.badRequest("regionId is required");
            }
            TerritoryLookup.Request request = (TerritoryLookup.Request) parsed;
            TerritoryService service = territory.get();
            TerritoryLookup.Answer off = TerritoryLookup.unavailable(service);
            if (off != null) {
                return off;
            }
            Optional<List<RegionOutline>> union;
            try {
                union = CompletableFuture.supplyAsync(() -> regions.union(request.world(), regionId), main)
                        .get(REGION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.warning("[KnK Territory] Lookup: region " + regionId + " could not be read: " + e);
                return TerritoryLookup.Answer.unavailable("the region could not be read on the game server");
            }
            if (union.isEmpty() || union.get().isEmpty()) {
                return TerritoryLookup.Answer.notFound("region " + regionId + " not found"
                        + (request.world() != null ? " in " + request.world() : ""));
            }
            return TerritoryLookup.region(service.cache(), request.holder(), union.get());
        }
        return TerritoryLookup.Answer.notFound(
                "expected /api/territory/contains-region/{regionId} or /api/territory/contains-location");
    }

    static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return map;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                try {
                    map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    map.put(kv[0], kv[1]);
                }
            }
        }
        return map;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
