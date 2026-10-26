package net.knightsandkings.knk.core.domain.enchantment;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The combat values of the custom enchantments (PandiO/knk-workspace#151): the built-in values below, with what an
 * admin set on the enchantment definitions in knk-web-api on top. The plugin loads those at startup, every
 * {@code enchantments.tuning-refresh-minutes} and on {@code /ce reload}; until the first load (or while the API is
 * down) the built-in values apply. Effects read the values per hit, so a change applies without a restart.
 * <p>
 * Cleaving has no entry: its gate numbers and cooldowns live in config.yml ({@code gates.cleaving}).
 */
public final class EnchantmentTuningTable {
    private static final EnchantmentTuningTable INSTANCE = new EnchantmentTuningTable();

    private final Map<String, EnchantmentTuning> builtIn;
    private volatile Map<String, EnchantmentTuning> effective;
    private volatile Map<String, EnchantmentTuningOverride> overrides = Map.of();

    EnchantmentTuningTable() {
        this.builtIn = Collections.unmodifiableMap(builtInValues());
        this.effective = builtIn;
    }

    public static EnchantmentTuningTable getInstance() {
        return INSTANCE;
    }

    /**
     * The built-in values: the behaviour before #151 (chance 15 % per level, durations of level x 3 s, no cooldown on
     * the on-hit effects; 90-120 s on the right-click abilities).
     */
    static Map<String, EnchantmentTuning> builtInValues() {
        Map<String, EnchantmentTuning> map = new LinkedHashMap<>();
        // On hit: chance, no cooldown (strength: 120 s), duration level x the level-I value.
        map.put("poison", new EnchantmentTuning(0.15d, 0.15d, false, 0L, 60, 1.0d));
        map.put("wither", new EnchantmentTuning(0.15d, 0.15d, false, 0L, 40, 1.0d));
        map.put("freeze", new EnchantmentTuning(0.15d, 0.15d, false, 0L, 60, 1.0d));
        map.put("blindness", new EnchantmentTuning(1.0d, 0.0d, false, 0L, 60, 1.0d));
        map.put("confusion", new EnchantmentTuning(0.15d, 0.15d, false, 0L, 60, 1.0d));
        map.put("strength", new EnchantmentTuning(0.15d, 0.15d, true, 120_000L, 300, 0.0d));
        // Right-click abilities: always go off, cooldown per player.
        map.put("chaos", new EnchantmentTuning(1.0d, 0.0d, true, 90_000L, 0, 0.0d));
        map.put("flash_chaos", new EnchantmentTuning(1.0d, 0.0d, true, 90_000L, 0, 0.0d));
        map.put("health_boost", new EnchantmentTuning(1.0d, 0.0d, true, 120_000L, 0, 0.0d));
        map.put("armor_repair", new EnchantmentTuning(1.0d, 0.0d, true, 120_000L, 0, 0.0d));
        map.put("resistance", new EnchantmentTuning(1.0d, 0.0d, true, 120_000L, 200, 0.5d));
        map.put("invisibility", new EnchantmentTuning(1.0d, 0.0d, true, 90_000L, 200, 0.0d));
        return map;
    }

    /** The values for this enchantment id ({@code freeze}); {@link EnchantmentTuning#NONE} for one without tuning. */
    public EnchantmentTuning get(String enchantmentId) {
        if (enchantmentId == null) {
            return EnchantmentTuning.NONE;
        }
        EnchantmentTuning tuning = effective.get(enchantmentId.toLowerCase(Locale.ROOT));
        return tuning != null ? tuning : EnchantmentTuning.NONE;
    }

    /** The built-in values for this enchantment id, ignoring what an admin set. */
    public Optional<EnchantmentTuning> builtIn(String enchantmentId) {
        return enchantmentId == null ? Optional.empty() : Optional.ofNullable(builtIn.get(enchantmentId.toLowerCase(Locale.ROOT)));
    }

    /** The admin override applied to this enchantment id, if any. */
    public Optional<EnchantmentTuningOverride> override(String enchantmentId) {
        return enchantmentId == null ? Optional.empty() : Optional.ofNullable(overrides.get(enchantmentId.toLowerCase(Locale.ROOT)));
    }

    /**
     * Replaces every admin override with these (a definition no longer listed falls back to the built-in values).
     * Keys are matched like knk-web-api's {@code AbilityDefinition.MatchKey}: namespace dropped, letters and digits
     * only, case ignored ({@code knk:flash_chaos} = {@code flash_chaos}). Out-of-range values are clamped.
     *
     * @return the number of overrides that matched an enchantment of this table
     */
    public int replaceOverrides(Collection<EnchantmentTuningOverride> incoming) {
        Map<String, String> idsByMatchKey = new HashMap<>();
        for (String id : builtIn.keySet()) {
            idsByMatchKey.put(matchKey(id), id);
        }

        Map<String, EnchantmentTuningOverride> matched = new LinkedHashMap<>();
        if (incoming != null) {
            for (EnchantmentTuningOverride override : incoming) {
                if (override == null || override.key() == null) {
                    continue;
                }
                String id = idsByMatchKey.get(matchKey(override.key()));
                if (id != null) {
                    matched.put(id, clamp(override));
                }
            }
        }

        Map<String, EnchantmentTuning> next = new LinkedHashMap<>(builtIn);
        matched.forEach((id, override) -> next.put(id, builtIn.get(id).with(override)));
        this.effective = Collections.unmodifiableMap(next);
        this.overrides = Collections.unmodifiableMap(matched);
        return matched.size();
    }

    /** Drops every admin override (tests, plugin disable). */
    public void clearOverrides() {
        this.effective = builtIn;
        this.overrides = Map.of();
    }

    /** Namespace dropped, lower-case letters and digits only. */
    public static String matchKey(String key) {
        if (key == null) {
            return "";
        }
        String trimmed = key.trim();
        int colon = trimmed.indexOf(':');
        if (colon >= 0 && colon < trimmed.length() - 1) {
            trimmed = trimmed.substring(colon + 1);
        }
        StringBuilder builder = new StringBuilder(trimmed.length());
        for (char c : trimmed.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                builder.append(Character.toLowerCase(c));
            }
        }
        return builder.toString();
    }

    /** The API refuses these ranges already; clamped here too so a bad row can't break combat. */
    static EnchantmentTuningOverride clamp(EnchantmentTuningOverride o) {
        return new EnchantmentTuningOverride(
                o.key(),
                clamp(o.procChancePercent(), 0.0d, 100.0d),
                clamp(o.procChancePerLevelPercent(), -100.0d, 100.0d),
                o.cooldownEnabled(),
                o.cooldownSeconds() == null ? null : Math.max(0, Math.min(86_400, o.cooldownSeconds())),
                clamp(o.effectDurationSeconds(), 0.0d, 3_600.0d),
                clamp(o.levelMultiplier(), 0.0d, 10.0d)
        );
    }

    private static Double clamp(Double value, double min, double max) {
        if (value == null || value.isNaN()) {
            return null;
        }
        return Math.max(min, Math.min(max, value));
    }
}
