package net.knightsandkings.knk.paper.config;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.navigation.SessionParameters;
import net.knightsandkings.knk.core.roads.build.BuildParameters;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.route.RouterParameters;

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
 * @param snapVerticalWeight one block of height counts this many when snapping
 * @param trail              the guidance trail (Phase 4)
 * @param rerouteDistance    off-route distance that triggers a re-route
 * @param rerouteAfterTicks  how long the player must stay off-route first
 * @param arriveDistance     distance to the destination that ends a session
 * @param maxSessionMinutes  a session older than this ends by itself
 * @param sprintSpeed        blocks per second for the ETA
 * @param survey             survey-walk sampling
 * @param builder            tile builder parameters
 */
public record NavigationConfig(
    boolean enabled,
    Map<RoadClass, Double> classCost,
    List<String> overlayMaterials,
    boolean seedFromDomains,
    double maxSnapDistance,
    double snapVerticalWeight,
    TrailConfig trail,
    double rerouteDistance,
    int rerouteAfterTicks,
    double arriveDistance,
    int maxSessionMinutes,
    double sprintSpeed,
    SurveyConfig survey,
    BuilderConfig builder
) {
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
     */
    public record BuilderConfig(int tileSize, int tileMargin, int maxCellsPerTile, int snapshotChunksPerTick,
                                int junctionClusterRadius, int minSpurLength, int ambiguousReach, int plazaGrowth) {
        public static BuilderConfig defaults() {
            return new BuilderConfig(512, 32, 250_000, 4, 3, 4, 3, BuildParameters.DEFAULT_PLAZA_GROWTH);
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
        }

        /** The knk-core builder parameters (the ones not in config keep the builder's defaults). */
        public BuildParameters buildParameters() {
            return BuildParameters.defaults()
                .withTile(tileSize, tileMargin)
                .withMaxCells(maxCellsPerTile)
                .withAmbiguousReach(ambiguousReach)
                .withGraphRules(junctionClusterRadius, minSpurLength)
                .withPlazaGrowth(plazaGrowth);
        }
    }

    public NavigationConfig {
        classCost = classCost == null || classCost.isEmpty() ? RouterParameters.defaultClassCost() : Map.copyOf(classCost);
        overlayMaterials = overlayMaterials == null ? DEFAULT_OVERLAY_MATERIALS : List.copyOf(overlayMaterials);
        trail = trail == null ? TrailConfig.defaults() : trail;
        survey = survey == null ? SurveyConfig.defaults() : survey;
        builder = builder == null ? BuilderConfig.defaults() : builder;
    }

    public static NavigationConfig defaults() {
        return new NavigationConfig(true, RouterParameters.defaultClassCost(), DEFAULT_OVERLAY_MATERIALS, true,
            48, 4, TrailConfig.defaults(), 8, 40, 4, 30, 5.6, SurveyConfig.defaults(), BuilderConfig.defaults());
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
    }

    /** Snapping and class costs for the router (Phases 2d/4). */
    public RouterParameters routerParameters() {
        return new RouterParameters(maxSnapDistance, snapVerticalWeight, classCost);
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
