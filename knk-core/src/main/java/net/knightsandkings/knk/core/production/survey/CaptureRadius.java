package net.knightsandkings.knk.core.production.survey;

import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The {@code CaptureRadius} world task (PRODUCTION_KINDS § 3.3, PandiO/knk-workspace#679): an admin stands at the centre
 * of a shared area and sets its radius; the output carries the fields of knk-web-api {@code ProductionAreaDto}
 * ({@code shape}, {@code worldName}, {@code centerX/Y/Z}, {@code radius}) so the area form can spread it. The input may
 * carry a starting {@code radius} and a {@code maxRadius} (the level's cap).
 */
public final class CaptureRadius {

    public static final String TASK_TYPE = "CaptureRadius";
    public static final int DEFAULT_MAX_RADIUS = 256;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A draft: the centre block and the radius. */
    public record Draft(String worldName, int centerX, int centerY, int centerZ, int radius) {
        public AreaShape shape(int minY, int maxY) {
            return new AreaShape.Radius(centerX, centerZ, radius, minY, maxY);
        }
    }

    /** Starting values from the task's input JSON. */
    public record Input(Integer radius, int maxRadius) {
    }

    private CaptureRadius() {
    }

    public static Input parseInput(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return new Input(null, DEFAULT_MAX_RADIUS);
        }
        try {
            JsonNode root = JSON.readTree(inputJson);
            Integer radius = root.path("radius").isNumber() ? root.path("radius").asInt() : null;
            int max = root.path("maxRadius").isNumber() && root.path("maxRadius").asInt() > 0
                    ? root.path("maxRadius").asInt() : DEFAULT_MAX_RADIUS;
            return new Input(radius != null && radius > 0 ? radius : null, max);
        } catch (Exception e) {
            return new Input(null, DEFAULT_MAX_RADIUS);
        }
    }

    /** The radius typed in chat, or the reason it is refused. */
    public static Optional<String> refusal(String typed, int maxRadius) {
        Integer radius = parseRadius(typed);
        if (radius == null) {
            return Optional.of("The radius must be a whole number of blocks.");
        }
        if (radius < 1 || radius > maxRadius) {
            return Optional.of("The radius must be 1 to " + maxRadius + " blocks.");
        }
        return Optional.empty();
    }

    public static Integer parseRadius(String typed) {
        try {
            return Integer.parseInt(typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT).replace("blocks", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The world task's output JSON. */
    public static String toJson(Draft draft) {
        return JSON.createObjectNode().put("shape", "Radius").put("worldName", draft.worldName())
                .put("centerX", draft.centerX()).put("centerY", draft.centerY()).put("centerZ", draft.centerZ())
                .put("radius", draft.radius()).toString();
    }
}
