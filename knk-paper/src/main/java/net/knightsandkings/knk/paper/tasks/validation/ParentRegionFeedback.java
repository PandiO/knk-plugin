package net.knightsandkings.knk.paper.tasks.validation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.knightsandkings.knk.core.domain.validation.ValidationResult;
import net.knightsandkings.knk.paper.utils.PlaceholderInterpolationUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * KNG-173: what a region rule from a world task's {@code validationContext.validationRules[]} (RegionContainment,
 * LocationInsideRegion) says when it fails, and how hard it fails.
 *
 * <p>Every failure of a rule follows that rule's {@code isBlocking} (missing or false = a warning), whatever the
 * cause: outside the parent, the parent's region missing, the wrong world. Every failure names what the region or
 * location must be inside, from the rule's dependency: {@code District "Kardenna Docks" (region domain_27)}.
 *
 * <p>Pure (no WorldGuard or Bukkit), so the wording and the severity are unit-tested.
 */
public final class ParentRegionFeedback {
    static final String FALLBACK_TYPE = "the parent";

    private final String entityType;
    private final String entityName;
    private final String regionId;
    private final String worldName;
    private final boolean blocking;

    ParentRegionFeedback(String entityType, String entityName, String regionId, String worldName, boolean blocking) {
        this.entityType = entityType;
        this.entityName = entityName;
        this.regionId = regionId;
        this.worldName = worldName;
        this.blocking = blocking;
    }

    /**
     * @param rule            the validation rule as sent in the task's InputJson
     * @param dependencyValue the rule's {@code dependencyFieldValue} (an entity object or just a region id)
     * @param regionId        the parent region id resolved from the dependency
     */
    public static ParentRegionFeedback of(JsonObject rule, JsonElement dependencyValue, String regionId) {
        return new ParentRegionFeedback(entityTypeLabel(rule), stringProperty(dependencyValue, "name"), regionId,
                stringProperty(dependencyValue, "worldName"), isBlocking(rule));
    }

