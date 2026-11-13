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

    @Test
    void routeFollowingKeysHaveDefaultsLayersAndRanges() {
        Scope scope = Scope.of("w");
        NavigationSettings defaults = NavigationSettings.defaults();
        assertEquals(16.0, defaults.value(NavigationSettings.HOP_BLOCKS, scope));
        assertEquals(3, defaults.value(NavigationSettings.MAX_RETRIES, scope));
        assertEquals(100, defaults.value(NavigationSettings.NO_ROUTE_WAIT_TICKS, scope));
        assertEquals(64.0, defaults.value(NavigationSettings.WALK_ONLY_BLOCKS, scope));

        NavigationSettings settings = NavigationSettings.builder()
            .global(parse(Map.of("hop-blocks", 12, "max-retries", 5)))
            .world("w", parse(Map.of("no-route-wait-ticks", 200, "walk-only-blocks", 0)))
            .build();
        assertEquals(new Resolved<>(12.0, Layer.GLOBAL, null), settings.resolve(NavigationSettings.HOP_BLOCKS, scope));
        assertEquals(5, settings.value(NavigationSettings.MAX_RETRIES, scope));
        assertEquals(new Resolved<>(200, Layer.WORLD, "w"), settings.resolve(NavigationSettings.NO_ROUTE_WAIT_TICKS, scope));
        assertEquals(0.0, settings.value(NavigationSettings.WALK_ONLY_BLOCKS, scope), "0 turns walk-only trips off");

        List<String> rejected = new ArrayList<>();
        Overrides.parse(Map.of("hop-blocks", 2, "max-retries", 11, "no-route-wait-ticks", 5, "walk-only-blocks", 500),
            "test", rejected);
        assertEquals(4, rejected.size(), rejected.toString());

        NpcNavigationService.Settings resolved = NpcNavigationService.Settings.of(settings, scope);
        assertEquals(12.0, resolved.hopBlocks());
        assertEquals(5, resolved.maxRetries());
        assertEquals(200, resolved.noRouteWaitTicks());
        assertEquals(32.0, resolved.stretchBlocks());
        assertEquals(60, resolved.noProgressTicks());
    }

    @Test
    void groupKeysHaveDefaultsLayersAndRanges() {
        Scope scope = Scope.of("w");
        NavigationSettings defaults = NavigationSettings.defaults();
        assertEquals("column", defaults.value(NavigationSettings.GROUP_FORMATION, scope));
        assertEquals(2.0, defaults.value(NavigationSettings.GROUP_SPACING, scope));
        assertEquals(2, defaults.value(NavigationSettings.GROUP_RANKS_WIDTH, scope));
        assertEquals(4.0, defaults.value(NavigationSettings.GROUP_REJOIN_BLOCKS, scope));
        assertEquals(24.0, defaults.value(NavigationSettings.GROUP_REGROUP_BLOCKS, scope));
        assertEquals(1.3, defaults.value(NavigationSettings.GROUP_CATCH_UP_SPEED, scope));

        NavigationSettings settings = NavigationSettings.builder()
            .global(parse(Map.of("group-formation", "RANKS", "group-spacing", 1.5, "group-catch-up-speed", 1)))
            .world("w", parse(Map.of("group-ranks-width", 4, "group-regroup-blocks", 0)))
            .region("keep", parse(Map.of("group-rejoin-blocks", 6)))
            .template("bodyguards", parse(Map.of("group-formation", "escort-ring")))
            .build();
        assertEquals(new Resolved<>("ranks", Layer.GLOBAL, null), settings.resolve(NavigationSettings.GROUP_FORMATION, scope));
        assertEquals(new Resolved<>(1.5, Layer.GLOBAL, null), settings.resolve(NavigationSettings.GROUP_SPACING, scope));
        assertEquals(1.0, settings.value(NavigationSettings.GROUP_CATCH_UP_SPEED, scope), "1 turns catching up off");
        assertEquals(new Resolved<>(4, Layer.WORLD, "w"), settings.resolve(NavigationSettings.GROUP_RANKS_WIDTH, scope));
        assertEquals(0.0, settings.value(NavigationSettings.GROUP_REGROUP_BLOCKS, scope), "0: the leader never waits");
        assertEquals(new Resolved<>(6.0, Layer.REGION, "keep"),
            settings.resolve(NavigationSettings.GROUP_REJOIN_BLOCKS, new Scope("w", List.of("keep"), null, null)));
        // a formation per template and per assignment (decision Q4)
        assertEquals(new Resolved<>("escort-ring", Layer.TEMPLATE, "bodyguards"),
            settings.resolve(NavigationSettings.GROUP_FORMATION, new Scope("w", List.of(), "bodyguards", null)));
        assertEquals(new Resolved<>("cluster", Layer.ASSIGNMENT, null), settings.resolve(NavigationSettings.GROUP_FORMATION,
            new Scope("w", List.of(), "bodyguards", Overrides.of(NavigationSettings.GROUP_FORMATION, "cluster"))));

        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("group-formation", "wedge");
        bad.put("group-spacing", 0.5);
        bad.put("group-ranks-width", 1);
        bad.put("group-rejoin-blocks", 1);
        bad.put("group-regroup-blocks", -1);
        bad.put("group-catch-up-speed", 3);
        List<String> rejected = new ArrayList<>();
        Overrides.parse(bad, "test", rejected);
        assertEquals(6, rejected.size(), rejected.toString());
    }
}
