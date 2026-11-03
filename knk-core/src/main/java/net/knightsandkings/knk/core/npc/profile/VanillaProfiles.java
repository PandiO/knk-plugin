package net.knightsandkings.knk.core.npc.profile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Code-defined defaults per vanilla mob type, keyed by the lower-cased entity type name the adapter
 * reads from the entity ({@code zombie}, {@code skeleton}, {@code creeper}, {@code spider}). Every
 * other type resolves to {@link #DEFAULT}, which carries the vanilla timings and the zombie speed.
 *
 * <p>Evidence: implementation checks report §4 (melee 20 ticks, bow 40 ticks with 20 drawing) and §10
 * (speeds zombie 0.23, skeleton/creeper 0.25, spider 0.3). Damage is left vanilla (engine-supplied)
 * for every type because the report gives no damage numbers; a creeper has no melee attack in vanilla
 * and keeps its vanilla profile for timing only.
 */
public final class VanillaProfiles {
    public static final String DEFAULT = "default";
    public static final String ZOMBIE = "zombie";
    public static final String SKELETON = "skeleton";
    public static final String CREEPER = "creeper";
    public static final String SPIDER = "spider";

    public static final double ZOMBIE_SPEED = 0.23;
    public static final double SKELETON_SPEED = 0.25;
    public static final double CREEPER_SPEED = 0.25;
    public static final double SPIDER_SPEED = 0.3;

    private VanillaProfiles() {}

    /** Combat defaults: vanilla timings for every type (the ranged values only matter to archers). */
    public static Map<String, CombatProfile> combat() {
        Map<String, CombatProfile> out = new LinkedHashMap<>();
        for (String id : new String[] {DEFAULT, ZOMBIE, SKELETON, CREEPER, SPIDER}) {
            out.put(id, CombatProfile.vanilla(id));
        }
        return Map.copyOf(out);
    }

    /** Speed defaults from the checks report §10, default limits 0.4 / 0.5. */
    public static Map<String, SpeedProfile> speed() {
        Map<String, SpeedProfile> out = new LinkedHashMap<>();
        out.put(DEFAULT, SpeedProfile.of(DEFAULT, ZOMBIE_SPEED));
        out.put(ZOMBIE, SpeedProfile.of(ZOMBIE, ZOMBIE_SPEED));
        out.put(SKELETON, SpeedProfile.of(SKELETON, SKELETON_SPEED));
        out.put(CREEPER, SpeedProfile.of(CREEPER, CREEPER_SPEED));
        out.put(SPIDER, SpeedProfile.of(SPIDER, SPIDER_SPEED));
        return Map.copyOf(out);
    }
}