    /** A rule blocks only when it says so; a missing or unreadable {@code isBlocking} is a warning. */
    public static boolean isBlocking(JsonObject rule) {
        JsonElement value = property(rule, "isBlocking");
        if (value == null || !value.isJsonPrimitive()) {
            return false;
        }
        try {
            return value.getAsJsonPrimitive().isBoolean()
                    ? value.getAsBoolean()
                    : Boolean.parseBoolean(value.getAsString().trim());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The kind of thing the dependency is: the dependency field's label ("District"), else the first segment of the
     * rule's dependency path ("District.WgRegionId" gives "District"), else "the parent".
     */
    static String entityTypeLabel(JsonObject rule) {
        JsonElement dependsOn = property(rule, "dependsOnField");
        if (dependsOn != null && dependsOn.isJsonObject()) {
            String label = stringProperty(dependsOn, "label");
            if (label != null) {
                return label;
            }
        }
        String path = stringProperty(rule, "dependencyPath");
        if (path != null) {
            String first = path.split("\\.", 2)[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return FALLBACK_TYPE;
    }

    public String regionId() {
        return regionId;
    }

    /** The world the dependency says its region is in, or null when it doesn't say. */
    public String worldName() {
        return worldName;
    }

    public boolean blocking() {
        return blocking;
    }

    /** {@code District "Kardenna Docks"}, or {@code the District} when the dependency has no name. */
    public String subject() {
        if (entityName != null) {
            return entityType + " \"" + entityName + "\"";
        }
        return entityType.equals(FALLBACK_TYPE) ? FALLBACK_TYPE : "the " + entityType;
    }

    /** {@code District "Kardenna Docks" (region domain_27)}. */
    public String describe() {
        return subject() + " (region " + regionId + ")";
    }

    /** {@code Must be inside District "Kardenna Docks" (region domain_27).} */
    public String mustBeInsideLine() {
        return "Must be inside " + describe() + ".";
    }

    /**
     * The placeholders a rule's own messages can use: {regionName} and {parentRegionName} (the region id),
     * {parentEntityName} (the dependency's name, else the region id) and {parentEntityType}.
     */
    public JsonObject placeholders() {
        JsonObject placeholders = new JsonObject();
        placeholders.addProperty("regionName", regionId);
        placeholders.addProperty("parentRegionName", regionId);
        placeholders.addProperty("parentEntityName", entityName != null ? entityName : regionId);
        placeholders.addProperty("parentEntityType", entityType);
        return placeholders;
    }

    /**
     * Fills a rule's message: the pre-resolved placeholders from the API, overridden by {@link #placeholders()},
     * overridden by {@code computed} (e.g. coordinates or violations; may be null).
     */
    public String interpolate(String template, JsonObject preResolved, JsonObject computed) {
        JsonObject all = PlaceholderInterpolationUtil.mergePlaceholders(
                PlaceholderInterpolationUtil.mergePlaceholders(preResolved, placeholders()), computed);
        return PlaceholderInterpolationUtil.interpolate(template, all);
    }

    /**
     * The region or location is (partly) outside the parent region.
     *
     * @param ruleMessage the rule's filled-in error message (skipped when blank)
     * @param detail      an extra line such as the corners outside it (skipped when null or blank)
     */
    public ValidationResult notInside(String ruleMessage, String detail) {
        List<String> lines = new ArrayList<>();
        if (ruleMessage != null && !ruleMessage.isBlank()) {
            lines.add(ruleMessage.trim());
        }
        lines.add(mustBeInsideLine());
        if (detail != null && !detail.isBlank()) {
            lines.add(detail.trim());
        }
        return failure(String.join("\n", lines));
    }

    /**
     * The dependency names a region that the searched world doesn't have, so nothing could be checked. When the
     * dependency names another world, that world wasn't loaded (it is searched first when it is), which is said too.
     */
    public ValidationResult parentRegionMissing(String searchedWorld) {
        String unloaded = worldName != null && !worldName.equals(searchedWorld)
                ? " (its own world " + worldName + " isn't loaded)"
                : "";
        return failure(capitalize(subject()) + " has region " + regionId + ", but that region doesn't exist in world "
                + searchedWorld + unloaded + ", so the check couldn't run.");
    }

    /** WorldGuard has no region manager for the world, so nothing could be checked. */
    public ValidationResult noRegionData(String world) {
        return failure("WorldGuard has no region data for world " + world + ", so the check against " + describe()
                + " couldn't run.");
    }

    /**
     * The region or location is in another world than the parent region.
     *
     * @param what        what is being checked, starting a sentence ("Your selection", "This location")
     * @param actualWorld the world it is in
     * @param parentWorld the parent region's world
     */
    public ValidationResult wrongWorld(String what, String actualWorld, String parentWorld) {
        return failure(what + " is in world " + actualWorld + ", but " + describe() + " is in world " + parentWorld
                + ".");
    }

    private ValidationResult failure(String message) {
        return new ValidationResult(false, message, blocking);
    }

    /**
     * One result for all of a task's rules, so a warning from an earlier rule can't hide a later rule's block: the
     * blocking failures if there are any, else the warnings (their messages one after another), else a success.
     */
    public static ValidationResult combine(List<ValidationResult> results) {
        List<String> blocking = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (ValidationResult result : results) {
            if (result == null || result.isValid()) {
                continue;
            }
            (result.isBlocking() ? blocking : warnings).add(result.getMessage() == null ? "" : result.getMessage());
        }
        if (!blocking.isEmpty()) {
            return new ValidationResult(false, String.join("\n", blocking), true);
        }
        if (!warnings.isEmpty()) {
            return new ValidationResult(false, String.join("\n", warnings), false);
        }
        return ValidationResult.success("All validations passed");
    }

    /**
     * The chat lines for a failed validation: a block says it failed and gives {@code blockedAdvice}; a warning says
     * it is only a warning and gives {@code warningAdvice}. A passed validation gives no lines.
     */
    public static List<String> chatLines(ValidationResult result, List<String> blockedAdvice, String warningAdvice) {
        List<String> lines = new ArrayList<>();
        if (result == null || result.isValid()) {
            return lines;
        }
        String color = result.isBlocking() ? "§c" : "§e";
        lines.add(result.isBlocking() ? "§c✗ Validation failed:" : "§e⚠ Validation warning:");
        String message = result.getMessage() == null ? "" : result.getMessage();
        for (String line : message.split("\n")) {
            if (!line.isBlank()) {
                lines.add(color + line);
            }
        }
        if (result.isBlocking()) {
            for (String advice : blockedAdvice) {
                lines.add("§e" + advice);
            }
        } else {
            lines.add("§7" + warningAdvice);
        }
        return lines;
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    /** A property by name, ignoring case (the API sends camelCase, older payloads PascalCase). */
    private static JsonElement property(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) {
            return null;
        }
        JsonObject object = value.getAsJsonObject();
        if (object.has(name)) {
            return object.get(name);
        }
        for (String key : object.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return object.get(key);
            }
        }
        return null;
    }

    /** A non-blank string property, trimmed, or null. */
    private static String stringProperty(JsonElement value, String name) {
        JsonElement property = property(value, name);
        if (property == null || !property.isJsonPrimitive()) {
            return null;
        }
        String text = property.getAsString().trim();
        return text.isEmpty() ? null : text;
    }
}
