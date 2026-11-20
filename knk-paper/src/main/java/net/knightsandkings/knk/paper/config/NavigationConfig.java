package net.knightsandkings.knk.paper.config;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.navigation.CursorParameters;
import net.knightsandkings.knk.core.navigation.SessionParameters;
import net.knightsandkings.knk.core.roads.build.BuildParameters;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.route.RouterParameters;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.roads.walk.WalkBudget;
import net.knightsandkings.knk.core.roads.walk.WalkPathShaper;

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
 * @param maxDestinationDistance with walk paths, a destination may be this far from a road (plain 3D; KNG-75 step 2):
 *                           beyond {@code destinationWalkRange} the last leg is the HUD arrow alone. Without walk paths
 *                           {@code max-snap-distance}, as before
 * @param destinationWalkRange with walk paths, a destination at most this far from the road's end gets a walk path for
 *                           the last leg (KNG-75 step 2); without them {@code max-snap-distance}
 * @param trail              the guidance trail (Phase 4)
 * @param rerouteDistance    off-route distance that triggers a re-route
 * @param rerouteAfterTicks  how long the player must stay off-route first
 * @param arriveDistance     distance to the destination that ends a session
 * @param maxSessionMinutes  a session older than this ends by itself
 * @param sprintSpeed        blocks per second for the ETA
 * @param survey             survey-walk sampling
 * @param builder            tile builder parameters
 * @param walk               last-mile walkable paths (KNG-51, {@code navigation.walk.*})
 * @param guidanceV2         {@code navigation.guidance-v2} (off-road navigation phase 6, PandiO/knk-workspace#710): the
 *                           trail-quality pass - walk paths with the clearance cost, shaped and drawn as checked curves.
 *                           Default false: walk paths, trails and the search exactly as before (DESIGN § 7.6, A16)
 * @param cursorJump         {@code navigation.cursor-jump} (phase 6.2, PandiO/knk-workspace#711; with {@code guidance-v2}
 *                           only): how far along the route the cursor may move in one tick before only a player who left
 *                           the previous stretch makes it jump (loops and switchbacks, DESIGN § 7.5.6). Default 12
 * @param plazaLines         {@code navigation.plaza-lines}, {@code plaza-pull} and {@code plaza-guide-cost} (off-road
 *                           navigation phase 7, PandiO/knk-workspace#712): plaza lines for the players' trail. Default
 *                           off: routes and trails exactly as before (A24)
 * @param walkLinks          {@code navigation.walk-links} and the {@code walk-link-*} and {@code road-first-*} keys
 *                           (off-road navigation phase 2, PandiO/knk-workspace#705): walk links in the middle of a
 *                           route and the road-first rule. Default off: routes, trails and sessions exactly as before
 * @param routeEnds          {@code navigation.road-goal-reachable}, {@code road-goal-candidates} and
 *                           {@code road-to-its-end} (off-road fix link F2, PandiO/knk-workspace#780, #771 items 4 and 5):
 *                           where a route's road part ends. Default off: routes and sessions exactly as before
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
    double maxDestinationDistance,
    double destinationWalkRange,
    TrailConfig trail,
    double rerouteDistance,
    int rerouteAfterTicks,
    double arriveDistance,
    int maxSessionMinutes,
    double sprintSpeed,
    SurveyConfig survey,
    BuilderConfig builder,
    WalkConfig walk,
    boolean guidanceV2,
    double cursorJump,
    PlazaLinesConfig plazaLines,
    WalkLinksConfig walkLinks,
    RouteEndsConfig routeEnds
) {
    /** Default for {@code destination-snap-vertical-weight}: plain 3D (finding N15). */
    public static final double DEFAULT_DESTINATION_SNAP_VERTICAL_WEIGHT = 1;

    /** Default for {@code max-start-distance}: the walk range (KNG-75; a 96-block leg fits the 64-chunk capture). */
    public static final double DEFAULT_MAX_START_DISTANCE = 96;

    /** Default for {@code max-destination-distance} (KNG-75 decision 2026-10-09). */
    public static final double DEFAULT_MAX_DESTINATION_DISTANCE = 256;

    /** Default for {@code destination-walk-range}: the walk range, as for the start (KNG-75 step 2a). */
    public static final double DEFAULT_DESTINATION_WALK_RANGE = 96;

    /** Design default for {@code overlay-materials} (DESIGN §4). */
    public static final List<String> DEFAULT_OVERLAY_MATERIALS = List.of(
        "SNOW", "*_CARPET", "*_PRESSURE_PLATE", "RAIL", "POWERED_RAIL", "LEAF_LITTER", "PINK_PETALS");

    /**
     * @param length      blocks of route drawn ahead of the player
     * @param periodTicks ticks between two trail redraws
     * @param particle    a Bukkit {@code Particle} name (DUST takes the colour)
     * @param color       {@code #RRGGBB}
     * @param legSpacing  {@code navigation.trail-leg-spacing} (phase 6.2, #711; since F2, #771 item 3, also with {@code guidance-v2} off): blocks
     *                    between the fixed anchors of a walk trail. Default 2.0 (the developer's choice in the live test of
     *                    2026-11-19, smoke § 13 step 5; 3.0 before F3, O20, DESIGN § 7.2.2)
     */
    public record TrailConfig(int length, int periodTicks, String particle, String color, double legSpacing) {
        /**
         * Default of {@code navigation.trail-leg-spacing}: 2.0, the developer's choice in the live test of 2026-11-19
         * (fix link F3; 3.0, {@link net.knightsandkings.knk.paper.navigation.TrailRenderer#LEG_SPACING}, before).
         */
        public static final double DEFAULT_LEG_SPACING = 2.0;

        /** Without {@code trail-leg-spacing} (callers from before #711): the default. */
        public TrailConfig(int length, int periodTicks, String particle, String color) {
            this(length, periodTicks, particle, color, DEFAULT_LEG_SPACING);
        }

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
            if (!(legSpacing >= 0.5 && legSpacing <= 16)) {
                throw new IllegalArgumentException("navigation.trail-leg-spacing must be between 0.5 and 16 (got: " + legSpacing + ")");
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
     * @param climbAllowance        and this many blocks more per block of height between start and target, also
     *                              above {@code maxLength} (KNG-108: stairs in tall buildings)
     * @param maxDrop               deepest drop taken (3 = no fall damage)
     * @param dropPenalty           extra cost per block dropped
     * @param captureMargin         blocks captured around the start→target box (also up and down)
     * @param chunkTtlSeconds       a captured chunk is reused this long
     * @param recomputeDistance     off-path distance that recomputes the path
     * @param maxConcurrentSearches walk searches running at once, server-wide
     * @param climbables            material names climbed like a ladder
     * @param wallCost              extra cost of a cell beside a wall: paths keep a block from walls (0 = hug them)
     * @param maxChunksPerRequest   a capture box larger than this many chunks is refused (64; raised on the dev server
     *                              only to measure long legs, off-road phase 0, PandiO/knk-workspace#703)
     * @param clearanceCost         with {@code guidance-v2}: extra cost per block of clearance a cell lacks to
     *                              {@code wallClearance}, in place of {@code wallCost}; 0 keeps {@code wallCost} (phase 6.1)
     * @param wallClearance         with {@code guidance-v2}: the clearance walk paths keep from walls where there is room
     *                              (0.35..2; the shaper's hard minimum is 0.35)
     * @param curve                 with {@code guidance-v2}: corners of a shaped walk trail drawn as checked curves
     */
    public record WalkConfig(boolean enabled, int maxExpansions, double maxLengthFactor, double maxLength,
                             double detourAllowance, double climbAllowance, int maxDrop, double dropPenalty,
                             int captureMargin, int chunkTtlSeconds, double recomputeDistance, int maxConcurrentSearches,
                             List<String> climbables, double wallCost, int maxChunksPerRequest, double clearanceCost,
                             double wallClearance, boolean curve) {

        /** Default of {@code clearance-cost} (DESIGN § 7.1.4: 5 per block, as the NPC local-leg search). */
        public static final double DEFAULT_CLEARANCE_COST = 5.0;
        /** Default of {@code wall-clearance} (DESIGN § 7.1.2). */
        public static final double DEFAULT_WALL_CLEARANCE = MovementProfile.DEFAULT_WALL_CLEARANCE;

        /** Default of {@code max-chunks-per-request}: the capture's limit before #703 (KNG-75 step 2a). */
        public static final int DEFAULT_MAX_CHUNKS_PER_REQUEST = 64;

        /** Upper bound of {@code max-chunks-per-request}: 1024 chunks, about 12 MB of captured terrain (DESIGN § 9.3). */
        public static final int MAX_CHUNKS_PER_REQUEST_LIMIT = 1024;

        public WalkConfig {
            climbables = climbables == null ? List.of("LADDER") : climbables.stream()
                .map(name -> name == null ? "" : name.trim().toUpperCase(Locale.ROOT))
                .toList();
        }

        /** Without the phase 6.1 keys (callers from before #710): their defaults. */
        public WalkConfig(boolean enabled, int maxExpansions, double maxLengthFactor, double maxLength,
                          double detourAllowance, double climbAllowance, int maxDrop, double dropPenalty,
                          int captureMargin, int chunkTtlSeconds, double recomputeDistance, int maxConcurrentSearches,
                          List<String> climbables, double wallCost, int maxChunksPerRequest) {
            this(enabled, maxExpansions, maxLengthFactor, maxLength, detourAllowance, climbAllowance, maxDrop, dropPenalty,
                captureMargin, chunkTtlSeconds, recomputeDistance, maxConcurrentSearches, climbables, wallCost,
                maxChunksPerRequest, DEFAULT_CLEARANCE_COST, DEFAULT_WALL_CLEARANCE, true);
        }

        /** Without {@code max-chunks-per-request} (callers from before #703): the default 64. */
        public WalkConfig(boolean enabled, int maxExpansions, double maxLengthFactor, double maxLength,
                          double detourAllowance, double climbAllowance, int maxDrop, double dropPenalty,
                          int captureMargin, int chunkTtlSeconds, double recomputeDistance, int maxConcurrentSearches,
                          List<String> climbables, double wallCost) {
            this(enabled, maxExpansions, maxLengthFactor, maxLength, detourAllowance, climbAllowance, maxDrop, dropPenalty,
                captureMargin, chunkTtlSeconds, recomputeDistance, maxConcurrentSearches, climbables, wallCost,
                DEFAULT_MAX_CHUNKS_PER_REQUEST);
        }

        public static WalkConfig defaults() {
            return new WalkConfig(true, WalkBudget.DEFAULTS.maxExpansions(), WalkBudget.DEFAULTS.maxLengthFactor(),
                WalkBudget.DEFAULTS.maxLength(), WalkBudget.DEFAULTS.detourAllowance(), WalkBudget.DEFAULTS.climbAllowance(),
                MovementProfile.PLAYER.maxDrop(), MovementProfile.PLAYER.dropPenalty(),
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
            if (!(climbAllowance >= 0) || Double.isInfinite(climbAllowance)) {
                throw new IllegalArgumentException("navigation.walk.climb-allowance must be a number >= 0 (got: " + climbAllowance + ")");
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
            if (maxChunksPerRequest < 1 || maxChunksPerRequest > MAX_CHUNKS_PER_REQUEST_LIMIT) {
                throw new IllegalArgumentException("navigation.walk.max-chunks-per-request must be 1.."
                    + MAX_CHUNKS_PER_REQUEST_LIMIT + " (got: " + maxChunksPerRequest + ")");
            }
            if (!(clearanceCost >= 0) || Double.isInfinite(clearanceCost)) {
                throw new IllegalArgumentException("navigation.walk.clearance-cost must be a number >= 0 (got: " + clearanceCost + ")");
            }
            if (!(wallClearance >= MovementProfile.MIN_WALL_CLEARANCE && wallClearance <= MovementProfile.MAX_WALL_CLEARANCE)) {
                throw new IllegalArgumentException("navigation.walk.wall-clearance must be " + MovementProfile.MIN_WALL_CLEARANCE
                    + ".." + MovementProfile.MAX_WALL_CLEARANCE + " (got: " + wallClearance + ")");
            }
            for (String name : climbables) {
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("navigation.walk.climbables must not contain blank entries");
                }
            }
        }

        /** The player's {@link MovementProfile} with this config's drops and climbables (today's wall cost). */
        public MovementProfile profile() {
            return MovementProfile.PLAYER.withDrops(maxDrop, dropPenalty).withClimbables(Set.copyOf(climbables))
                .withWallCost(wallCost);
        }

        /**
         * The profile for {@code navigation.guidance-v2}: on, with the clearance cost ({@code clearance-cost} 0 keeps the
         * wall cost); off, {@link #profile()} exactly (phase 6.1, A16).
         */
        public MovementProfile profile(boolean guidanceV2) {
            return guidanceV2 ? profile().withClearance(clearanceCost, wallClearance) : profile();
        }

        /** How walk paths are shaped with {@code navigation.guidance-v2} (phase 6.1). */
        public WalkPathShaper.Options shaping() {
            return WalkPathShaper.Options.of(wallClearance, curve);
        }

        /** The search budget (snap radii keep the core defaults: start 2, goal 3). */
        public WalkBudget budget() {
            return new WalkBudget(maxExpansions, maxLengthFactor, maxLength, detourAllowance, climbAllowance,
                WalkBudget.DEFAULTS.startSnap(), WalkBudget.DEFAULTS.goalSnap());
        }
    }

    public NavigationConfig {
        classCost = classCost == null || classCost.isEmpty() ? RouterParameters.defaultClassCost() : Map.copyOf(classCost);
        overlayMaterials = overlayMaterials == null ? DEFAULT_OVERLAY_MATERIALS : List.copyOf(overlayMaterials);
        trail = trail == null ? TrailConfig.defaults() : trail;
        survey = survey == null ? SurveyConfig.defaults() : survey;
        builder = builder == null ? BuilderConfig.defaults() : builder;
        walk = walk == null ? WalkConfig.defaults() : walk;
        plazaLines = plazaLines == null ? PlazaLinesConfig.defaults() : plazaLines;
        walkLinks = walkLinks == null ? WalkLinksConfig.defaults() : walkLinks;
        routeEnds = routeEnds == null ? RouteEndsConfig.defaults() : routeEnds;
    }

    /**
     * Where a player's route leaves the road (off-road fix link F2, PandiO/knk-workspace#780 option (b), #771 items 4 and
     * 5). Both switches are off by default: routes, trails and sessions exactly as before.
     *
     * @param reachableGoal  {@code navigation.road-goal-reachable}: a point destination's road goal is chosen among the
     *                       nearest road points by walk reachability (a walk search from each, within the shipped
     *                       {@code walk:} limits, off the main thread), the least road plus walk length winning; none
     *                       reachable: the straight-line nearest point, as today. The first route is today's; the chosen
     *                       goal replaces it when the searches answer. Default false
     * @param goalCandidates {@code navigation.road-goal-candidates}: the most road points (nearest first, one per road
     *                       edge, within {@code max-snap-distance} of the destination) searched (1..32, default 12)
     * @param roadToItsEnd   {@code navigation.road-to-its-end}: a running road route is never replaced by direct mode
     *                       when the road network is swapped while the player is within {@code max-snap-distance} of the
     *                       destination; the last walk leg starts at the road's end. Default false (today: direct mode)
     * @param goalWalkWeight {@code navigation.road-goal-walk-weight} (fix link F3, live smoke test of 2026-11-19): with
     *                       {@code road-goal-reachable}, a candidate's score is its road length plus this many times
     *                       its walk length, the least winning, so the road carries the route as far as it usefully goes
     *                       and the walk starts as late as it can (1..100, default {@link #DEFAULT_GOAL_WALK_WEIGHT};
     *                       1 is F2's road plus walk)
     */
    public record RouteEndsConfig(boolean reachableGoal, int goalCandidates, boolean roadToItsEnd, double goalWalkWeight) {
        public static final int DEFAULT_GOAL_CANDIDATES = 12;

        /** Default of {@code navigation.road-goal-walk-weight}: a block walked counts as four blocks of road. */
        public static final double DEFAULT_GOAL_WALK_WEIGHT = 4;

        /** Without {@code road-goal-walk-weight} (callers from before F3): the default weight. */
        public RouteEndsConfig(boolean reachableGoal, int goalCandidates, boolean roadToItsEnd) {
            this(reachableGoal, goalCandidates, roadToItsEnd, DEFAULT_GOAL_WALK_WEIGHT);
        }

        public static RouteEndsConfig defaults() {
            return new RouteEndsConfig(false, DEFAULT_GOAL_CANDIDATES, false, DEFAULT_GOAL_WALK_WEIGHT);
        }

        public void validate() {
            if (goalCandidates < 1 || goalCandidates > 32) {
                throw new IllegalArgumentException("navigation.road-goal-candidates must be 1..32 (got: " + goalCandidates + ")");
            }
            if (!(goalWalkWeight >= 1 && goalWalkWeight <= 100)) {
                throw new IllegalArgumentException("navigation.road-goal-walk-weight must be 1..100 (got: " + goalWalkWeight + ")");
            }
        }
    }

    /** Without the route-end keys (callers from before F2): the defaults, both off. */
    public NavigationConfig(boolean enabled, Map<RoadClass, Double> classCost, List<String> overlayMaterials,
                            boolean seedFromDomains, double maxSnapDistance, double snapVerticalWeight,
                            double destinationSnapVerticalWeight, double maxStartDistance, double maxDestinationDistance,
                            double destinationWalkRange, TrailConfig trail, double rerouteDistance, int rerouteAfterTicks,
                            double arriveDistance, int maxSessionMinutes, double sprintSpeed, SurveyConfig survey,
                            BuilderConfig builder, WalkConfig walk, boolean guidanceV2, double cursorJump,
                            PlazaLinesConfig plazaLines, WalkLinksConfig walkLinks) {
        this(enabled, classCost, overlayMaterials, seedFromDomains, maxSnapDistance, snapVerticalWeight,
            destinationSnapVerticalWeight, maxStartDistance, maxDestinationDistance, destinationWalkRange, trail,
            rerouteDistance, rerouteAfterTicks, arriveDistance, maxSessionMinutes, sprintSpeed, survey, builder, walk,
            guidanceV2, cursorJump, plazaLines, walkLinks, null);
    }

    /**
     * Walk links and mixed routes (off-road navigation phase 2, DESIGN § 5.1, § 5.2, decisions O12, O17, O18, O28,
     * PandiO/knk-workspace#705). Budgets from the phase 0 measurements report (2026-11-17, § 3.3): a link search is a
     * plain walk search within the {@code walk:} budget (budget 2: 150 ms p95 for 144 blocks; a link is at most
     * {@code walk-link-range} straight), shares the walk executor ({@code walk.max-concurrent-searches} 2, budget 5) and
     * is dropped first under load (at most that many link searches at once); its capture goes through the walk capture's
     * tick budget (budget 1).
     *
     * @param enabled          {@code navigation.walk-links}: the road A* may take walk links, and the road-first rule
     *                         applies in direct mode. Default false: today's routes exactly
     * @param range            {@code walk-link-range}: the longest straight distance of a link, blocks (default 40, O28)
     * @param ratio            {@code walk-link-ratio}: a pair of nodes is a candidate when the road between is more than
     *                         this times the straight distance (default 2.0, O28)
     * @param ttlSeconds       {@code walk-link-ttl-seconds}: a found link is kept this long without a block change in its
     *                         box (the backstop of the block-change hook, O18; default 600)
     * @param retrySeconds     {@code walk-link-retry-seconds}: "no link" is kept this long (default 600)
     * @param searchesPerRoute {@code walk-link-searches-per-route}: the most link searches one route asks for, the
     *                         cheapest first (default 4)
     * @param roadFirstShare   {@code road-first-share}: direct mode takes the road when the road carries at least this
     *                         part of the way (0..1, default 0.6; the developer confirms)
     * @param roadFirstMaxRatio {@code road-first-max-ratio}: ... and the whole way is at most this times the single walk
     *                         path (at least 1, default 1.5; the developer confirms)
     */
    public record WalkLinksConfig(boolean enabled, double range, double ratio, int ttlSeconds, int retrySeconds,
                                  int searchesPerRoute, double roadFirstShare, double roadFirstMaxRatio) {
        public static final double DEFAULT_RANGE = 40;
        public static final double DEFAULT_RATIO = 2.0;
        public static final int DEFAULT_TTL_SECONDS = 600;
        public static final int DEFAULT_RETRY_SECONDS = 600;
        public static final int DEFAULT_SEARCHES_PER_ROUTE = 4;

        public static WalkLinksConfig defaults() {
            return new WalkLinksConfig(false, DEFAULT_RANGE, DEFAULT_RATIO, DEFAULT_TTL_SECONDS, DEFAULT_RETRY_SECONDS,
                DEFAULT_SEARCHES_PER_ROUTE, net.knightsandkings.knk.core.roads.links.RoadFirstRule.DEFAULT_SHARE,
                net.knightsandkings.knk.core.roads.links.RoadFirstRule.DEFAULT_MAX_RATIO);
        }

        public WalkLinksConfig withEnabled(boolean on) {
            return new WalkLinksConfig(on, range, ratio, ttlSeconds, retrySeconds, searchesPerRoute, roadFirstShare,
                roadFirstMaxRatio);
        }

        public net.knightsandkings.knk.core.roads.links.RoadFirstRule roadFirst() {
            return new net.knightsandkings.knk.core.roads.links.RoadFirstRule(roadFirstShare, roadFirstMaxRatio);
        }

        public void validate() {
            if (!(range >= 2 && range <= 128)) {
                throw new IllegalArgumentException("navigation.walk-link-range must be 2..128 (got: " + range + ")");
            }
            if (!(ratio > 1)) {
                throw new IllegalArgumentException("navigation.walk-link-ratio must be above 1 (got: " + ratio + ")");
            }
            if (ttlSeconds < 1 || retrySeconds < 1) {
                throw new IllegalArgumentException("navigation.walk-link-ttl-seconds and walk-link-retry-seconds must be "
                    + "at least 1 (got: " + ttlSeconds + ", " + retrySeconds + ")");
            }
            if (searchesPerRoute < 0 || searchesPerRoute > 32) {
                throw new IllegalArgumentException("navigation.walk-link-searches-per-route must be 0..32 (got: "
                    + searchesPerRoute + ")");
            }
            roadFirst(); // validates road-first-share and road-first-max-ratio
        }
    }

    /** Without the walk-link keys (callers from before #705): the defaults, walk links off. */
    public NavigationConfig(boolean enabled, Map<RoadClass, Double> classCost, List<String> overlayMaterials,
                            boolean seedFromDomains, double maxSnapDistance, double snapVerticalWeight,
                            double destinationSnapVerticalWeight, double maxStartDistance, double maxDestinationDistance,
                            double destinationWalkRange, TrailConfig trail, double rerouteDistance, int rerouteAfterTicks,
                            double arriveDistance, int maxSessionMinutes, double sprintSpeed, SurveyConfig survey,
                            BuilderConfig builder, WalkConfig walk, boolean guidanceV2, double cursorJump,
                            PlazaLinesConfig plazaLines) {
        this(enabled, classCost, overlayMaterials, seedFromDomains, maxSnapDistance, snapVerticalWeight,
            destinationSnapVerticalWeight, maxStartDistance, maxDestinationDistance, destinationWalkRange, trail,
            rerouteDistance, rerouteAfterTicks, arriveDistance, maxSessionMinutes, sprintSpeed, survey, builder, walk,
            guidanceV2, cursorJump, plazaLines, null);
    }

    /**
     * Plaza lines for the players' trail (off-road navigation phase 7, DESIGN § 8.7, PandiO/knk-workspace#712). With
     * {@code plaza-lines} on, the road trail is drawn along the plaza line where a route crosses a plaza (step 2);
     * {@code /knk road plazaline} previews the lines with the flag on or off.
     *
     * @param enabled   {@code navigation.plaza-lines}: the trail is drawn along the plaza line where a route crosses a
     *                  plaza. Default false: today's trail (A24)
     * @param pull      {@code navigation.plaza-pull}: how far the curve's middle is pulled from the chord towards the
     *                  plaza node, 0 to 1. Default 0.35 (O30), tuned live on Cinix and Brink
     * @param guideCost {@code navigation.plaza-guide-cost}: the A* fallback's cost per block of distance from the
     *                  curve, at least 0. Default 2
     * @param curveClearance {@code navigation.plaza-curve-clearance}: the least clearance the curve must keep to be
     *                  taken; under it the A* line is used (it keeps {@code walk.wall-clearance} where it can, never
     *                  under 0.35). Default 0.65, the developer's decision on #712 (a player is 0.6 wide)
     */
    public record PlazaLinesConfig(boolean enabled, double pull, double guideCost, double curveClearance) {
        public static PlazaLinesConfig defaults() {
            return new PlazaLinesConfig(false, net.knightsandkings.knk.core.navigation.shape.PlazaCurve.DEFAULT_PULL,
                net.knightsandkings.knk.core.navigation.shape.PlazaCurve.DEFAULT_GUIDE_COST,
                net.knightsandkings.knk.core.navigation.shape.PlazaCurve.DEFAULT_CURVE_CLEARANCE);
        }

        /** Without {@code plaza-curve-clearance} (callers from step 1): the default. */
        public PlazaLinesConfig(boolean enabled, double pull, double guideCost) {
            this(enabled, pull, guideCost, net.knightsandkings.knk.core.navigation.shape.PlazaCurve.DEFAULT_CURVE_CLEARANCE);
        }

        public void validate() {
            if (!(pull >= 0 && pull <= 1)) {
                throw new IllegalArgumentException("navigation.plaza-pull must be 0..1 (got: " + pull + ")");
            }
            if (!(guideCost >= 0)) {
                throw new IllegalArgumentException("navigation.plaza-guide-cost must not be negative (got: " + guideCost + ")");
            }
            if (!(curveClearance >= 0.35 && curveClearance <= 2)) {
                throw new IllegalArgumentException("navigation.plaza-curve-clearance must be 0.35..2 (got: "
                    + curveClearance + ")");
            }
        }
    }

    /** Without the plaza-line keys (callers from before #712): the defaults, plaza lines off. */
    public NavigationConfig(boolean enabled, Map<RoadClass, Double> classCost, List<String> overlayMaterials,
                            boolean seedFromDomains, double maxSnapDistance, double snapVerticalWeight,
                            double destinationSnapVerticalWeight, double maxStartDistance, double maxDestinationDistance,
                            double destinationWalkRange, TrailConfig trail, double rerouteDistance, int rerouteAfterTicks,
                            double arriveDistance, int maxSessionMinutes, double sprintSpeed, SurveyConfig survey,
                            BuilderConfig builder, WalkConfig walk, boolean guidanceV2, double cursorJump) {
        this(enabled, classCost, overlayMaterials, seedFromDomains, maxSnapDistance, snapVerticalWeight,
            destinationSnapVerticalWeight, maxStartDistance, maxDestinationDistance, destinationWalkRange, trail,
            rerouteDistance, rerouteAfterTicks, arriveDistance, maxSessionMinutes, sprintSpeed, survey, builder, walk,
            guidanceV2, cursorJump, null);
    }

    /** Without {@code cursor-jump} (callers from before #711): the default. */
    public NavigationConfig(boolean enabled, Map<RoadClass, Double> classCost, List<String> overlayMaterials,
                            boolean seedFromDomains, double maxSnapDistance, double snapVerticalWeight,
                            double destinationSnapVerticalWeight, double maxStartDistance, double maxDestinationDistance,
                            double destinationWalkRange, TrailConfig trail, double rerouteDistance, int rerouteAfterTicks,
                            double arriveDistance, int maxSessionMinutes, double sprintSpeed, SurveyConfig survey,
                            BuilderConfig builder, WalkConfig walk, boolean guidanceV2) {
        this(enabled, classCost, overlayMaterials, seedFromDomains, maxSnapDistance, snapVerticalWeight,
            destinationSnapVerticalWeight, maxStartDistance, maxDestinationDistance, destinationWalkRange, trail,
            rerouteDistance, rerouteAfterTicks, arriveDistance, maxSessionMinutes, sprintSpeed, survey, builder, walk,
            guidanceV2, CursorParameters.DEFAULT_CURSOR_JUMP);
    }

    /** Without {@code guidance-v2} (callers from before #710): off. */
    public NavigationConfig(boolean enabled, Map<RoadClass, Double> classCost, List<String> overlayMaterials,
                            boolean seedFromDomains, double maxSnapDistance, double snapVerticalWeight,
                            double destinationSnapVerticalWeight, double maxStartDistance, double maxDestinationDistance,
                            double destinationWalkRange, TrailConfig trail, double rerouteDistance, int rerouteAfterTicks,
                            double arriveDistance, int maxSessionMinutes, double sprintSpeed, SurveyConfig survey,
                            BuilderConfig builder, WalkConfig walk) {
        this(enabled, classCost, overlayMaterials, seedFromDomains, maxSnapDistance, snapVerticalWeight,
            destinationSnapVerticalWeight, maxStartDistance, maxDestinationDistance, destinationWalkRange, trail,
            rerouteDistance, rerouteAfterTicks, arriveDistance, maxSessionMinutes, sprintSpeed, survey, builder, walk, false);
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
            DEFAULT_DESTINATION_SNAP_VERTICAL_WEIGHT, DEFAULT_MAX_START_DISTANCE, DEFAULT_MAX_DESTINATION_DISTANCE,
            DEFAULT_DESTINATION_WALK_RANGE, trail, rerouteDistance, rerouteAfterTicks, arriveDistance,
            maxSessionMinutes, sprintSpeed, survey, builder, null);
    }

    public static NavigationConfig defaults() {
        return new NavigationConfig(true, RouterParameters.defaultClassCost(), DEFAULT_OVERLAY_MATERIALS, true,
            48, 4, TrailConfig.defaults(), 8, 40, 4, 30, 5.6, SurveyConfig.defaults(), BuilderConfig.defaults());
    }

    /** This config with another {@code walk:} block. */
    public NavigationConfig withWalk(WalkConfig walk) {
        return new NavigationConfig(enabled, classCost, overlayMaterials, seedFromDomains, maxSnapDistance,
            snapVerticalWeight, destinationSnapVerticalWeight, maxStartDistance, maxDestinationDistance, destinationWalkRange,
            trail, rerouteDistance, rerouteAfterTicks, arriveDistance, maxSessionMinutes, sprintSpeed,
            survey, builder, walk, guidanceV2, cursorJump, plazaLines, walkLinks, routeEnds);
    }

    /** This config with {@code guidance-v2} on or off. */
    public NavigationConfig withGuidanceV2(boolean guidanceV2) {
        return new NavigationConfig(enabled, classCost, overlayMaterials, seedFromDomains, maxSnapDistance,
            snapVerticalWeight, destinationSnapVerticalWeight, maxStartDistance, maxDestinationDistance, destinationWalkRange,
            trail, rerouteDistance, rerouteAfterTicks, arriveDistance, maxSessionMinutes, sprintSpeed,
            survey, builder, walk, guidanceV2, cursorJump, plazaLines, walkLinks, routeEnds);
    }

    /** This config with another trail block ({@code trail-*} keys). */
    public NavigationConfig withTrail(TrailConfig trail) {
        return new NavigationConfig(enabled, classCost, overlayMaterials, seedFromDomains, maxSnapDistance,
            snapVerticalWeight, destinationSnapVerticalWeight, maxStartDistance, maxDestinationDistance, destinationWalkRange,
            trail, rerouteDistance, rerouteAfterTicks, arriveDistance, maxSessionMinutes, sprintSpeed,
            survey, builder, walk, guidanceV2, cursorJump, plazaLines, walkLinks, routeEnds);
    }

    /** This config with another {@code cursor-jump}. */
    public NavigationConfig withCursorJump(double cursorJump) {
        return new NavigationConfig(enabled, classCost, overlayMaterials, seedFromDomains, maxSnapDistance,
            snapVerticalWeight, destinationSnapVerticalWeight, maxStartDistance, maxDestinationDistance, destinationWalkRange,
            trail, rerouteDistance, rerouteAfterTicks, arriveDistance, maxSessionMinutes, sprintSpeed,
            survey, builder, walk, guidanceV2, cursorJump, plazaLines, walkLinks, routeEnds);
    }

    /** This config with other plaza-line keys. */
    public NavigationConfig withPlazaLines(PlazaLinesConfig plazaLines) {
        return new NavigationConfig(enabled, classCost, overlayMaterials, seedFromDomains, maxSnapDistance,
            snapVerticalWeight, destinationSnapVerticalWeight, maxStartDistance, maxDestinationDistance, destinationWalkRange,
            trail, rerouteDistance, rerouteAfterTicks, arriveDistance, maxSessionMinutes, sprintSpeed,
            survey, builder, walk, guidanceV2, cursorJump, plazaLines, walkLinks, routeEnds);
    }

    /** This config with other walk-link keys. */
    public NavigationConfig withWalkLinks(WalkLinksConfig walkLinks) {
        return new NavigationConfig(enabled, classCost, overlayMaterials, seedFromDomains, maxSnapDistance,
            snapVerticalWeight, destinationSnapVerticalWeight, maxStartDistance, maxDestinationDistance, destinationWalkRange,
            trail, rerouteDistance, rerouteAfterTicks, arriveDistance, maxSessionMinutes, sprintSpeed,
            survey, builder, walk, guidanceV2, cursorJump, plazaLines, walkLinks, routeEnds);
    }

    /** This config with other route-end keys (F2). */
    public NavigationConfig withRouteEnds(RouteEndsConfig routeEnds) {
        return new NavigationConfig(enabled, classCost, overlayMaterials, seedFromDomains, maxSnapDistance,
            snapVerticalWeight, destinationSnapVerticalWeight, maxStartDistance, maxDestinationDistance, destinationWalkRange,
            trail, rerouteDistance, rerouteAfterTicks, arriveDistance, maxSessionMinutes, sprintSpeed,
            survey, builder, walk, guidanceV2, cursorJump, plazaLines, walkLinks, routeEnds);
    }

    /** {@code guidance-v2}'s route cursor ({@code cursor-jump}); null with the flag off: the session as before. */
    public CursorParameters cursorParameters() {
        return guidanceV2 ? new CursorParameters(cursorJump) : null;
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
        if (!(maxDestinationDistance > 0)) {
            throw new IllegalArgumentException("navigation.max-destination-distance must be positive (got: " + maxDestinationDistance + ")");
        }
        if (!(destinationWalkRange > 0)) {
            throw new IllegalArgumentException("navigation.destination-walk-range must be positive (got: " + destinationWalkRange + ")");
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
        if (!(cursorJump > 0)) {
            throw new IllegalArgumentException("navigation.cursor-jump must be positive (got: " + cursorJump + ")");
        }
        trail.validate();
        survey.validate();
        builder.validate();
        walk.validate();
        plazaLines.validate();
        walkLinks.validate();
        routeEnds.validate();
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
