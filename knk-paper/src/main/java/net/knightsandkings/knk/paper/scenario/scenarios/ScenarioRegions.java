package net.knightsandkings.knk.paper.scenario.scenarios;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;

/**
 * WorldGuard helpers for scenarios: the scene world's region manager, regions in scene coordinates, and the call
 * knk-web-api makes to the plugin's region callback server ({@code POST /Regions/rename}, {@code RegionHttpServer}).
 * Regions live outside the scene box: remove them in a cleanup step ({@link #removeAll}).
 */
public final class ScenarioRegions {

    private ScenarioRegions() {
    }

    public static RegionManager manager(ScenarioScene scene) {
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
            .get(BukkitAdapter.adapt(scene.world()));
        if (manager == null) {
            throw new ScenarioAssertionError("WorldGuard has no region manager for world " + scene.world().getName());
        }
        return manager;
    }

    /** A cuboid between two scene positions (inclusive), added to the scene world. */
    public static ProtectedCuboidRegion cuboid(ScenarioScene scene, String id, int x1, int y1, int z1, int x2, int y2, int z2) {
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(id,
            BlockVector3.at(scene.worldX(x1), scene.worldY(y1), scene.worldZ(z1)),
            BlockVector3.at(scene.worldX(x2), scene.worldY(y2), scene.worldZ(z2)));
        manager(scene).addRegion(region);
        return region;
    }

    /**
     * A polygon through scene positions {@code xz} (pairs x, z) from scene height {@code minY} to {@code maxY}, as a
     * player draws it with {@code //sel poly}, added to the scene world.
     */
    public static ProtectedPolygonalRegion polygon(ScenarioScene scene, String id, int minY, int maxY, int... xz) {
        List<BlockVector2> points = new ArrayList<>();
        for (int i = 0; i + 1 < xz.length; i += 2) {
            points.add(BlockVector2.at(scene.worldX(xz[i]), scene.worldZ(xz[i + 1])));
        }
        ProtectedPolygonalRegion region = new ProtectedPolygonalRegion(id, points, scene.worldY(minY), scene.worldY(maxY));
        manager(scene).addRegion(region);
        return region;
    }

    /** Removes the regions that exist (a cleanup step). */
    public static void removeAll(ScenarioScene scene, String... ids) {
        RegionManager manager = manager(scene);
        for (String id : ids) {
            if (manager.hasRegion(id)) {
                manager.removeRegion(id);
            }
        }
    }

    /** Null when the region has exactly this outline (world x, z pairs in order) and Y range; else what differs. */
    public static String shapeProblem(ProtectedRegion region, List<BlockVector2> outline, int minY, int maxY) {
        if (region == null) {
            return "the region does not exist";
        }
        List<String> problems = new ArrayList<>();
        if (!region.getPoints().equals(outline)) {
            problems.add("outline " + region.getPoints() + " instead of " + outline);
        }
        if (region.getMinimumPoint().y() != minY || region.getMaximumPoint().y() != maxY) {
            problems.add("Y " + region.getMinimumPoint().y() + " to " + region.getMaximumPoint().y()
                + " instead of " + minY + " to " + maxY);
        }
        return problems.isEmpty() ? null : region.getId() + ": " + String.join("; ", problems);
    }

    /**
     * Calls the plugin's region callback server the way knk-web-api does after a form save:
     * {@code POST /Regions/rename} with the query parameters and the plugin's API key. The server runs the rename on
     * the main thread, so the scenario must not wait for the answer in the same tick: {@code await} the future.
     */
    public static CompletableFuture<HttpResponse<String>> callRename(ScenarioScene scene, Map<String, String> query) {
        var address = scene.services().regionHttp();
        StringJoiner parameters = new StringJoiner("&");
        query.forEach((key, value) -> parameters.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        String host = address.getAddress().getHostAddress();
        if (host.contains(":")) {
            host = "[" + host + "]";
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://" + host + ":" + address.getPort() + "/Regions/rename?" + parameters))
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.noBody());
        String apiKey = scene.services().config().getString("api.auth.api-key", "");
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("X-API-Key", apiKey);
        }
        return HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build()
            .sendAsync(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** "200 true" style summary of a finished call, or the failure. */
    public static String answer(CompletableFuture<HttpResponse<String>> call) {
        try {
            HttpResponse<String> response = call.join();
            return response.statusCode() + " " + response.body();
        } catch (Exception e) {
            return "failed: " + e.getMessage();
        }
    }
}
