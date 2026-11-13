package net.knightsandkings.knk.core.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.NavigationSettings.Layer;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings.Overrides;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings.Resolved;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings.Scope;

class NavigationSettingsTest {

    private static Overrides parse(Map<String, ?> raw) {
        List<String> rejected = new ArrayList<>();
        Overrides overrides = Overrides.parse(raw, "test", rejected);
        assertTrue(rejected.isEmpty(), rejected.toString());
        return overrides;
    }

    @Test
    void codeDefaultsWithoutAnyLayer() {
        Resolved<Integer> recheck = NavigationSettings.defaults().resolve(NavigationSettings.RECHECK_TICKS, Scope.of("w"));
        assertEquals(40, recheck.value());
        assertEquals(Layer.DEFAULT, recheck.layer());
        assertEquals("load", NavigationSettings.defaults().value(NavigationSettings.CHUNK_LOADING, Scope.of("w")));
    }

    @Test
    void eachLayerOverridesTheOneBelowIt() {
        NavigationSettings settings = NavigationSettings.builder()
            .global(parse(Map.of("recheck-ticks", 30)))
            .world("World_KNK", parse(Map.of("recheck-ticks", 20)))
            .region("district_1", parse(Map.of("recheck-ticks", 10)))
            .template("guard", parse(Map.of("recheck-ticks", 5)))
            .build();
        Scope base = new Scope("world_knk", List.of("district_1"), "guard", null);

        assertEquals(new Resolved<>(5, Layer.TEMPLATE, "guard"), settings.resolve(NavigationSettings.RECHECK_TICKS, base));
        assertEquals(Layer.REGION, settings.resolve(NavigationSettings.RECHECK_TICKS,
            new Scope("world_knk", List.of("district_1"), null, null)).layer());
        assertEquals(Layer.WORLD, settings.resolve(NavigationSettings.RECHECK_TICKS, Scope.of("WORLD_KNK")).layer());
        assertEquals(Layer.GLOBAL, settings.resolve(NavigationSettings.RECHECK_TICKS, Scope.of("other")).layer());
        Scope assigned = new Scope("world_knk", List.of("district_1"), "guard",
            Overrides.of(NavigationSettings.RECHECK_TICKS, 1));
        assertEquals(new Resolved<>(1, Layer.ASSIGNMENT, null), settings.resolve(NavigationSettings.RECHECK_TICKS, assigned));
    }

    @Test
    void innermostRegionWinsAndUnsetKeysFallThrough() {
        NavigationSettings settings = NavigationSettings.builder()
            .region("district_1", parse(Map.of("chunk-loading", "simulate", "collidable", true)))
            .region("town_1", parse(Map.of("chunk-loading", "load")))
            .build();
        Scope inBoth = new Scope("w", List.of("town_1", "district_1"), null, null);
        assertEquals(new Resolved<>("simulate", Layer.REGION, "district_1"),
            settings.resolve(NavigationSettings.CHUNK_LOADING, inBoth));
        Scope townOnly = new Scope("w", List.of("town_1"), null, null);
        assertEquals("load", settings.value(NavigationSettings.CHUNK_LOADING, townOnly));
        assertEquals(Layer.DEFAULT, settings.resolve(NavigationSettings.COLLIDABLE, townOnly).layer());
    }

    @Test
    void badEntriesAreRejectedNotThrownAndTheRestIsKept() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("recheck-ticks", "soon");
        raw.put("stretch-blocks", 500);
        raw.put("pathfinder", "minecraft");
        raw.put("speling", 1);
        raw.put("collidable", "on");
        List<String> rejected = new ArrayList<>();
        Overrides overrides = Overrides.parse(raw, "npc.navigation", rejected);

        assertEquals(4, rejected.size(), rejected.toString());
        assertTrue(rejected.contains("npc.navigation.speling: unknown setting"));
        assertTrue(rejected.stream().anyMatch(r -> r.startsWith("npc.navigation.pathfinder: 'minecraft' is not one of")));
        assertEquals(true, overrides.get(NavigationSettings.COLLIDABLE));
        assertNull(overrides.get(NavigationSettings.RECHECK_TICKS));
    }

    @Test
    void describeListsEveryKeyWithItsOrigin() {
        NavigationSettings settings = NavigationSettings.builder()
            .world("w", parse(Map.of("stretch-blocks", 24))).build();
        Map<NavigationSettings.Key<?>, Resolved<?>> all = settings.describe(Scope.of("w"));
        assertEquals(NavigationSettings.KEYS.size(), all.size());
        assertEquals("24.0 (world w)", all.get(NavigationSettings.STRETCH_BLOCKS).toString());
        assertEquals("40 (default)", all.get(NavigationSettings.RECHECK_TICKS).toString());
    }
}
