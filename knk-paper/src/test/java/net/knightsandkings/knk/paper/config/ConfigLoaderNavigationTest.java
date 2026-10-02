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
    }

    @Test
    void walkKeysDefaultToTheDesignAndOverride() {
        NavigationConfig.WalkConfig defaults = ConfigLoader.loadNavigation(null).walk();
        assertEquals(new NavigationConfig.WalkConfig(true, 20_000, 1.75, 96, 3, 10, 16, 10, 6, 2, List.of("LADDER")),
            defaults, "KNG-51 §9 defaults");
        assertEquals(net.knightsandkings.knk.core.roads.walk.MovementProfile.PLAYER, defaults.profile());
        assertEquals(net.knightsandkings.knk.core.roads.walk.WalkBudget.DEFAULTS, defaults.budget());

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("navigation.walk.max-drop", 2);
        yaml.set("navigation.walk.drop-penalty", 4.5);
        yaml.set("navigation.walk.capture-margin", 8);
        yaml.set("navigation.walk.chunk-ttl-seconds", 30);
        yaml.set("navigation.walk.max-expansions", 5000);
        yaml.set("navigation.walk.climbables", List.of("ladder", "VINE"));
        NavigationConfig.WalkConfig walk = ConfigLoader.loadNavigation(yaml.getConfigurationSection("navigation")).walk();
        walk.validate();

        assertEquals(2, walk.profile().maxDrop());
        assertEquals(4.5, walk.profile().dropPenalty(), 0.0001);
        assertEquals(java.util.Set.of("LADDER", "VINE"), walk.profile().climbables());
        assertEquals(5000, walk.budget().maxExpansions());
        assertEquals(96, walk.budget().maxLength(), 0.0001, "unset keys keep their defaults");
        assertEquals(8, walk.captureMargin());
        assertEquals(30, walk.chunkTtlSeconds());
        assertEquals(2, walk.maxConcurrentSearches());

        YamlConfiguration bad = new YamlConfiguration();
        bad.set("navigation.walk.max-length-factor", 0.5);
        assertThrows(IllegalArgumentException.class,
            () -> ConfigLoader.loadNavigation(bad.getConfigurationSection("navigation")).validate());
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
