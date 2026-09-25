package net.knightsandkings.knk.core.menu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parses an ActionBinding/ConditionBinding's {@code paramsJson} (a flat
 * key-value JSON object, per IMPLEMENTATION_PLAN.md's "params map... not
 * inline code") into a {@code Map<String,String>} handlers can read.
 * Deliberately separate from {@link VariableResolver} - this is plain JSON
 * deserialization, no getter-chain reflection involved. Malformed JSON is
 * logged and treated as empty params rather than aborting the click - a
 * handler that needs a specific key is expected to fail loudly itself (see
 * {@link MenuActionException}) when that key turns out to be missing.
 */
public final class MenuParams {

    private static final Logger LOGGER = Logger.getLogger(MenuParams.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MenuParams() {
    }

    public static Map<String, String> parse(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = MAPPER.readValue(paramsJson, new TypeReference<Map<String, String>>() {
            });
            return parsed != null ? parsed : Map.of();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Malformed paramsJson '" + paramsJson + "' - treating as empty params", e);
            return Map.of();
        }
    }

    /**
     * InventoryMenu Phase 9 (E3): resolves every {@code $...$} placeholder in the
     * <em>values</em> of {@code params} (keys are left alone) against
     * {@code variableScope}, uncached and in text form (see
     * {@link VariableResolver#interpolate}). Params without placeholders come
     * back unchanged. Order is preserved.
     */
    public static Map<String, String> interpolate(Map<String, String> params, Map<String, Object> variableScope) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        params.forEach((key, value) -> resolved.put(key, VariableResolver.interpolate(value, variableScope)));
        return resolved;
    }

    /** {@link #parse} then {@link #interpolate} - the one call action/condition/content-source params go through. */
    public static Map<String, String> resolve(String paramsJson, Map<String, Object> variableScope) {
        return interpolate(parse(paramsJson), variableScope);
    }
}
