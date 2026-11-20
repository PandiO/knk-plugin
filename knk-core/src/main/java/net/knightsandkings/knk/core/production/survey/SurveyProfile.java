package net.knightsandkings.knk.core.production.survey;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What a kind's survey counts (PRODUCTION_KINDS § 2 {@code SurveyProfile}, § 3.2), read from the kind's
 * {@code surveyProfileJson}. The API seeds only {@code unitTypes}; the plugin owns the rest (L11 contract on
 * PandiO/knk-workspace#679). Every field but {@code unitTypes} is optional:
 * <pre>
 * {
 *   "unitTypes": ["TREE", "SAPLING"],        // CROP, TREE, SAPLING, BLOCK, WATER, FLOWER, ENTITY
 *   "materials": ["STONE", "COAL_ORE"],      // BLOCK: count exactly these (default: rock and every ore)
 *   "entityTypes": ["SHEEP", "COW"],         // ENTITY: count these (default: farm animals)
 *   "tree": { "smallMaxLogs": 6, "mediumMaxLogs": 20, "maxLogs": 400, "leafReach": 1 }
 * }
 * </pre>
 * Unknown unit types and fields are ignored and listed in {@link #warnings()}.
 *
 * @param unitTypes     the unit types counted
 * @param materials     BLOCK materials (empty = rock and ores)
 * @param entityTypes   ENTITY types (empty = {@link SurveyMaterials#FARM_ANIMALS})
 * @param smallMaxLogs  a tree with at most this many logs is SMALL
 * @param mediumMaxLogs ... MEDIUM; above it LARGE
 * @param maxLogs       a log cluster above this is a building, not a tree
 * @param leafReach     a log cluster is a tree when a leaves block lies this close (Chebyshev) to one of its logs
 * @param version       the profile version the API sent (reported back)
 * @param warnings      what was ignored while reading
 */
public record SurveyProfile(Set<UnitType> unitTypes, Set<String> materials, Set<String> entityTypes, int smallMaxLogs,
                            int mediumMaxLogs, int maxLogs, int leafReach, int version, List<String> warnings) {

    public static final int DEFAULT_SMALL_MAX_LOGS = 6;
    public static final int DEFAULT_MEDIUM_MAX_LOGS = 20;
    public static final int DEFAULT_MAX_LOGS = 400;
    public static final int DEFAULT_LEAF_REACH = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    public enum UnitType { CROP, TREE, SAPLING, BLOCK, WATER, FLOWER, ENTITY }

    public SurveyProfile {
        unitTypes = unitTypes == null || unitTypes.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(unitTypes));
        materials = materials == null ? Set.of() : Set.copyOf(materials);
        entityTypes = entityTypes == null ? Set.of() : Set.copyOf(entityTypes);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        smallMaxLogs = Math.max(1, smallMaxLogs);
        mediumMaxLogs = Math.max(smallMaxLogs, mediumMaxLogs);
        maxLogs = Math.max(mediumMaxLogs, maxLogs);
        leafReach = Math.max(1, Math.min(4, leafReach));
    }

    public static SurveyProfile of(Set<UnitType> unitTypes) {
        return new SurveyProfile(unitTypes, Set.of(), Set.of(), DEFAULT_SMALL_MAX_LOGS, DEFAULT_MEDIUM_MAX_LOGS,
                DEFAULT_MAX_LOGS, DEFAULT_LEAF_REACH, 0, List.of());
    }

    public boolean counts(UnitType type) {
        return unitTypes.contains(type);
    }

    /** Reads a profile; a missing or broken JSON gives a profile that counts nothing, with the reason in warnings. */
    public static SurveyProfile parse(String json, int version) {
        List<String> warnings = new ArrayList<>();
        if (json == null || json.isBlank()) {
            warnings.add("no survey profile");
            return new SurveyProfile(Set.of(), Set.of(), Set.of(), DEFAULT_SMALL_MAX_LOGS, DEFAULT_MEDIUM_MAX_LOGS,
                    DEFAULT_MAX_LOGS, DEFAULT_LEAF_REACH, version, warnings);
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            warnings.add("survey profile is not JSON: " + e.getMessage());
            return new SurveyProfile(Set.of(), Set.of(), Set.of(), DEFAULT_SMALL_MAX_LOGS, DEFAULT_MEDIUM_MAX_LOGS,
                    DEFAULT_MAX_LOGS, DEFAULT_LEAF_REACH, version, warnings);
        }
        Set<UnitType> types = EnumSet.noneOf(UnitType.class);
        for (String name : strings(root.get("unitTypes"))) {
            try {
                types.add(UnitType.valueOf(name));
            } catch (IllegalArgumentException e) {
                warnings.add("unknown unit type " + name + " ignored");
            }
        }
        JsonNode tree = root.path("tree");
        return new SurveyProfile(types, new LinkedHashSet<>(strings(root.get("materials"))),
                new LinkedHashSet<>(strings(root.get("entityTypes"))),
                tree.path("smallMaxLogs").asInt(DEFAULT_SMALL_MAX_LOGS),
                tree.path("mediumMaxLogs").asInt(DEFAULT_MEDIUM_MAX_LOGS),
                tree.path("maxLogs").asInt(DEFAULT_MAX_LOGS),
                tree.path("leafReach").asInt(DEFAULT_LEAF_REACH), version, warnings);
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    values.add(item.asText().trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return values;
    }
}
