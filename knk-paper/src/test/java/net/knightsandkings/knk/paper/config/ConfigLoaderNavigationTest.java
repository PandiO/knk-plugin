package net.knightsandkings.knk.paper.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.navigation.SessionParameters;
import net.knightsandkings.knk.core.roads.build.BuildParameters;
import net.knightsandkings.knk.core.roads.route.RouterParameters;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Road navigation (KNG-27): the navigation block of config.yml (DESIGN §4). */
class ConfigLoaderNavigationTest {

    private static YamlConfiguration bundledConfig() throws Exception {
        try (InputStream in = ConfigLoaderNavigationTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml on the classpath");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            config.set("api.auth.api-key", "test-key");
            return config;
        }
    }

    @Test
    void theBundledConfigLoadsWithTheDesignDefaults() throws Exception {
        NavigationConfig navigation = ConfigLoader.load(bundledConfig()).navigation();

        assertEquals(NavigationConfig.defaults(), navigation, "config.yml must spell out exactly the DESIGN §4 defaults");
        assertTrue(navigation.enabled());
        assertEquals(Map.of(RoadClass.MAIN, 0.9, RoadClass.ROAD, 1.0, RoadClass.PATH, 1.15), navigation.classCost());
        assertEquals(List.of("SNOW", "*_CARPET", "*_PRESSURE_PLATE", "RAIL", "POWERED_RAIL", "LEAF_LITTER", "PINK_PETALS"),
            navigation.overlayMaterials());
        assertTrue(navigation.seedFromDomains());
        assertEquals(48, navigation.maxSnapDistance(), 0.0001);
        assertEquals(4, navigation.snapVerticalWeight(), 0.0001);
        assertEquals(1, navigation.destinationSnapVerticalWeight(), 0.0001);
        assertEquals(96, navigation.maxStartDistance(), 0.0001);
        assertEquals(256, navigation.maxDestinationDistance(), 0.0001);
        assertEquals(96, navigation.destinationWalkRange(), 0.0001);
        assertEquals(new NavigationConfig.TrailConfig(30, 10, "DUST", "#E8C66A"), navigation.trail());
        assertEquals(8, navigation.rerouteDistance(), 0.0001);
        assertEquals(40, navigation.rerouteAfterTicks());
        assertEquals(4, navigation.arriveDistance(), 0.0001);
        assertEquals(30, navigation.maxSessionMinutes());
        assertEquals(5.6, navigation.sprintSpeed(), 0.0001);
        assertEquals(new NavigationConfig.SurveyConfig(4, 7, 32), navigation.survey());
        assertEquals(new NavigationConfig.BuilderConfig(512, 32, 250_000, 4, 3, 4, 3, 2, 8.0), navigation.builder());
    }

    @Test
    void theBundledConfigSpellsOutEveryWalkKey() throws Exception {
        YamlConfiguration yaml = bundledConfig();
        var walk = yaml.getConfigurationSection("navigation.walk");

        assertNotNull(walk, "config.yml has a navigation.walk block (KNG-51 §9)");
        assertEquals(java.util.Set.of("enabled", "max-expansions", "max-length-factor", "max-length", "detour-allowance",
            "climb-allowance", "max-drop", "drop-penalty", "capture-margin", "chunk-ttl-seconds", "recompute-distance",
            "max-concurrent-searches", "climbables", "wall-cost", "max-chunks-per-request", "clearance-cost", "wall-clearance",
            "curve"), walk.getKeys(false));
        assertEquals(new NavigationConfig.WalkConfig(true, 20_000, 1.75, 144, 48, 5, 3, 10, 16, 10, 6, 2, List.of("LADDER"), 1.0,
            64), ConfigLoader.load(yaml).navigation().walk());
    }

