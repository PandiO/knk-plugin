package net.knightsandkings.knk.core.siege.weapons;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Optional;

/**
 * The {@code OutputJson} the {@code SiegeWeaponSpot} world task completes with (SIEGE_WEAPONS § 4, W4, A02;
 * PandiO/knk-workspace#799). The web app's spot form spreads it over its fields ({@code applySiegeWeaponSpotResult}
 * in knk-web-app's {@code FormWizard.tsx}); the plugin does not create the spot, the form's save does. Shape:
 * <pre>
 * {"fieldName": "SiegeWeaponSpot", "status": "Success" | "Warning",
 *  "minX", "minY", "minZ", "maxX", "maxY", "maxZ",   (inclusive corners)
 *  "facing": "North" | "East" | "South" | "West", "sizeClass": "Small" | "Medium" | "Large",
 *  "wgRegionId": "..." | null, "world": "...", "warnings": ["..."], "capturedAt": epoch millis}
 * </pre>
 */
public final class SpotTaskOutput {

    public static final String TASK_TYPE = "SiegeWeaponSpot";

    private SpotTaskOutput() {
    }

    /**
     * @return the JSON, or empty when the draft is refused in its facing (A03: below the small class), so a refused
     * spot can never complete the task
     */
    public static Optional<String> toJson(SpotDraft draft, String worldName, long capturedAtMillis) {
        Optional<SpotSizeClass> sizeClass = draft.sizeClass();
        if (sizeClass.isEmpty()) {
            return Optional.empty();
        }
        SpotBounds bounds = draft.bounds();
        JsonObject root = new JsonObject();
        root.addProperty("fieldName", TASK_TYPE);
        root.addProperty("status", draft.site().hasWarnings() ? "Warning" : "Success");
        root.addProperty("minX", bounds.minX());
        root.addProperty("minY", bounds.minY());
        root.addProperty("minZ", bounds.minZ());
        root.addProperty("maxX", bounds.maxX());
        root.addProperty("maxY", bounds.maxY());
        root.addProperty("maxZ", bounds.maxZ());
        root.addProperty("facing", draft.facing().apiName());
        root.addProperty("sizeClass", sizeClass.get().apiName());
        root.addProperty("wgRegionId", draft.wgRegionId());
        root.addProperty("world", worldName);
        JsonArray warnings = new JsonArray();
        draft.site().warnings().forEach(warnings::add);
        root.add("warnings", warnings);
        root.addProperty("capturedAt", capturedAtMillis);
        return Optional.of(root.toString());
    }
}
