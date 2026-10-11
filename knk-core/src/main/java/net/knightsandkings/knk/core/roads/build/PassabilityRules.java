package net.knightsandkings.knk.core.roads.build;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The one pure definition of "passable", "solid", "overlay" and "hazard" over material names for the
 * road builder (plan Phase 2c). {@code ChunkSnapshot} has no {@code isPassable} and
 * {@code Block#isPassable} is main-thread only, so the build classifies by name:
 *
 * <ul>
 *   <li><b>overlay</b> — a thin block lying on the road (DESIGN §5.1 role {@code Overlay}, config
 *       {@code overlay-materials}): passable headroom, never a floor; the road cell is the block beneath.</li>
 *   <li><b>passable</b> — a player can stand in the block: it has no collision box, or it is an overlay.</li>
 *   <li><b>solid</b> — a player can stand on the block: it has a collision box and is not an overlay.
 *       Passable and solid are exact complements, which is what makes spans at y-1, y and y+1 of one
 *       column mutually exclusive (DESIGN §5.2).</li>
 *   <li><b>hazard</b> — teleport's {@code SafeLocationFinder.HAZARD_MATERIALS} (reuse map R22); Phase 4
 *       unifies the two lists when the teleport branch lands.</li>
 * </ul>
 *
 * <p>"Has a collision box" is {@code Material#isCollidable()} on the paper side ({@link #of(Predicate)});
 * knk-core cannot see {@code Material}, so {@link #defaults()} carries a curated list of the common
 * non-collidable blocks for tests and as a fallback. Phase 3 must verify {@code Material#isCollidable()}
 * on 1.21.10 and prefer it; the curated list is the reversible default.
 *
 * <p>Names are compared exactly as {@code Material.name()} spells them (upper case). Overlay patterns
 * may start with {@code *} (config style: {@code *_CARPET}).
 *
 * <p>The static walk helpers at the end ({@link #isWalkFloor}, {@link #isHandOpenableDoor},
 * {@link #isWater}) serve the last-mile walk search (KNG-51 {@code LAST_MILE_PATHFINDING.md} §4); they
 * do not change what is passable or solid for the builder.
 */
public final class PassabilityRules {

    /** DESIGN §4 {@code overlay-materials} default (plus the other rail variants and moss carpets). */
    public static final List<String> DEFAULT_OVERLAY_PATTERNS = List.of(
        "SNOW", "*_CARPET", "*_PRESSURE_PLATE", "RAIL", "POWERED_RAIL", "DETECTOR_RAIL", "ACTIVATOR_RAIL",
        "LEAF_LITTER", "PINK_PETALS", "WILDFLOWERS"
    );

    /** Teleport's hazard set, verbatim ({@code SafeLocationFinder.HAZARD_MATERIALS} on {@code claude/teleport}). */
    public static final Set<String> HAZARD_MATERIALS = Set.of(
        "LAVA", "MAGMA_BLOCK", "FIRE", "SOUL_FIRE", "CAMPFIRE", "SOUL_CAMPFIRE", "CACTUS",
        "SWEET_BERRY_BUSH", "POWDER_SNOW", "WITHER_ROSE", "POINTED_DRIPSTONE"
    );

    /**
     * Curated non-collidable materials (exact names) for {@link #defaults()}: air, fluids, plants,
     * redstone parts, signs, banners and the like. Not exhaustive — the paper side uses
     * {@code Material#isCollidable()}.
     */
    static final Set<String> CURATED_NON_COLLIDABLE = Set.of(
        "AIR", "CAVE_AIR", "VOID_AIR", "WATER", "LAVA", "BUBBLE_COLUMN", "LIGHT", "STRUCTURE_VOID",
        "SHORT_GRASS", "GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH", "SEAGRASS", "TALL_SEAGRASS",
        "KELP", "KELP_PLANT", "BAMBOO_SAPLING", "SUGAR_CANE", "VINE", "GLOW_LICHEN", "SCULK_VEIN",
        "HANGING_ROOTS", "SPORE_BLOSSOM", "SMALL_DRIPLEAF", "CAVE_VINES", "CAVE_VINES_PLANT",
        "WEEPING_VINES", "WEEPING_VINES_PLANT", "TWISTING_VINES", "TWISTING_VINES_PLANT",
        "WARPED_ROOTS", "CRIMSON_ROOTS", "NETHER_SPROUTS", "WARPED_FUNGUS", "CRIMSON_FUNGUS",
        "BROWN_MUSHROOM", "RED_MUSHROOM", "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART",
        "MELON_STEM", "PUMPKIN_STEM", "ATTACHED_MELON_STEM", "ATTACHED_PUMPKIN_STEM", "TORCHFLOWER_CROP",
        "PITCHER_CROP", "SWEET_BERRY_BUSH", "COBWEB", "FIRE", "SOUL_FIRE", "POWDER_SNOW", "FROGSPAWN",
        "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "OXEYE_DAISY", "CORNFLOWER",
        "LILY_OF_THE_VALLEY", "WITHER_ROSE", "TORCHFLOWER", "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY",
        "PITCHER_PLANT", "CLOSED_EYEBLOSSOM", "OPEN_EYEBLOSSOM",
        "TORCH", "WALL_TORCH", "SOUL_TORCH", "SOUL_WALL_TORCH", "REDSTONE_TORCH", "REDSTONE_WALL_TORCH",
        "REDSTONE_WIRE", "LEVER", "TRIPWIRE", "TRIPWIRE_HOOK", "LADDER", "REPEATER", "COMPARATOR"
    );

    /** Curated non-collidable name suffixes for {@link #defaults()} (saplings, tulips, buttons, signs, banners, coral). */
    static final List<String> CURATED_NON_COLLIDABLE_SUFFIXES = List.of(
        "_SAPLING", "_TULIP", "_BUTTON", "_SIGN", "_BANNER", "_CORAL_FAN", "_CORAL_WALL_FAN", "_CORAL", "_PROPAGULE"
    );

    private final Predicate<String> collidable;
    private final Predicate<String> overlay;

    private PassabilityRules(Predicate<String> collidable, Predicate<String> overlay) {
        this.collidable = collidable;
        this.overlay = overlay;
    }

    /** The curated collision table and the DESIGN §4 overlay list (tests, fallback). */
    public static PassabilityRules defaults() {
        return of(PassabilityRules::curatedCollidable, DEFAULT_OVERLAY_PATTERNS);
    }

    /**
     * Rules over a real collision test (paper: {@code name -> Material.valueOf(name).isCollidable()})
     * and the DESIGN §4 overlay list.
     */
    public static PassabilityRules of(Predicate<String> collidable) {
        return of(collidable, DEFAULT_OVERLAY_PATTERNS);
    }

    /**
     * Rules over a real collision test and a configured overlay list (config {@code overlay-materials};
     * a pattern may start with {@code *}, e.g. {@code *_CARPET}).
     */
    public static PassabilityRules of(Predicate<String> collidable, Collection<String> overlayPatterns) {
        Objects.requireNonNull(collidable, "collidable");
        return new PassabilityRules(collidable, overlayPredicate(overlayPatterns));
    }

    /** A predicate over material names for a list of exact names or {@code *SUFFIX} patterns. */
    public static Predicate<String> overlayPredicate(Collection<String> patterns) {
        Objects.requireNonNull(patterns, "patterns");
        List<String> normalised = patterns.stream()
            .map(p -> p.trim().toUpperCase(Locale.ROOT))
            .filter(p -> !p.isEmpty())
            .toList();
        return name -> {
            for (String pattern : normalised) {
                if (matchesPattern(name, pattern)) {
                    return true;
                }
            }
            return false;
        };
    }

    /** Exact match, or suffix match when the pattern starts with {@code *}. */
    public static boolean matchesPattern(String name, String pattern) {
        if (pattern.startsWith("*")) {
            return name.endsWith(pattern.substring(1));
        }
        return name.equals(pattern);
    }

    /** The curated "has a collision box" test (everything not in the curated non-collidable lists). */
    public static boolean curatedCollidable(String material) {
        if (CURATED_NON_COLLIDABLE.contains(material)) {
            return false;
        }
        if (material.endsWith("_CORAL_BLOCK")) {
            return true; // before the _CORAL suffix rule below
        }
        for (String suffix : CURATED_NON_COLLIDABLE_SUFFIXES) {
            if (material.endsWith(suffix)) {
                return false;
            }
        }
        return true;
    }

    /** A thin block lying on the road (never a floor, always passable). */
    public boolean isOverlay(String material) {
        return overlay.test(Objects.requireNonNull(material, "material"));
    }

    /** A player can stand in this block. */
    public boolean isPassable(String material) {
        return !collidable.test(Objects.requireNonNull(material, "material")) || overlay.test(material);
    }

    /** A player can stand on this block; the exact complement of {@link #isPassable}. */
    public boolean isSolid(String material) {
        return !isPassable(material);
    }

    /** Standing in or on this block hurts. */
    public boolean isHazard(String material) {
        return HAZARD_MATERIALS.contains(Objects.requireNonNull(material, "material"));
    }

    /** A stair or slab block (by name), which a player steps onto without jumping. */
    public static boolean isStairOrSlab(String material) {
        return material.endsWith("_STAIRS") || material.endsWith("_SLAB");
    }

    // ===== walk search (KNG-51 LAST_MILE_PATHFINDING.md §4) =====

    /**
     * Name suffixes of blocks that are never a walk floor: a 1.5-high collision box (fences, walls,
     * fence gates) or too thin to stand on (panes). They are already non-passable for the cell above,
     * so they correctly block movement; this keeps the search from "standing" on top of them.
     */
    public static final List<String> NEVER_FLOOR_SUFFIXES = List.of("_FENCE", "_WALL", "_FENCE_GATE", "_PANE");

    /** Exact names that are never a walk floor: iron bars, and the redstone-only iron door and trapdoor. */
    public static final Set<String> NEVER_FLOOR_MATERIALS = Set.of("IRON_BARS", "IRON_DOOR", "IRON_TRAPDOOR");

    /** Water by name: a cell whose feet block is water is a wading cell (§4). Waterlogged blocks are not visible by name. */
    public static final Set<String> WATER_MATERIALS = Set.of("WATER", "BUBBLE_COLUMN");

    /** A block a mover can never stand on, whatever its collision box (fences, walls, panes, iron bars/doors). */
    public static boolean isNeverFloor(String material) {
        Objects.requireNonNull(material, "material");
        if (NEVER_FLOOR_MATERIALS.contains(material)) {
            return true;
        }
        for (String suffix : NEVER_FLOOR_SUFFIXES) {
            if (material.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A door or fence gate a hand opens (every {@code *_DOOR} except the iron door, every
     * {@code *_FENCE_GATE}): walkable only where the mover may interact (§6). Trapdoors are not
     * entries in v1.
     */
    public static boolean isHandOpenableDoor(String material) {
        Objects.requireNonNull(material, "material");
        return (material.endsWith("_DOOR") && !material.equals("IRON_DOOR")) || material.endsWith("_FENCE_GATE");
    }

    /**
     * Whether a solid block can carry a walk cell: not a never-floor block and not a door (a door's
     * blocks are walked through, never stood on). The road builder does not use this — its floors are
     * road materials.
     */
    public static boolean isWalkFloor(String material) {
        return !isNeverFloor(material) && !isHandOpenableDoor(material);
    }

    /** Water (or a bubble column) by name. */
    public static boolean isWater(String material) {
        return WATER_MATERIALS.contains(Objects.requireNonNull(material, "material"));
    }
}