    @Test
    void theChunkLimitPerWalkRequestCanBeRaisedWithinBounds() {
        // off-road navigation phase 0 (#703): raised on a test server to measure long legs
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("navigation.walk.max-chunks-per-request", 400);
        NavigationConfig.WalkConfig walk = ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation")).walk();

        assertEquals(400, walk.maxChunksPerRequest());
        assertEquals(64, NavigationConfig.WalkConfig.defaults().maxChunksPerRequest());
        walk.validate();
        yaml.set("navigation.walk.max-chunks-per-request", 0);
        NavigationConfig.WalkConfig zero = ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation")).walk();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, zero::validate);
        assertTrue(e.getMessage().contains("max-chunks-per-request"), e.getMessage());
        yaml.set("navigation.walk.max-chunks-per-request", 1025);
        assertThrows(IllegalArgumentException.class,
            ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation")).walk()::validate);
    }

    @Test
    void guidanceV2IsOffInConfigYmlAndItsWalkKeysHaveTheDesignDefaults() throws Exception {
        // off-road navigation phase 6.1 (#710, DESIGN § 7.6): the flag, off; the walk keys it uses
        YamlConfiguration yaml = bundledConfig();
        assertTrue(yaml.contains("navigation.guidance-v2"), "config.yml names the flag");
        NavigationConfig navigation = ConfigLoader.load(yaml).navigation();
        assertFalse(navigation.guidanceV2());
        assertFalse(NavigationConfig.defaults().guidanceV2());
        assertEquals(5.0, navigation.walk().clearanceCost());
        assertEquals(0.8, navigation.walk().wallClearance());
        assertTrue(navigation.walk().curve());
    }

    @Test
    void withGuidanceV2OffTheWalkProfileIsTodaysExactly() {
        // A16: the flag off keeps wall-cost's meaning; on, the clearance cost replaces it (0 = the kill switch)
        NavigationConfig.WalkConfig walk = NavigationConfig.WalkConfig.defaults();
        assertEquals(walk.profile(), walk.profile(false));
        assertFalse(walk.profile(false).usesClearance());
        assertEquals(1.0, walk.profile(false).wallCost());
        assertTrue(walk.profile(true).usesClearance());
        assertEquals(5.0, walk.profile(true).clearanceCost());
        assertEquals(0.8, walk.profile(true).wallClearance());
        NavigationConfig.WalkConfig noCost = new NavigationConfig.WalkConfig(true, 20_000, 1.75, 144, 48, 5, 3, 10, 16, 10, 6,
            2, List.of("LADDER"), 1.0, 64, 0, 0.8, true);
        assertFalse(noCost.profile(true).usesClearance(), "clearance-cost 0: the wall cost, as before");
        assertEquals(new net.knightsandkings.knk.core.roads.walk.WalkPathShaper.Options(8, 0.8, true), walk.shaping());
    }

    @Test
    void theGuidanceKeysAreReadAndChecked() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("navigation.guidance-v2", true);
        yaml.set("navigation.walk.clearance-cost", 3.5);
        yaml.set("navigation.walk.wall-clearance", 1.2);
        yaml.set("navigation.walk.curve", false);
        NavigationConfig navigation = ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation"));
        assertTrue(navigation.guidanceV2());
        assertEquals(3.5, navigation.walk().clearanceCost());
        assertEquals(1.2, navigation.walk().wallClearance());
        assertFalse(navigation.walk().curve());
        navigation.walk().validate();
        assertTrue(navigation.withGuidanceV2(false).walk().equals(navigation.walk()));
        assertFalse(navigation.withGuidanceV2(false).guidanceV2());

        yaml.set("navigation.walk.wall-clearance", 0.2);
        IllegalArgumentException low = assertThrows(IllegalArgumentException.class,
            ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation")).walk()::validate);
        assertTrue(low.getMessage().contains("wall-clearance"), low.getMessage());
        yaml.set("navigation.walk.wall-clearance", 0.8);
        yaml.set("navigation.walk.clearance-cost", -1);
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
            ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation")).walk()::validate);
        assertTrue(negative.getMessage().contains("clearance-cost"), negative.getMessage());
    }

    @Test
    void thePlazaLineKeysAreInConfigYmlOffWithTheDesignDefaultsAndAreChecked() throws Exception {
        // off-road phase 7 (#712, DESIGN § 8.7, O30): plaza-lines off, plaza-pull 0.35, plaza-guide-cost 2
        YamlConfiguration bundled = bundledConfig();
        assertTrue(bundled.contains("navigation.plaza-lines"));
        assertTrue(bundled.contains("navigation.plaza-pull"));
        assertTrue(bundled.contains("navigation.plaza-guide-cost"));
        NavigationConfig loaded = ConfigLoader.load(bundled).navigation();
        assertEquals(new NavigationConfig.PlazaLinesConfig(false, 0.35, 2), loaded.plazaLines());
        assertEquals(loaded.plazaLines(), NavigationConfig.defaults().plazaLines());
        assertEquals(NavigationConfig.PlazaLinesConfig.defaults(), new YamlNavigation().missing().plazaLines(),
            "a config.yml without the keys: off, the defaults");

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("navigation.plaza-lines", true);
        yaml.set("navigation.plaza-pull", 0.5);
        yaml.set("navigation.plaza-guide-cost", 3);
        NavigationConfig navigation = ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation"));
        navigation.validate();
        assertEquals(new NavigationConfig.PlazaLinesConfig(true, 0.5, 3), navigation.plazaLines());
        assertEquals(navigation.plazaLines(), navigation.withGuidanceV2(true).withCursorJump(4).plazaLines());

        yaml.set("navigation.plaza-pull", 1.5);
        IllegalArgumentException pull = assertThrows(IllegalArgumentException.class,
            ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation"))::validate);
        assertTrue(pull.getMessage().contains("plaza-pull"), pull.getMessage());
        yaml.set("navigation.plaza-pull", 0.35);
        yaml.set("navigation.plaza-guide-cost", -1);
        IllegalArgumentException cost = assertThrows(IllegalArgumentException.class,
            ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation"))::validate);
        assertTrue(cost.getMessage().contains("plaza-guide-cost"), cost.getMessage());
    }

    /** A navigation section with none of the plaza-line keys. */
    private static final class YamlNavigation {
        NavigationConfig missing() {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("navigation.enabled", true);
            return ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation"));
        }
    }

    @Test
    void thePhase62KeysAreInConfigYmlWithTheDesignDefaultsAndAreChecked() throws Exception {
        // off-road phase 6.2 (#711, DESIGN § 7.6 corrected: trail-leg-spacing 3.0 per O20 and § 7.2.2)
        YamlConfiguration bundled = bundledConfig();
        assertTrue(bundled.contains("navigation.trail-leg-spacing"));
        assertTrue(bundled.contains("navigation.cursor-jump"));
        NavigationConfig loaded = ConfigLoader.load(bundled).navigation();
        assertEquals(3.0, loaded.trail().legSpacing());
        assertEquals(12.0, loaded.cursorJump());
        assertEquals(3.0, NavigationConfig.defaults().trail().legSpacing());
        assertEquals(12.0, NavigationConfig.defaults().cursorJump());
        assertEquals(null, loaded.cursorParameters(), "guidance-v2 off: the session keeps no cursor");

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("navigation.guidance-v2", true);
        yaml.set("navigation.trail-leg-spacing", 2.5);
        yaml.set("navigation.cursor-jump", 20);
        NavigationConfig navigation = ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation"));
        navigation.validate();
        assertEquals(2.5, navigation.trail().legSpacing());
        assertEquals(20.0, navigation.cursorParameters().cursorJump());

        yaml.set("navigation.cursor-jump", 0);
        IllegalArgumentException jump = assertThrows(IllegalArgumentException.class,
            ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation"))::validate);
        assertTrue(jump.getMessage().contains("cursor-jump"), jump.getMessage());
        yaml.set("navigation.cursor-jump", 12);
        yaml.set("navigation.trail-leg-spacing", 40);
        IllegalArgumentException spacing = assertThrows(IllegalArgumentException.class,
            ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation"))::validate);
        assertTrue(spacing.getMessage().contains("trail-leg-spacing"), spacing.getMessage());
    }

    @Test
    void aMissingSectionMeansOnWithDefaults() {
        assertEquals(NavigationConfig.defaults(), ConfigLoader.loadNavigation(null));
    }

    @Test
    void valuesCanBeOverridden() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("navigation.enabled", false);
        yaml.set("navigation.class-cost.path", 2.0);
        yaml.set("navigation.overlay-materials", List.of("SNOW"));
        yaml.set("navigation.trail-color", "#FF0000");
        yaml.set("navigation.survey.sample-period-ticks", 2);
        yaml.set("navigation.builder.snapshot-chunks-per-tick", 8);

        NavigationConfig navigation = ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation"));

        assertFalse(navigation.enabled());
        assertEquals(2.0, navigation.classCost().get(RoadClass.PATH), 0.0001);
        assertEquals(0.9, navigation.classCost().get(RoadClass.MAIN), 0.0001, "unset classes keep their defaults");
        assertEquals(List.of("SNOW"), navigation.overlayMaterials());
        assertEquals(0xFF0000, navigation.trail().rgb());
        assertEquals(30, navigation.trail().length(), "unset trail keys keep their defaults");
        assertEquals(2, navigation.survey().samplePeriodTicks());
        assertEquals(7, navigation.survey().crossSectionHalfWidth());
        assertEquals(8, navigation.builder().snapshotChunksPerTick());
        assertEquals(512, navigation.builder().tileSize());
        assertTrue(navigation.builder().curatedTiles(), "curated tiles are on unless switched off");
    }

    @Test
    void destinationsSnapWithTheirOwnHeightWeight() {
        // finding N15: the player's start keeps snap-vertical-weight, a destination has its own
        NavigationConfig defaults = ConfigLoader.loadNavigation(null);
        assertEquals(4, defaults.routerParameters().snapVerticalWeight(), 0.0001);
        assertEquals(1, defaults.destinationRouterParameters().snapVerticalWeight(), 0.0001);
        assertEquals(48, defaults.destinationRouterParameters().maxSnapDistance(), 0.0001);

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("navigation.destination-snap-vertical-weight", 2.5);
        NavigationConfig navigation = ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation"));

        assertEquals(2.5, navigation.destinationRouterParameters().snapVerticalWeight(), 0.0001);
        assertEquals(4, navigation.routerParameters().snapVerticalWeight(), 0.0001, "the start's weight is unchanged");

        yaml.set("navigation.destination-snap-vertical-weight", -1);
        assertThrows(IllegalArgumentException.class,
            () -> ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation")).validate());
    }

    @Test
    void thePlayersStartDistanceOverridesAndMustBePositive() {
        // KNG-75: with walk paths the player may start this far from a road
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("navigation.max-start-distance", 64);
        NavigationConfig navigation = ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation"));

        assertEquals(64, navigation.maxStartDistance(), 0.0001);
        assertEquals(48, navigation.maxSnapDistance(), 0.0001, "the destination limit is separate");

        yaml.set("navigation.max-start-distance", 0);
        assertThrows(IllegalArgumentException.class,
            () -> ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation")).validate());
    }

    @Test
    void theDestinationLimitsOverrideAndMustBePositive() {
        // KNG-75 step 2
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("navigation.max-destination-distance", 200);
        yaml.set("navigation.destination-walk-range", 80);
        NavigationConfig navigation = ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation"));

        assertEquals(200, navigation.maxDestinationDistance(), 0.0001);
        assertEquals(80, navigation.destinationWalkRange(), 0.0001);

        yaml.set("navigation.destination-walk-range", 0);
        assertThrows(IllegalArgumentException.class,
            () -> ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation")).validate());
        yaml.set("navigation.destination-walk-range", 80);
        yaml.set("navigation.max-destination-distance", -1);
        assertThrows(IllegalArgumentException.class,
            () -> ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation")).validate());
    }

    @Test
    void curatedTilesCanBeSwitchedOff() throws Exception {
        assertTrue(ConfigLoader.load(bundledConfig()).navigation().builder().curatedTiles());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("navigation.builder.curated-tiles", false);

        assertFalse(ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation")).builder().curatedTiles());
    }

    @Test
    void walkKeysDefaultToTheDesignAndOverride() {
        NavigationConfig.WalkConfig defaults = ConfigLoader.loadNavigation(null).walk();
        assertEquals(new NavigationConfig.WalkConfig(true, 20_000, 1.75, 144, 48, 5, 3, 10, 16, 10, 6, 2, List.of("LADDER"), 1.0),
            defaults, "KNG-51 §9 defaults");
        assertEquals(net.knightsandkings.knk.core.roads.walk.MovementProfile.PLAYER, defaults.profile());
        assertEquals(net.knightsandkings.knk.core.roads.walk.WalkBudget.DEFAULTS, defaults.budget());

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("navigation.walk.max-drop", 2);
        yaml.set("navigation.walk.drop-penalty", 4.5);
        yaml.set("navigation.walk.capture-margin", 8);
        yaml.set("navigation.walk.chunk-ttl-seconds", 30);
        yaml.set("navigation.walk.max-expansions", 5000);
        yaml.set("navigation.walk.climb-allowance", 3.5);
        yaml.set("navigation.walk.climbables", List.of("ladder", "VINE"));
        NavigationConfig.WalkConfig walk = ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation")).walk();
        walk.validate();

        assertEquals(2, walk.profile().maxDrop());
        assertEquals(4.5, walk.profile().dropPenalty(), 0.0001);
        assertEquals(java.util.Set.of("LADDER", "VINE"), walk.profile().climbables());
        assertEquals(5000, walk.budget().maxExpansions());
        assertEquals(144, walk.budget().maxLength(), 0.0001, "unset keys keep their defaults");
        assertEquals(3.5, walk.budget().climbAllowance(), 0.0001);
        assertEquals(8, walk.captureMargin());
        assertEquals(30, walk.chunkTtlSeconds());
        assertEquals(2, walk.maxConcurrentSearches());

        YamlConfiguration bad = new YamlConfiguration();
        bad.set("navigation.walk.max-length-factor", 0.5);
        assertThrows(IllegalArgumentException.class,
            () -> ConfigLoader.loadNavigation(bad.getConfigurationSection("navigation")).validate());
        YamlConfiguration negativeClimb = new YamlConfiguration();
        negativeClimb.set("navigation.walk.climb-allowance", -1);
        assertThrows(IllegalArgumentException.class,
            () -> ConfigLoader.loadNavigation(negativeClimb.getConfigurationSection("navigation")).validate());
        YamlConfiguration blank = new YamlConfiguration();
        blank.set("navigation.walk.climbables", List.of(" "));
        assertThrows(IllegalArgumentException.class,
            () -> ConfigLoader.loadNavigation(blank.getConfigurationSection("navigation")).validate());
    }

    @Test
    void badValuesAreConfigErrors() {
        YamlConfiguration unknownClass = new YamlConfiguration();
        unknownClass.set("navigation.class-cost.Highway", 1.0);
        assertThrows(IllegalArgumentException.class,
            () -> ConfigLoader.loadNavigation(unknownClass.getConfigurationSection("navigation")));

        YamlConfiguration badSnap = new YamlConfiguration();
        badSnap.set("navigation.max-snap-distance", 0);
        assertThrows(IllegalArgumentException.class,
            () -> ConfigLoader.loadNavigation(badSnap.getConfigurationSection("navigation")).validate());

        YamlConfiguration badColor = new YamlConfiguration();
        badColor.set("navigation.trail-color", "gold");
        assertThrows(IllegalArgumentException.class,
            () -> ConfigLoader.loadNavigation(badColor.getConfigurationSection("navigation")).validate());

        YamlConfiguration badTile = new YamlConfiguration();
        badTile.set("navigation.builder.tile-size", 500);
        assertThrows(IllegalArgumentException.class,
            () -> ConfigLoader.loadNavigation(badTile.getConfigurationSection("navigation")).validate());

        YamlConfiguration badHalfWidth = new YamlConfiguration();
        badHalfWidth.set("navigation.survey.cross-section-half-width", 9);
        assertThrows(IllegalArgumentException.class,
            () -> ConfigLoader.loadNavigation(badHalfWidth.getConfigurationSection("navigation")).validate());
    }

    @Test
    void theDefaultsMapOntoTheCoreParameterRecords() {
        NavigationConfig navigation = NavigationConfig.defaults();

        assertEquals(RouterParameters.defaults(), navigation.routerParameters());
        assertEquals(SessionParameters.defaults(), navigation.sessionParameters());
        assertEquals(BuildParameters.defaults(), navigation.builder().buildParameters());
        assertTrue(navigation.isOverlayMaterial("red_carpet"));
        assertTrue(navigation.isOverlayMaterial("SNOW"));
        assertFalse(navigation.isOverlayMaterial("SNOW_BLOCK"));
        assertTrue(navigation.passabilityRules(m -> true).isOverlay("RAIL"));
    }

    @Test
    void anOlderKnkConfigConstructorStillGivesTheNavigationDefaults() {
        KnkConfig older = new KnkConfig(null, null, null, null, KnkConfig.PrivateMessagesConfig.defaults(),
            KnkConfig.DiscoveryConfig.defaults());
        assertEquals(NavigationConfig.defaults(), older.navigation());
    }
}
