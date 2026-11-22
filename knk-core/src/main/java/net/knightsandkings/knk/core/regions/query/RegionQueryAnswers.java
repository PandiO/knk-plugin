package net.knightsandkings.knk.core.regions.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.knightsandkings.knk.core.siege.weapons.SpotSiteCheck;

/**
 * PandiO/knk-workspace#988: the bodies of the hub answers {@code RegionGeometryResult} and {@code SpotSiteCheckResult}
 * ({@code RegionGeometryResultDto} / {@code SpotSiteCheckResultDto} in knk-web-api, camelCase). Outcomes as
 * {@code RegionQueryEvents}: {@code found}, {@code not-found}, {@code error}.
 */
public final class RegionQueryAnswers {

    public static final String FOUND = "found";
    public static final String NOT_FOUND = "not-found";
    public static final String ERROR = "error";

    private RegionQueryAnswers() {
    }

    public static Map<String, Object> geometry(String queryId, RegionShape shape) {
        Map<String, Object> body = head(queryId, FOUND, "Region found in " + shape.world() + ".");
        body.put("world", shape.world());
        body.put("shape", shape.kind());
        body.put("minX", shape.minX());
        body.put("minY", shape.minY());
        body.put("minZ", shape.minZ());
        body.put("maxX", shape.maxX());
        body.put("maxY", shape.maxY());
        body.put("maxZ", shape.maxZ());
        List<Map<String, Object>> points = new ArrayList<>();
        for (int[] p : shape.points()) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("x", p[0]);
            point.put("z", p[1]);
            points.add(point);
        }
        body.put("points", points);
        return body;
    }

    public static Map<String, Object> siteCheck(String queryId, SpotSiteCheck.Result result) {
        Map<String, Object> body = head(queryId, FOUND,
                result.hasWarnings() ? "The site has warnings." : "The floor is solid and the space is clear.");
        body.put("floorBlocks", result.floorBlocks());
        body.put("openFloor", result.openFloor());
        body.put("volumeChecked", result.volumeChecked());
        body.put("blockedVolume", result.blockedVolume());
        body.put("warnings", result.warnings());
        return body;
    }

    public static Map<String, Object> notFound(String queryId, String regionId, String world) {
        return head(queryId, NOT_FOUND, "No WorldGuard region '" + regionId + "'"
                + (world == null ? " in any loaded world." : " in " + world + "."));
    }

    public static Map<String, Object> error(String queryId, String message) {
        return head(queryId, ERROR, message);
    }

    private static Map<String, Object> head(String queryId, String result, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("queryId", queryId);
        body.put("result", result);
        body.put("message", message);
        return body;
    }
}
