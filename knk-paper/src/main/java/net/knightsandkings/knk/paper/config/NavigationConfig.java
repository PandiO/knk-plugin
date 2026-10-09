package net.knightsandkings.knk.paper.config;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.navigation.SessionParameters;
import net.knightsandkings.knk.core.roads.build.BuildParameters;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.route.RouterParameters;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.roads.walk.WalkBudget;

/**
 * The {@code navigation:} block of config.yml (road navigation, docs/specs/navigation/DESIGN.md §4,
 * KNG-27). Every key has a default, so an existing config.yml without the section means "on, with
 * the design defaults". Materials live in the API's road profiles, not here; {@code overlay-materials}
 * only names the thin blocks that may lie <em>on</em> a road (snow layers, carpets, rails).
 *
 * <p>Kept as its own top-level record (rather than nested in {@link KnkConfig}) because knk-paper's
 * road classes and the Bukkit-free helper tests read it directly; {@link KnkConfig#navigation()}
 * carries it.
 *
 * @param enabled            master switch: when false nothing under {@code P/roads} is constructed
 * @param classCost          routing cost per road class ({@code Main}/{@code Road}/{@code Path})
 * @param overlayMaterials   material names or {@code *_SUFFIX} patterns lying on a road
 * @param seedFromDomains    every Domain Location within 8 blocks of a road cell seeds the build
 * @param maxSnapDistance    player and destination must be this close to a road (hard limit)
 * @param snapVerticalWeight one block of height counts this many when snapping the player's start
 * @param destinationSnapVerticalWeight the same for a destination (finding N15): its last leg is a walk
 *                           path, which climbs, so the default 1 measures plain 3D
 * @param maxStartDistance   with walk paths, the player may be this far from a road (plain 3D; KNG-75): the first
 *                           leg is a walk path to it; {@code snap-vertical-weight} only picks the road. Without walk
 *                           paths {@code max-snap-distance} (weighted) applies, as before
 * @param trail              the guidance trail (Phase 4)
 * @param rerouteDistance    off-route distance that triggers a re-route
 * @param rerouteAfterTicks  how long the player must stay off-route first
 * @param arriveDistance     distance to the destination that ends a session
 * @param maxSessionMinutes  a session older than this ends by itself
 * @param sprintSpeed        blocks per second for the ETA
 * @param survey             survey-walk sampling
 * @param builder            tile builder parameters
 * @param walk               last-mile walkable paths (KNG-51, {@code navigation.walk.*})
 */
