package net.knightsandkings.knk.core.npc.profile;

import net.knightsandkings.knk.core.npc.NpcStat;
import net.knightsandkings.knk.core.npc.StatBounds;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Bukkit-free configuration overrides for profiles: a map of profile id → (key → value) that a later
 * link fills from {@code config.yml} (or any other source) and {@link ProfileRegistry#withOverrides}
 * applies. No YAML here; values are what a config reader hands over ({@link Number}, or a
 * {@link String} holding a number, {@code "vanilla"} for a damage field, or a profile id for
 * {@code based-on}). Unknown keys and bad values are <em>rejected with a reason</em>, never thrown,
 * so one typo does not take the whole profile set down.
 *
 * <p>Keys per profile (an unknown profile id creates a new profile from {@code based-on}, default
 * {@code "default"}):
 * <pre>
 * based-on                                  profile id to copy for a new profile (combat and speed)
 * combat.melee-cooldown-ticks               int ≥ 0
 * combat.melee-damage                       number ≥ 0 or "vanilla"
 * combat.ranged-cooldown-ticks              int ≥ 0
 * combat.ranged-draw-ticks                  int ≥ 0
 * combat.projectile-damage                  number ≥ 0 or "vanilla"
 * combat.targets.&lt;type&gt;.&lt;field&gt;      the five fields above, per target type (player, objective, gate, ...)
 * combat.bounds.&lt;stat-id&gt;.floor|ceiling  number; stat ids: melee-cooldown-ticks, melee-damage,
 *                                           ranged-cooldown-ticks, ranged-draw-ticks, projectile-damage
 * speed.base-speed                          number ≥ 0
 * speed.comfort-limit                       number ≥ 0 (≤ hard cap)
 * speed.hard-cap                            number ≥ 0
 * speed.factors.&lt;key&gt;                     number ≥ 0 (terrain / movement-profile multiplier)
 * </pre>
 * Keys are matched case-insensitively. Bounds that would cross (floor above ceiling) or a comfort
 * limit above the hard cap are rejected and the previous value kept.
 */
public record ProfileOverrides(Map<String, Map<String, Object>> byProfile) {

    public static final ProfileOverrides NONE = new ProfileOverrides(Map.of());

    public static final String BASED_ON = "based-on";
    public static final String VANILLA = "vanilla";

    static final String COMBAT_PREFIX = "combat.";
    static final String SPEED_PREFIX = "speed.";
    static final String TARGETS_PREFIX = "targets.";
    static final String BOUNDS_PREFIX = "bounds.";
    static final String FACTORS_PREFIX = "factors.";

    public ProfileOverrides {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        if (byProfile != null) {
            byProfile.forEach((id, entries) -> {
                if (id == null || id.isBlank() || entries == null) return;
                Map<String, Object> normalized = new LinkedHashMap<>();
                entries.forEach((key, value) -> {
                    if (key != null && value != null) normalized.put(key.trim().toLowerCase(Locale.ROOT), value);
                });
                copy.put(id.trim().toLowerCase(Locale.ROOT), Map.copyOf(normalized));
            });
        }
        byProfile = Map.copyOf(copy);
    }

    public boolean isEmpty() {
        return byProfile.isEmpty();
    }

    /** The {@code based-on} id for a profile, or {@code fallback}. */
    String basedOn(String profileId, String fallback) {
        Object v = byProfile.getOrDefault(profileId, Map.of()).get(BASED_ON);
        return v == null ? fallback : v.toString().trim().toLowerCase(Locale.ROOT);
    }

    /** Applies the {@code combat.*} keys of {@code profileId} to {@code base}; rejections appended as {@code profile/key: reason}. */
    CombatProfile applyCombat(String profileId, CombatProfile base, List<String> rejected) {
        CombatProfile p = base.withId(profileId);
        for (Map.Entry<String, Object> e : byProfile.getOrDefault(profileId, Map.of()).entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(COMBAT_PREFIX)) continue;
            String field = key.substring(COMBAT_PREFIX.length());
            Object value = e.getValue();
            try {
                if (field.startsWith(TARGETS_PREFIX)) {
                    p = applyTarget(p, field.substring(TARGETS_PREFIX.length()), value);
                } else if (field.startsWith(BOUNDS_PREFIX)) {
                    p = applyBounds(p, field.substring(BOUNDS_PREFIX.length()), value);
                } else {
                    p = applyField(p, field, value);
                }
            } catch (IllegalArgumentException ex) {
                rejected.add(profileId + "/" + key + ": " + ex.getMessage());
            }
        }
        return p;
    }

    /** Applies the {@code speed.*} keys of {@code profileId} to {@code base}. */
    SpeedProfile applySpeed(String profileId, SpeedProfile base, List<String> rejected) {
        SpeedProfile p = base.withId(profileId);
        for (Map.Entry<String, Object> e : byProfile.getOrDefault(profileId, Map.of()).entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(SPEED_PREFIX)) continue;
            String field = key.substring(SPEED_PREFIX.length());
            Object value = e.getValue();
            try {
                if (field.startsWith(FACTORS_PREFIX)) {
                    String factorKey = field.substring(FACTORS_PREFIX.length());
                    if (factorKey.isBlank()) throw new IllegalArgumentException("factor key must not be blank");
                    p = p.withFactor(factorKey, nonNegative(value));
                } else {
                    p = switch (field) {
                        case "base-speed" -> p.withBaseSpeed(nonNegative(value));
                        case "comfort-limit" -> p.withComfortLimit(nonNegative(value));
                        case "hard-cap" -> p.withHardCap(nonNegative(value));
                        default -> throw new IllegalArgumentException("unknown speed key");
                    };
                }
            } catch (IllegalArgumentException ex) {
                rejected.add(profileId + "/" + key + ": " + ex.getMessage());
            }
        }
        return p;
    }

    /** Collects the keys of a profile that belong to neither section nor {@code based-on}. */
    void rejectUnknownKeys(String profileId, List<String> rejected) {
        for (String key : byProfile.getOrDefault(profileId, Map.of()).keySet()) {
            if (!key.equals(BASED_ON) && !key.startsWith(COMBAT_PREFIX) && !key.startsWith(SPEED_PREFIX)) {
                rejected.add(profileId + "/" + key + ": unknown key (expected based-on, combat.* or speed.*)");
            }
        }
    }

    private static CombatProfile applyField(CombatProfile p, String field, Object value) {
        return switch (field) {
            case "melee-cooldown-ticks" -> p.withMeleeCooldownTicks(ticks(value));
            case "melee-damage" -> p.withMeleeDamage(damage(value));
            case "ranged-cooldown-ticks" -> p.withRangedCooldownTicks(ticks(value));
            case "ranged-draw-ticks" -> p.withDrawTicks(ticks(value));
            case "projectile-damage" -> p.withProjectileDamage(damage(value));
            default -> throw new IllegalArgumentException("unknown combat key");
        };
    }

    private static CombatProfile applyTarget(CombatProfile p, String rest, Object value) {
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot == rest.length() - 1) throw new IllegalArgumentException("expected targets.<type>.<field>");
        String type = rest.substring(0, dot);
        String field = rest.substring(dot + 1);
        TargetOverride existing = p.targetOverrides().getOrDefault(type, TargetOverride.NONE);
        TargetOverride updated = switch (field) {
            case "melee-cooldown-ticks" -> existing.withMeleeCooldownTicks(ticks(value));
            case "melee-damage" -> withOptionalDamage(existing, field, damage(value));
            case "ranged-cooldown-ticks" -> existing.withRangedCooldownTicks(ticks(value));
            case "ranged-draw-ticks" -> existing.withDrawTicks(ticks(value));
            case "projectile-damage" -> withOptionalDamage(existing, field, damage(value));
            default -> throw new IllegalArgumentException("unknown target field");
        };
        return p.withTargetOverride(type, updated);
    }

    private static TargetOverride withOptionalDamage(TargetOverride o, String field, OptionalDouble damage) {
        boolean melee = field.equals("melee-damage");
        if (damage.isEmpty()) {
            // "vanilla" on a target override means: no override for that field.
            return new TargetOverride(o.meleeCooldownTicks(), melee ? OptionalDouble.empty() : o.meleeDamage(),
                o.rangedCooldownTicks(), o.drawTicks(), melee ? o.projectileDamage() : OptionalDouble.empty());
        }
        return melee ? o.withMeleeDamage(damage.getAsDouble()) : o.withProjectileDamage(damage.getAsDouble());
    }

    private static CombatProfile applyBounds(CombatProfile p, String rest, Object value) {
        int dot = rest.lastIndexOf('.');
        if (dot <= 0 || dot == rest.length() - 1) throw new IllegalArgumentException("expected bounds.<stat-id>.floor|ceiling");
        NpcStat stat = NpcStat.builtIn(rest.substring(0, dot));
        if (stat == null || stat.equals(NpcStat.MOVEMENT_SPEED)) {
            throw new IllegalArgumentException("unknown combat stat '" + rest.substring(0, dot) + "'");
        }
        String side = rest.substring(dot + 1);
        double limit = number(value);
        StatBounds current = p.boundsFor(stat);
        StatBounds updated = switch (side) {
            case "floor" -> current.withFloor(limit);
            case "ceiling" -> current.withCeiling(limit);
            default -> throw new IllegalArgumentException("expected floor or ceiling, got '" + side + "'");
        };
        return p.withBounds(stat, updated);
    }

    private static int ticks(Object value) {
        double d = nonNegative(value);
        if (d != Math.rint(d)) throw new IllegalArgumentException("expected a whole number of ticks, got " + value);
        if (d > Integer.MAX_VALUE) throw new IllegalArgumentException("too many ticks: " + value);
        return (int) d;
    }

    private static OptionalDouble damage(Object value) {
        if (value instanceof String s && s.trim().equalsIgnoreCase(VANILLA)) return OptionalDouble.empty();
        return OptionalDouble.of(nonNegative(value));
    }

    private static double nonNegative(Object value) {
        double d = number(value);
        if (d < 0) throw new IllegalArgumentException("must be ≥ 0, got " + value);
        return d;
    }

    private static double number(Object value) {
        Objects.requireNonNull(value, "value");
        double d;
        if (value instanceof Number n) {
            d = n.doubleValue();
        } else if (value instanceof String s) {
            try {
                d = Double.parseDouble(s.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("expected a number, got '" + s + "'");
            }
        } else {
            throw new IllegalArgumentException("expected a number, got " + value.getClass().getSimpleName());
        }
        if (Double.isNaN(d)) throw new IllegalArgumentException("expected a number, got NaN");
        return d;
    }
}
