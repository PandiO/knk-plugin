package net.knightsandkings.knk.paper.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.navigation.SessionParameters;
import net.knightsandkings.knk.core.roads.build.BuildParameters;
import net.knightsandkings.knk.core.roads.route.RouterParameters;
import org.junit.jupiter.api.Test;

/** The Bukkit-free half of the navigation config: defaults, validation, the core parameter mappings. */
class NavigationConfigTest {

    @Test
    void defaultsMatchTheCoreDefaults() {
        NavigationConfig navigation = NavigationConfig.defaults();
        navigation.validate();
        assertEquals(RouterParameters.defaults(), navigation.routerParameters());
        assertEquals(SessionParameters.defaults(), navigation.sessionParameters());
        assertEquals(BuildParameters.defaults(), navigation.builder().buildParameters());
        assertEquals(0xE8C66A, navigation.trail().rgb());
    }

    @Test
    void classCostParsesApiNamesAndKeepsUnsetDefaults() {
        Map<RoadClass, Double> cost = NavigationConfig.parseClassCost(Map.of("path", 2.0, "Main", 0.5));
        assertEquals(2.0, cost.get(RoadClass.PATH));
        assertEquals(0.5, cost.get(RoadClass.MAIN));
        assertEquals(1.0, cost.get(RoadClass.ROAD));
        assertThrows(IllegalArgumentException.class, () -> NavigationConfig.parseClassCost(Map.of("Highway", 1.0)));
        assertThrows(IllegalArgumentException.class, () -> NavigationConfig.parseClassCost(Map.of("Road", 0.0)));
    }

    @Test
    void validationRejectsBadValues() {
        NavigationConfig d = NavigationConfig.defaults();
        assertThrows(IllegalArgumentException.class, () -> new NavigationConfig(true, d.classCost(), d.overlayMaterials(), true, 0, 4,
            d.trail(), 8, 40, 4, 30, 5.6, d.survey(), d.builder()).validate());
        assertThrows(IllegalArgumentException.class, () -> new NavigationConfig.TrailConfig(30, 10, "DUST", "gold").validate());
        assertThrows(IllegalArgumentException.class, () -> new NavigationConfig.BuilderConfig(500, 32, 250_000, 4, 3, 4, 3, 2).validate());
        assertThrows(IllegalArgumentException.class, () -> new NavigationConfig.BuilderConfig(512, 32, 250_000, 4, 3, 4, 3, -1).validate());
        assertThrows(IllegalArgumentException.class, () -> new NavigationConfig.SurveyConfig(4, 9, 32).validate());
        assertThrows(IllegalArgumentException.class, () -> new NavigationConfig.SurveyConfig(0, 7, 32).validate());
        new NavigationConfig.BuilderConfig(256, 0, 1000, 1, 0, 0, 0, 0).validate();
    }

    @Test
    void overlayMaterialsMatchNamesAndSuffixPatterns() {
        NavigationConfig navigation = NavigationConfig.defaults();
        assertTrue(navigation.isOverlayMaterial("red_carpet"));
        assertTrue(navigation.isOverlayMaterial("SNOW"));
        assertTrue(navigation.isOverlayMaterial("STONE_PRESSURE_PLATE"));
        assertFalse(navigation.isOverlayMaterial("SNOW_BLOCK"));
        assertTrue(navigation.passabilityRules(m -> true).isPassable("RAIL"), "an overlay is passable even if collidable");
        NavigationConfig custom = new NavigationConfig(true, null, List.of("SNOW"), true, 48, 4, null, 8, 40, 4, 30, 5.6, null, null);
        assertFalse(custom.isOverlayMaterial("RED_CARPET"));
        assertEquals(RouterParameters.defaultClassCost(), custom.classCost(), "null map → defaults");
    }
}