public record NavigationConfig(
    boolean enabled,
    Map<RoadClass, Double> classCost,
    List<String> overlayMaterials,
    boolean seedFromDomains,
    double maxSnapDistance,
    double snapVerticalWeight,
    double destinationSnapVerticalWeight,
    double maxStartDistance,
    TrailConfig trail,
    double rerouteDistance,
    int rerouteAfterTicks,
    double arriveDistance,
    int maxSessionMinutes,
    double sprintSpeed,
    SurveyConfig survey,
    BuilderConfig builder,
    WalkConfig walk
) {
    /** Default for {@code destination-snap-vertical-weight}: plain 3D (finding N15). */
    public static final double DEFAULT_DESTINATION_SNAP_VERTICAL_WEIGHT = 1;

    /** Default for {@code max-start-distance}: the walk range (KNG-75; a 96-block leg fits the 64-chunk capture). */
    public static final double DEFAULT_MAX_START_DISTANCE = 96;

    /** Design default for {@code overlay-materials} (DESIGN §4). */
    public static final List<String> DEFAULT_OVERLAY_MATERIALS = List.of(
        "SNOW", "*_CARPET", "*_PRESSURE_PLATE", "RAIL", "POWERED_RAIL", "LEAF_LITTER", "PINK_PETALS");

    /**
     * @param length      blocks of route drawn ahead of the player
     * @param periodTicks ticks between two trail redraws
     * @param particle    a Bukkit {@code Particle} name (DUST takes the colour)
     * @param color       {@code #RRGGBB}
     */
    public record TrailConfig(int length, int periodTicks, String particle, String color) {
        public static TrailConfig defaults() {
            return new TrailConfig(30, 10, "DUST", "#E8C66A");
        }

        public void validate() {
            if (length < 1) {
                throw new IllegalArgumentException("navigation.trail-length must be at least 1 (got: " + length + ")");
            }
            if (periodTicks < 1) {
                throw new IllegalArgumentException("navigation.trail-period-ticks must be at least 1 (got: " + periodTicks + ")");
            }
            if (particle == null || particle.isBlank()) {
                throw new IllegalArgumentException("navigation.trail-particle is required");
            }
            if (color == null || !color.matches("#[0-9A-Fa-f]{6}")) {
                throw new IllegalArgumentException("navigation.trail-color must be #RRGGBB (got: " + color + ")");
            }
        }

        /** The colour as {@code 0xRRGGBB}. */
        public int rgb() {
            return Integer.parseInt(color.substring(1), 16);
        }
    }

    /**
     * @param samplePeriodTicks      ticks between two samples of a surveying admin
     * @param crossSectionHalfWidth  lateral offsets sampled each side of the admin (DESIGN §5.3: 7)
     * @param breadcrumbSeedSpacing  blocks between two Survey seeds taken from the breadcrumb
     */
    public record SurveyConfig(int samplePeriodTicks, int crossSectionHalfWidth, int breadcrumbSeedSpacing) {
        public static SurveyConfig defaults() {
            return new SurveyConfig(4, 7, 32);
        }

        public void validate() {
            if (samplePeriodTicks < 1) {
                throw new IllegalArgumentException("navigation.survey.sample-period-ticks must be at least 1 (got: " + samplePeriodTicks + ")");
            }
            if (crossSectionHalfWidth < 1 || crossSectionHalfWidth > 7) {
                throw new IllegalArgumentException("navigation.survey.cross-section-half-width must be 1..7 (got: " + crossSectionHalfWidth + ")");
            }
            if (breadcrumbSeedSpacing < 1) {
                throw new IllegalArgumentException("navigation.survey.breadcrumb-seed-spacing must be at least 1 (got: " + breadcrumbSeedSpacing + ")");
            }
        }
    }

    /**
     * @param tileSize              blocks per tile side (the API's tiles are 512; keep it)
     * @param tileMargin            blocks around a tile the BFS may enter
     * @param maxCellsPerTile       span cap per tile build
     * @param snapshotChunksPerTick chunks extracted per tick on the main thread (halved under lag)
     * @param junctionClusterRadius junction cells closer than this merge
     * @param minSpurLength         shorter dead-end spurs are pruned
     * @param ambiguousReach        an ambiguous material only counts within this many cells of a sure road cell
     * @param plazaGrowth           a plaza reaches this many cells beyond its wide core (fix plan 5.5 item 5)
     * @param lockedNodeReach       a locked node absorbs the builder's nodes this close (fix plan 5.5 item 6)
     * @param autoPlazas            wide road areas outside designed plazas still become plaza junctions on their
     *                              own (DESIGN §5.6 step 4, rev. 5)
     * @param curatedTiles          a build of a Curated tile makes a proposal the admin reviews (rev. 6 Part B,
     *                              plan §5.7); false uploads every build directly (the kill switch)
     */
    public record BuilderConfig(int tileSize, int tileMargin, int maxCellsPerTile, int snapshotChunksPerTick,
                                int junctionClusterRadius, int minSpurLength, int ambiguousReach, int plazaGrowth,
                                double lockedNodeReach, boolean autoPlazas, boolean curatedTiles) {
        public static BuilderConfig defaults() {
            return new BuilderConfig(512, 32, 250_000, 4, 3, 4, 3, BuildParameters.DEFAULT_PLAZA_GROWTH,
                BuildParameters.DEFAULT_LOCKED_NODE_REACH, true, true);
        }

        /** A builder config with curated tiles on. */
        public BuilderConfig(int tileSize, int tileMargin, int maxCellsPerTile, int snapshotChunksPerTick,
                             int junctionClusterRadius, int minSpurLength, int ambiguousReach, int plazaGrowth,
                             double lockedNodeReach, boolean autoPlazas) {
            this(tileSize, tileMargin, maxCellsPerTile, snapshotChunksPerTick, junctionClusterRadius, minSpurLength,
                ambiguousReach, plazaGrowth, lockedNodeReach, autoPlazas, true);
        }

        /** A builder config with automatic plazas on. */
        public BuilderConfig(int tileSize, int tileMargin, int maxCellsPerTile, int snapshotChunksPerTick,
                             int junctionClusterRadius, int minSpurLength, int ambiguousReach, int plazaGrowth,
                             double lockedNodeReach) {
            this(tileSize, tileMargin, maxCellsPerTile, snapshotChunksPerTick, junctionClusterRadius, minSpurLength,
                ambiguousReach, plazaGrowth, lockedNodeReach, true);
        }

        public void validate() {
            if (tileSize < 16 || Integer.bitCount(tileSize) != 1) {
                throw new IllegalArgumentException("navigation.builder.tile-size must be a power of two >= 16 (got: " + tileSize + ")");
            }
            if (tileMargin < 0) {
                throw new IllegalArgumentException("navigation.builder.tile-margin must not be negative (got: " + tileMargin + ")");
            }
            if (maxCellsPerTile < 1000) {
                throw new IllegalArgumentException("navigation.builder.max-cells-per-tile must be at least 1000 (got: " + maxCellsPerTile + ")");
            }
            if (snapshotChunksPerTick < 1) {
                throw new IllegalArgumentException("navigation.builder.snapshot-chunks-per-tick must be at least 1 (got: " + snapshotChunksPerTick + ")");
            }
            if (junctionClusterRadius < 0) {
                throw new IllegalArgumentException("navigation.builder.junction-cluster-radius must not be negative (got: " + junctionClusterRadius + ")");
            }
            if (minSpurLength < 0) {
                throw new IllegalArgumentException("navigation.builder.min-spur-length must not be negative (got: " + minSpurLength + ")");
            }
            if (ambiguousReach < 0) {
                throw new IllegalArgumentException("navigation.builder.ambiguous-reach must not be negative (got: " + ambiguousReach + ")");
            }
            if (plazaGrowth < 0) {
                throw new IllegalArgumentException("navigation.builder.plaza-growth must not be negative (got: " + plazaGrowth + ")");
            }
            if (!(lockedNodeReach >= 0)) {
                throw new IllegalArgumentException("navigation.builder.locked-node-reach must not be negative (got: " + lockedNodeReach + ")");
            }
        }

        /** The knk-core builder parameters (the ones not in config keep the builder's defaults). */
        public BuildParameters buildParameters() {
            return BuildParameters.defaults()
                .withTile(tileSize, tileMargin)
                .withMaxCells(maxCellsPerTile)
                .withAmbiguousReach(ambiguousReach)
                .withGraphRules(junctionClusterRadius, minSpurLength)
                .withPlazaGrowth(plazaGrowth)
                .withLockedNodeReach(lockedNodeReach)
                .withAutoPlazas(autoPlazas);
        }
    }

    /**
     * Last-mile walkable paths (KNG-51 {@code LAST_MILE_PATHFINDING.md} §9, {@code navigation.walk.*}).
     * {@code enabled: false} starts none of the walk services: direct mode draws straight lines exactly
     * as before KNG-51 (the kill switch).
     *
     * @param enabled               walkable trails on; false = today's straight lines (the kill switch)
     * @param maxExpansions         cells one search may expand
     * @param maxLengthFactor       a path may be at most this many times the straight distance
     * @param maxLength             and never longer than this many blocks
     * @param detourAllowance       but always at least this many blocks longer than the straight distance
     * @param maxDrop               deepest drop taken (3 = no fall damage)
     * @param dropPenalty           extra cost per block dropped
     * @param captureMargin         blocks captured around the start→target box (also up and down)
     * @param chunkTtlSeconds       a captured chunk is reused this long
     * @param recomputeDistance     off-path distance that recomputes the path
     * @param maxConcurrentSearches walk searches running at once, server-wide
     * @param climbables            material names climbed like a ladder
     * @param wallCost              extra cost of a cell beside a wall: paths keep a block from walls (0 = hug them)
     */
    public record WalkConfig(boolean enabled, int maxExpansions, double maxLengthFactor, double maxLength,
                             double detourAllowance, int maxDrop, double dropPenalty, int captureMargin, int chunkTtlSeconds,
                             double recomputeDistance, int maxConcurrentSearches, List<String> climbables,
                             double wallCost) {
        public WalkConfig {
            climbables = climbables == null ? List.of("LADDER") : climbables.stream()
                .map(name -> name == null ? "" : name.trim().toUpperCase(Locale.ROOT))
                .toList();
        }

        public static WalkConfig defaults() {
            return new WalkConfig(true, WalkBudget.DEFAULTS.maxExpansions(), WalkBudget.DEFAULTS.maxLengthFactor(),
                WalkBudget.DEFAULTS.maxLength(), WalkBudget.DEFAULTS.detourAllowance(), MovementProfile.PLAYER.maxDrop(), MovementProfile.PLAYER.dropPenalty(),
                16, 10, 6, 2, List.of("LADDER"), MovementProfile.PLAYER.wallCost());
        }

        public void validate() {
            if (maxExpansions < 1) {
                throw new IllegalArgumentException("navigation.walk.max-expansions must be at least 1 (got: " + maxExpansions + ")");
            }
            if (!(maxLengthFactor >= 1)) {
                throw new IllegalArgumentException("navigation.walk.max-length-factor must be at least 1 (got: " + maxLengthFactor + ")");
            }
            if (!(maxLength > 0)) {
                throw new IllegalArgumentException("navigation.walk.max-length must be positive (got: " + maxLength + ")");
            }
            if (!(detourAllowance >= 0) || Double.isInfinite(detourAllowance)) {
                throw new IllegalArgumentException("navigation.walk.detour-allowance must be a number >= 0 (got: " + detourAllowance + ")");
            }
            if (!(wallCost >= 0) || Double.isInfinite(wallCost)) {
                throw new IllegalArgumentException("navigation.walk.wall-cost must be a number >= 0 (got: " + wallCost + ")");
            }
            if (maxDrop < 0) {
                throw new IllegalArgumentException("navigation.walk.max-drop must not be negative (got: " + maxDrop + ")");
            }
            if (!(dropPenalty >= 0) || Double.isInfinite(dropPenalty)) {
                throw new IllegalArgumentException("navigation.walk.drop-penalty must be a number >= 0 (got: " + dropPenalty + ")");
            }
            if (captureMargin < 0 || captureMargin > 64) {
                throw new IllegalArgumentException("navigation.walk.capture-margin must be 0..64 (got: " + captureMargin + ")");
            }
            if (chunkTtlSeconds < 0) {
                throw new IllegalArgumentException("navigation.walk.chunk-ttl-seconds must not be negative (got: " + chunkTtlSeconds + ")");
            }
            if (!(recomputeDistance > 0)) {
                throw new IllegalArgumentException("navigation.walk.recompute-distance must be positive (got: " + recomputeDistance + ")");
            }
            if (maxConcurrentSearches < 1) {
                throw new IllegalArgumentException("navigation.walk.max-concurrent-searches must be at least 1 (got: " + maxConcurrentSearches + ")");
            }
            for (String name : climbables) {
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("navigation.walk.climbables must not contain blank entries");
                }
            }
        }

        /** The player's {@link MovementProfile} with this config's drops and climbables. */
        public MovementProfile profile() {
            return MovementProfile.PLAYER.withDrops(maxDrop, dropPenalty).withClimbables(Set.copyOf(climbables))
                .withWallCost(wallCost);
        }

        /** The search budget (snap radii keep the core defaults: start 2, goal 3). */
        public WalkBudget budget() {
            return new WalkBudget(maxExpansions, maxLengthFactor, maxLength, detourAllowance, WalkBudget.DEFAULTS.startSnap(),
                WalkBudget.DEFAULTS.goalSnap());
        }
    }

    public NavigationConfig {
        classCost = classCost == null || classCost.isEmpty() ? RouterParameters.defaultClassCost() : Map.copyOf(classCost);
        overlayMaterials = overlayMaterials == null ? DEFAULT_OVERLAY_MATERIALS : List.copyOf(overlayMaterials);
        trail = trail == null ? TrailConfig.defaults() : trail;
        survey = survey == null ? SurveyConfig.defaults() : survey;
        builder = builder == null ? BuilderConfig.defaults() : builder;
        walk = walk == null ? WalkConfig.defaults() : walk;
    }

    /**
     * Without a {@code walk:} block (callers from before KNG-51): the walk defaults; destinations snap with
     * the default weight; the default start distance.
     */
    public NavigationConfig(boolean enabled, Map<RoadClass, Double> classCost, List<String> overlayMaterials,
                            boolean seedFromDomains, double maxSnapDistance, double snapVerticalWeight, TrailConfig trail,
                            double rerouteDistance, int rerouteAfterTicks, double arriveDistance, int maxSessionMinutes,
                            double sprintSpeed, SurveyConfig survey, BuilderConfig builder) {
        this(enabled, classCost, overlayMaterials, seedFromDomains, maxSnapDistance, snapVerticalWeight,
            DEFAULT_DESTINATION_SNAP_VERTICAL_WEIGHT, DEFAULT_MAX_START_DISTANCE, trail, rerouteDistance, rerouteAfterTicks, arriveDistance,
            maxSessionMinutes, sprintSpeed, survey, builder, null);
    }

    public static NavigationConfig defaults() {
        return new NavigationConfig(true, RouterParameters.defaultClassCost(), DEFAULT_OVERLAY_MATERIALS, true,
            48, 4, TrailConfig.defaults(), 8, 40, 4, 30, 5.6, SurveyConfig.defaults(), BuilderConfig.defaults());
    }

    /** This config with another {@code walk:} block. */
    public NavigationConfig withWalk(WalkConfig walk) {
        return new NavigationConfig(enabled, classCost, overlayMaterials, seedFromDomains, maxSnapDistance,
            snapVerticalWeight, destinationSnapVerticalWeight, maxStartDistance, trail, rerouteDistance, rerouteAfterTicks, arriveDistance, maxSessionMinutes, sprintSpeed,
            survey, builder, walk);
    }

    /** {@code class-cost} keys as the API spells them ({@code Main}, {@code Road}, {@code Path}), any case. */
    public static Map<RoadClass, Double> parseClassCost(Map<String, Double> byName) {
        Map<RoadClass, Double> cost = new EnumMap<>(RouterParameters.defaultClassCost());
        if (byName == null) {
            return cost;
        }
        byName.forEach((name, value) -> {
            RoadClass roadClass;
            try {
                roadClass = RoadClass.fromApiName(name);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("navigation.class-cost: unknown road class '" + name + "' (Main, Road, Path)");
            }
            if (value == null || !(value > 0)) {
                throw new IllegalArgumentException("navigation.class-cost." + name + " must be positive (got: " + value + ")");
            }
            cost.put(roadClass, value);
        });
        return cost;
    }

    public void validate() {
        for (Map.Entry<RoadClass, Double> entry : classCost.entrySet()) {
            if (entry.getValue() == null || !(entry.getValue() > 0)) {
                throw new IllegalArgumentException("navigation.class-cost." + entry.getKey().apiName() + " must be positive");
            }
        }
        for (String pattern : overlayMaterials) {
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalArgumentException("navigation.overlay-materials must not contain blank entries");
            }
        }
        if (!(maxSnapDistance > 0)) {
            throw new IllegalArgumentException("navigation.max-snap-distance must be positive (got: " + maxSnapDistance + ")");
        }
        if (snapVerticalWeight < 0) {
            throw new IllegalArgumentException("navigation.snap-vertical-weight must not be negative (got: " + snapVerticalWeight + ")");
        }
        if (!(maxStartDistance > 0)) {
            throw new IllegalArgumentException("navigation.max-start-distance must be positive (got: " + maxStartDistance + ")");
        }
        if (destinationSnapVerticalWeight < 0) {
            throw new IllegalArgumentException("navigation.destination-snap-vertical-weight must not be negative (got: "
                + destinationSnapVerticalWeight + ")");
        }
        if (!(rerouteDistance > 0)) {
            throw new IllegalArgumentException("navigation.reroute-distance must be positive (got: " + rerouteDistance + ")");
        }
        if (rerouteAfterTicks < 0) {
            throw new IllegalArgumentException("navigation.reroute-after-ticks must not be negative (got: " + rerouteAfterTicks + ")");
        }
        if (!(arriveDistance > 0)) {
            throw new IllegalArgumentException("navigation.arrive-distance must be positive (got: " + arriveDistance + ")");
        }
        if (maxSessionMinutes < 1) {
            throw new IllegalArgumentException("navigation.max-session-minutes must be at least 1 (got: " + maxSessionMinutes + ")");
        }
        if (!(sprintSpeed > 0)) {
            throw new IllegalArgumentException("navigation.sprint-speed must be positive (got: " + sprintSpeed + ")");
        }
        trail.validate();
        survey.validate();
        builder.validate();
        walk.validate();
    }

    /** Snapping and class costs for the router (Phases 2d/4); the snap weight is the player's start's. */
    public RouterParameters routerParameters() {
        return new RouterParameters(maxSnapDistance, snapVerticalWeight, classCost);
    }

    /** {@link #routerParameters()} with the destination's snap weight (finding N15). */
    public RouterParameters destinationRouterParameters() {
        return routerParameters().withSnap(maxSnapDistance, destinationSnapVerticalWeight);
    }

    /** Session timings for {@code NavigationSession} (Phase 4); keys not in config keep the core defaults. */
    public SessionParameters sessionParameters() {
        return new SessionParameters(rerouteDistance, rerouteAfterTicks,
            SessionParameters.DEFAULT_REROUTE_MIN_INTERVAL_TICKS, SessionParameters.DEFAULT_IMPROVEMENT_INTERVAL_TICKS,
            SessionParameters.DEFAULT_IMPROVEMENT_THRESHOLD, arriveDistance, maxSessionMinutes, sprintSpeed,
            SessionParameters.DEFAULT_OFF_ROUTE_LOOK_BACK);
    }

    /** Passability rules for the builder's surface grid: {@code collidable} decides on material names. */
    public PassabilityRules passabilityRules(java.util.function.Predicate<String> collidable) {
        return PassabilityRules.of(collidable, overlayMaterials);
    }

    /** Whether a material name (upper case) is one of the configured overlays. */
    public boolean isOverlayMaterial(String materialName) {
        String name = materialName.toUpperCase(Locale.ROOT);
        for (String pattern : overlayMaterials) {
            if (PassabilityRules.matchesPattern(name, pattern.trim().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
