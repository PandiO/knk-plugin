package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings.Layer;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings.Scope;

class NpcNavigationConfigTest {

    private static NpcNavigationConfig parse(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return NpcNavigationConfig.from(config.getConfigurationSection(NpcNavigationConfig.PATH));
    }

    @Test
    void noSectionMeansDisabledWithCodeDefaults() {
        NpcNavigationConfig config = NpcNavigationConfig.from(null);
        assertFalse(config.enabled());
        assertEquals(Layer.DEFAULT, config.settings().resolve(NavigationSettings.RECHECK_TICKS, Scope.of("w")).layer());
    }

    @Test
    void readsGlobalWorldAndRegionLayers() throws Exception {
        NpcNavigationConfig config = parse("""
            npc:
              navigation:
                enabled: true
                max-plans-in-flight: 8
                recheck-ticks: 30
                worlds:
                  world_KNK-DEV:
                    chunk-loading: simulate
                regions:
                  district_1000006:
                    recheck-ticks: 10
            """);
        assertTrue(config.enabled());
        assertEquals(8, config.maxPlansInFlight());
        assertTrue(config.rejected().isEmpty(), config.rejected().toString());
        NavigationSettings settings = config.settings();
        assertEquals(Layer.GLOBAL, settings.resolve(NavigationSettings.RECHECK_TICKS, Scope.of("other")).layer());
        assertEquals("simulate", settings.value(NavigationSettings.CHUNK_LOADING, Scope.of("world_knk-dev")));
        Scope inDistrict = new Scope("world_KNK-DEV", List.of("town_1", "district_1000006"), null, null);
        assertEquals(10, settings.value(NavigationSettings.RECHECK_TICKS, inDistrict));
    }

    @Test
    void badEntriesAreReportedAndDoNotStopTheRest() throws Exception {
        NpcNavigationConfig config = parse("""
            npc:
              navigation:
                enabled: true
                max-plans-in-flight: 0
                recheck-ticks: often
                stretch-blocks: 24
                regions:
                  r1:
                    colidable: true
            """);
        assertEquals(3, config.rejected().size(), config.rejected().toString());
        assertEquals(4, config.maxPlansInFlight());
        assertEquals(24.0, config.settings().value(NavigationSettings.STRETCH_BLOCKS, Scope.of("w")));
    }

    @Test
    void theShippedConfigHasNoRejectedEntriesAndIsOffByDefault() throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            shipped.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        NpcNavigationConfig config = NpcNavigationConfig.from(shipped.getConfigurationSection(NpcNavigationConfig.PATH));
        assertTrue(config.rejected().isEmpty(), config.rejected().toString());
        assertFalse(config.enabled());
        assertEquals(Layer.GLOBAL, config.settings().resolve(NavigationSettings.CHUNK_LOADING, Scope.of("w")).layer());
    }

    @Test
    void readsSpawnsPerTickAndAccessProfiles() throws Exception {
        NpcNavigationConfig config = parse("""
            npc:
              navigation:
                enabled: true
                spawns-per-tick: 5
                hop-blocks: 12
                max-retries: 2
                no-route-wait-ticks: 200
                walk-only-blocks: 32
                access-profiles:
                  Guard:
                    bypass-domain-rules: true
                    open-doors: false
                    allow-regions: [Castle_Yard]
                    deny-regions: [throne_room]
            """);
        assertTrue(config.rejected().isEmpty(), config.rejected().toString());
        assertEquals(5, config.spawnsPerTick());
        Scope scope = Scope.of("w");
        assertEquals(12.0, config.settings().value(NavigationSettings.HOP_BLOCKS, scope));
        assertEquals(2, config.settings().value(NavigationSettings.MAX_RETRIES, scope));
        assertEquals(200, config.settings().value(NavigationSettings.NO_ROUTE_WAIT_TICKS, scope));
        assertEquals(32.0, config.settings().value(NavigationSettings.WALK_ONLY_BLOCKS, scope));

        NpcAccessProfile guard = config.accessProfile("GUARD");
        assertEquals("guard", guard.id());
        assertTrue(guard.bypassDomainRules());
        assertFalse(guard.openDoors());
        assertEquals(AccessSubject.RegionOverride.ALLOW, guard.override("castle_yard"));
        assertEquals(AccessSubject.RegionOverride.DENY, guard.override("Throne_Room"));
        assertEquals(NpcAccessProfile.DEFAULT, config.accessProfile("default"), "always there");
        assertEquals(null, config.accessProfile("nobody"));
    }

    @Test
    void badGlobalSwitchesAndProfileKeysAreReported() throws Exception {
        NpcNavigationConfig config = parse("""
            npc:
              navigation:
                spawns-per-tick: 0
                access-profiles:
                  guard:
                    open-door: false
                  broken: 3
            """);
        assertEquals(3, config.rejected().size(), config.rejected().toString());
        assertEquals(2, config.spawnsPerTick());
        assertTrue(config.accessProfile("guard").openDoors(), "the misspelt key changed nothing");
        assertEquals(2, NpcNavigationConfig.disabled().spawnsPerTick());
        assertEquals(java.util.Set.of("default"), NpcNavigationConfig.disabled().accessProfiles().keySet());
    }

    @Test
    void theShippedConfigSetsEveryPerNpcKeyAndTheDefaultProfile() throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            shipped.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        NpcNavigationConfig config = NpcNavigationConfig.from(shipped.getConfigurationSection(NpcNavigationConfig.PATH));
        for (NavigationSettings.Key<?> key : NavigationSettings.KEYS) {
            NavigationSettings.Resolved<?> resolved = config.settings().resolve(key, Scope.of("w"));
            assertEquals(Layer.GLOBAL, resolved.layer(), key.name() + " is in the shipped config.yml");
            assertEquals(key.defaultValue(), resolved.value(), key.name() + " ships with its code default");
        }
        assertEquals(2, config.spawnsPerTick());
        assertEquals(4, config.maxPlansInFlight());
        assertEquals(10, config.ruleChecksPerTick());
        assertEquals(NpcAccessProfile.DEFAULT, config.accessProfile("default"));
    }

    @Test
    void readsTheRuleCheckKeys() throws Exception {
        NpcNavigationConfig config = parse("""
            npc:
              navigation:
                rule-checks-per-tick: 25
                recheck-ticks: 20
                stop-on-trespass: true
                regions:
                  market:
                    stop-on-trespass: false
            """);
        assertTrue(config.rejected().isEmpty(), config.rejected().toString());
        assertEquals(25, config.ruleChecksPerTick());
        assertEquals(20, config.settings().value(NavigationSettings.RECHECK_TICKS, Scope.of("w")));
        assertTrue(config.settings().value(NavigationSettings.STOP_ON_TRESPASS, Scope.of("w")));
        assertFalse(config.settings().value(NavigationSettings.STOP_ON_TRESPASS,
            new Scope("w", java.util.List.of("market"), null, null)), "the region layer wins");

        NpcNavigationConfig bad = parse("""
            npc:
              navigation:
                rule-checks-per-tick: 0
                stop-on-trespass: sometimes
            """);
        assertEquals(2, bad.rejected().size(), bad.rejected().toString());
        assertEquals(10, bad.ruleChecksPerTick());
        assertFalse(bad.settings().value(NavigationSettings.STOP_ON_TRESPASS, Scope.of("w")));
        assertEquals(10, NpcNavigationConfig.disabled().ruleChecksPerTick());
    }

    /** Implementation plan 4b.2 config keys: the decided defaults ship, and every key resolves through the layers. */
    @Test
    void readsTheWallCornerKeysInEveryConfigLayer() throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(
            NpcNavigationConfigTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            shipped.load(reader);
        }
        NavigationSettings defaults = NpcNavigationConfig.from(shipped.getConfigurationSection(NpcNavigationConfig.PATH))
            .settings();
        Scope anywhere = Scope.of("w");
        assertEquals(1.0, defaults.value(NavigationSettings.CORNER_CLEARANCE, anywhere));
        assertEquals(0.5, defaults.value(NavigationSettings.WAYPOINT_MARGIN, anywhere));
        assertEquals(20, defaults.value(NavigationSettings.RECOVERY_WAIT_TICKS, anywhere));
        assertEquals(40, defaults.value(NavigationSettings.STATIONARY_TICKS, anywhere));
        assertEquals(40, defaults.value(NavigationSettings.NO_PROGRESS_TICKS, anywhere));
        assertEquals(3, defaults.value(NavigationSettings.MAX_RETRIES, anywhere));
        assertEquals(Layer.GLOBAL, defaults.resolve(NavigationSettings.CORNER_CLEARANCE, anywhere).layer(),
            "config.yml sets it in the global layer");

        NpcNavigationConfig config = parse("""
            npc:
              navigation:
                corner-clearance: 0.5
                worlds:
                  w:
                    waypoint-margin: 0.75
                regions:
                  stairhouse:
                    corner-clearance: 0
                    recovery-wait-ticks: 10
                    stationary-ticks: 30
                    no-progress-ticks: 30
                    max-retries: 1
            """);
        assertTrue(config.rejected().isEmpty(), config.rejected().toString());
        NavigationSettings settings = config.settings();
        Scope inside = new Scope("w", List.of("town", "stairhouse"), null, null);
        assertEquals(0.5, settings.value(NavigationSettings.CORNER_CLEARANCE, anywhere));
        assertEquals(0.75, settings.value(NavigationSettings.WAYPOINT_MARGIN, anywhere));
        assertEquals(0.0, settings.value(NavigationSettings.CORNER_CLEARANCE, inside), "0 turns it off in a region");
        net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings resolved =
            net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings.of(settings, inside);
        assertEquals(0.0, resolved.cornerClearance());
        assertEquals(0.75, resolved.waypointMargin());
        assertEquals(10, resolved.recoveryWaitTicks());
        assertEquals(30, resolved.noProgressTicks());
        assertEquals(1, resolved.maxRetries());

        NpcNavigationConfig bad = parse("""
            npc:
              navigation:
                corner-clearance: 3
                waypoint-margin: 0.1
                recovery-wait-ticks: 500
            """);
        assertEquals(3, bad.rejected().size(), bad.rejected().toString());
    }

    @Test
    void readsTheCorridorSteeringKeysInEveryConfigLayer() throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(
            NpcNavigationConfigTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            shipped.load(reader);
        }
        NavigationSettings defaults = NpcNavigationConfig.from(shipped.getConfigurationSection(NpcNavigationConfig.PATH))
            .settings();
        Scope anywhere = Scope.of("w");
        assertEquals(false, defaults.value(NavigationSettings.CORRIDOR_STEERING, anywhere), "off by default");
        assertEquals(4.0, defaults.value(NavigationSettings.CORRIDOR_HALF_WIDTH, anywhere));
        assertEquals(0.8, defaults.value(NavigationSettings.WALL_CLEARANCE, anywhere));
        assertEquals(Layer.GLOBAL, defaults.resolve(NavigationSettings.CORRIDOR_STEERING, anywhere).layer());

        NpcNavigationConfig config = parse("""
            npc:
              navigation:
                corridor-steering: true
                worlds:
                  w:
                    wall-clearance: 0.6
                regions:
                  stairhouse:
                    corridor-steering: false
                    corridor-half-width: 3
            """);
        assertTrue(config.rejected().isEmpty(), config.rejected().toString());
        NavigationSettings settings = config.settings();
        Scope inside = new Scope("w", List.of("town", "stairhouse"), null, null);
        assertEquals(true, settings.value(NavigationSettings.CORRIDOR_STEERING, anywhere));
        assertEquals(0.6, settings.value(NavigationSettings.WALL_CLEARANCE, anywhere));
        net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings resolved =
            net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings.of(settings, inside);
        assertEquals(false, resolved.corridorSteering(), "a region turns it off");
        assertEquals(3.0, resolved.corridorHalfWidth());
        assertEquals(0.6, resolved.wallClearance(), "from the world layer");

        NpcNavigationConfig bad = parse("""
            npc:
              navigation:
                corridor-steering: maybe
                corridor-half-width: 9
                wall-clearance: 0.3
            """);
        assertEquals(3, bad.rejected().size(), bad.rejected().toString());
    }

    @Test
    void readsThePlazaLinesKeysInEveryConfigLayer() throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(
            NpcNavigationConfigTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            shipped.load(reader);
        }
        NavigationSettings defaults = NpcNavigationConfig.from(shipped.getConfigurationSection(NpcNavigationConfig.PATH))
            .settings();
        Scope anywhere = Scope.of("w");
        assertEquals(false, defaults.value(NavigationSettings.PLAZA_LINES, anywhere), "off by default");
        assertEquals(16, defaults.value(NavigationSettings.PLAZA_CORRIDOR_BLOCKS, anywhere));
        assertEquals(Layer.GLOBAL, defaults.resolve(NavigationSettings.PLAZA_LINES, anywhere).layer(),
            "the shipped config.yml has the key");
        assertEquals(Layer.GLOBAL, defaults.resolve(NavigationSettings.PLAZA_CORRIDOR_BLOCKS, anywhere).layer(),
            "the shipped config.yml has the key");

        NpcNavigationConfig config = parse("""
            npc:
              navigation:
                plaza-lines: true
                plaza-corridor-blocks: 12
                worlds:
                  w2:
                    plaza-lines: false
                regions:
                  market:
                    plaza-corridor-blocks: 24
            """);
        assertTrue(config.rejected().isEmpty(), config.rejected().toString());
        NavigationSettings settings = config.settings();
        assertEquals(true, settings.value(NavigationSettings.PLAZA_LINES, anywhere), "the global layer");
        assertEquals(false, settings.value(NavigationSettings.PLAZA_LINES, Scope.of("w2")), "a world turns it off");
        net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings resolved =
            net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings.of(settings,
                new Scope("w", List.of("town", "market"), null, null));
        assertEquals(true, resolved.plazaLines());
        assertEquals(24, resolved.plazaCorridorBlocks(), "a region widens it");
        assertEquals(12, net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings.of(settings, anywhere)
            .plazaCorridorBlocks());

        NpcNavigationConfig bad = parse("""
            npc:
              navigation:
                plaza-lines: sometimes
                plaza-corridor-blocks: 2
            """);
        assertEquals(2, bad.rejected().size(), bad.rejected().toString());
    }

    @Test
    void readsTheCrowdAwarenessKeyInEveryConfigLayer() throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(
            NpcNavigationConfigTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            shipped.load(reader);
        }
        NavigationSettings defaults = NpcNavigationConfig.from(shipped.getConfigurationSection(NpcNavigationConfig.PATH))
            .settings();
        Scope anywhere = Scope.of("w");
        assertEquals(false, defaults.value(NavigationSettings.CROWD_AWARENESS, anywhere), "off by default");
        assertEquals(Layer.GLOBAL, defaults.resolve(NavigationSettings.CROWD_AWARENESS, anywhere).layer(),
            "the shipped config.yml has the key");

        NpcNavigationConfig config = parse("""
            npc:
              navigation:
                crowd-awareness: true
                worlds:
                  w2:
                    crowd-awareness: false
                regions:
                  stairhouse:
                    crowd-awareness: false
            """);
        assertTrue(config.rejected().isEmpty(), config.rejected().toString());
        NavigationSettings settings = config.settings();
        assertEquals(true, settings.value(NavigationSettings.CROWD_AWARENESS, anywhere), "the global layer");
        assertEquals(false, settings.value(NavigationSettings.CROWD_AWARENESS, Scope.of("w2")), "a world turns it off");
        net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings resolved =
            net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings.of(settings,
                new Scope("w", List.of("town", "stairhouse"), null, null));
        assertEquals(false, resolved.crowdAwareness(), "a region turns it off");
        assertEquals(true, net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings.of(settings, anywhere)
            .crowdAwareness());

        NpcNavigationConfig bad = parse("""
            npc:
              navigation:
                crowd-awareness: sometimes
            """);
        assertEquals(1, bad.rejected().size(), bad.rejected().toString());
    }

    @Test
    void readsTheLocalLegsClearanceKeyInEveryConfigLayer() throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(
            NpcNavigationConfigTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            shipped.load(reader);
        }
        NavigationSettings defaults = NpcNavigationConfig.from(shipped.getConfigurationSection(NpcNavigationConfig.PATH))
            .settings();
        Scope anywhere = Scope.of("w");
        assertEquals(false, defaults.value(NavigationSettings.LOCAL_LEGS_CLEARANCE, anywhere), "off by default");
        assertEquals(Layer.GLOBAL, defaults.resolve(NavigationSettings.LOCAL_LEGS_CLEARANCE, anywhere).layer(),
            "the shipped config.yml has the key");

        NpcNavigationConfig config = parse("""
            npc:
              navigation:
                local-legs-clearance: true
                worlds:
                  w2:
                    local-legs-clearance: false
                regions:
                  stairhouse:
                    local-legs-clearance: false
            """);
        assertTrue(config.rejected().isEmpty(), config.rejected().toString());
        NavigationSettings settings = config.settings();
        assertEquals(true, settings.value(NavigationSettings.LOCAL_LEGS_CLEARANCE, anywhere), "the global layer");
        assertEquals(false, settings.value(NavigationSettings.LOCAL_LEGS_CLEARANCE, Scope.of("w2")), "a world turns it off");
        net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings resolved =
            net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings.of(settings,
                new Scope("w", List.of("town", "stairhouse"), null, null));
        assertEquals(false, resolved.localLegsClearance(), "a region turns it off");
        assertEquals(true, net.knightsandkings.knk.core.npc.nav.NpcNavigationService.Settings.of(settings, anywhere)
            .localLegsClearance());

        NpcNavigationConfig bad = parse("""
            npc:
              navigation:
                local-legs-clearance: sometimes
            """);
        assertEquals(1, bad.rejected().size(), bad.rejected().toString());
    }

    @Test
    void readsTheGroupKeysInEveryConfigLayer() throws Exception {
        NpcNavigationConfig config = parse("""
            npc:
              navigation:
                group-formation: ranks
                group-spacing: 1.5
                group-ranks-width: 3
                group-rejoin-blocks: 6
                group-regroup-blocks: 0
                group-catch-up-speed: 1.5
                worlds:
                  w:
                    group-formation: cluster
                regions:
                  keep:
                    group-formation: escort-ring
                    group-spacing: 3
            """);
        assertTrue(config.rejected().isEmpty(), config.rejected().toString());
        Scope elsewhere = Scope.of("other");
        assertEquals("ranks", config.settings().value(NavigationSettings.GROUP_FORMATION, elsewhere));
        assertEquals(1.5, config.settings().value(NavigationSettings.GROUP_SPACING, elsewhere));
        assertEquals(3, config.settings().value(NavigationSettings.GROUP_RANKS_WIDTH, elsewhere));
        assertEquals(6.0, config.settings().value(NavigationSettings.GROUP_REJOIN_BLOCKS, elsewhere));
        assertEquals(0.0, config.settings().value(NavigationSettings.GROUP_REGROUP_BLOCKS, elsewhere));
        assertEquals(1.5, config.settings().value(NavigationSettings.GROUP_CATCH_UP_SPEED, elsewhere));
        assertEquals("cluster", config.settings().value(NavigationSettings.GROUP_FORMATION, Scope.of("w")));
        Scope inTheKeep = new Scope("w", java.util.List.of("keep"), null, null);
        assertEquals(new NavigationSettings.Resolved<>("escort-ring", Layer.REGION, "keep"),
            config.settings().resolve(NavigationSettings.GROUP_FORMATION, inTheKeep));
        net.knightsandkings.knk.core.npc.nav.group.GroupSettings group =
            net.knightsandkings.knk.core.npc.nav.group.GroupSettings.of(config.settings(), inTheKeep);
        assertEquals(net.knightsandkings.knk.core.npc.nav.group.Formation.Type.ESCORT_RING, group.formation().type());
        assertEquals(3.0, group.formation().spacing());
        assertEquals(0.0, group.regroupBlocks());

        NpcNavigationConfig bad = parse("""
            npc:
              navigation:
                group-formation: wedge
                group-spacing: 0
                group-ranks-width: 1
                group-rejoin-blocks: 100
                group-regroup-blocks: -5
                group-catch-up-speed: 5
            """);
        assertEquals(6, bad.rejected().size(), bad.rejected().toString());
        assertEquals("column", bad.settings().value(NavigationSettings.GROUP_FORMATION, elsewhere), "the default stays");
        assertEquals(1.3, bad.settings().value(NavigationSettings.GROUP_CATCH_UP_SPEED, elsewhere));
    }

    @Test
    void readsTheFormationKeepingKeysInEveryConfigLayer() throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(
            NpcNavigationConfigTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            shipped.load(reader);
        }
        NavigationSettings defaults = NpcNavigationConfig.from(shipped.getConfigurationSection(NpcNavigationConfig.PATH))
            .settings();
        Scope anywhere = Scope.of("w");
        assertEquals("levels", defaults.value(NavigationSettings.GROUP_FORMATION_KEEPING, anywhere), "levels by default");
        assertEquals(0.6, defaults.value(NavigationSettings.GROUP_LATERAL_SPACING_MIN, anywhere));
        assertEquals(1.0, defaults.value(NavigationSettings.GROUP_SPACING_MIN, anywhere));
        assertEquals(4, defaults.value(NavigationSettings.GROUP_LOOKAHEAD_BLOCKS, anywhere));
        for (NavigationSettings.Key<?> key : List.of(NavigationSettings.GROUP_FORMATION_KEEPING,
            NavigationSettings.GROUP_LATERAL_SPACING_MIN, NavigationSettings.GROUP_SPACING_MIN,
            NavigationSettings.GROUP_LOOKAHEAD_BLOCKS)) {
            assertEquals(Layer.GLOBAL, defaults.resolve(key, anywhere).layer(), "the shipped config.yml has " + key);
        }

        NpcNavigationConfig config = parse("""
            npc:
              navigation:
                group-formation-keeping: elastic
                group-lateral-spacing-min: 0.75
                group-spacing-min: 1.25
                group-lookahead-blocks: 6
                worlds:
                  w2:
                    group-formation-keeping: levels
                regions:
                  stairhouse:
                    group-lookahead-blocks: 2
            """);
        assertTrue(config.rejected().isEmpty(), config.rejected().toString());
        net.knightsandkings.knk.core.npc.nav.group.GroupSettings.Keeping keeping =
            net.knightsandkings.knk.core.npc.nav.group.GroupSettings.of(config.settings(), anywhere).keeping();
        assertTrue(keeping.elastic(), "the global layer");
        assertEquals(0.75, keeping.lateralSpacingMin());
        assertEquals(1.25, keeping.spacingMin());
        assertEquals(6, keeping.lookaheadBlocks());
        assertFalse(net.knightsandkings.knk.core.npc.nav.group.GroupSettings.of(config.settings(), Scope.of("w2"))
            .keeping().elastic(), "a world keeps levels");
        assertEquals(2, net.knightsandkings.knk.core.npc.nav.group.GroupSettings.of(config.settings(),
            new Scope("w", List.of("stairhouse"), null, null)).keeping().lookaheadBlocks(), "a region");

        NpcNavigationConfig bad = parse("""
            npc:
              navigation:
                group-formation-keeping: wobbly
                group-lateral-spacing-min: 0.2
                group-spacing-min: 0.1
                group-lookahead-blocks: 99
            """);
        assertEquals(4, bad.rejected().size(), bad.rejected().toString());
        assertEquals("levels", bad.settings().value(NavigationSettings.GROUP_FORMATION_KEEPING, anywhere));
    }

    @Test
    void readsTheNarrowedFormationKeysInEveryConfigLayer() throws Exception {
        NpcNavigationConfig config = parse("""
            npc:
              navigation:
                group-lateral-spacing: 1.25
                group-width-hysteresis-blocks: 5
                group-followers-finish-trip: false
                worlds:
                  w:
                    group-followers-finish-trip: true
                regions:
                  market:
                    group-width-hysteresis-blocks: 0
            """);
        assertTrue(config.rejected().isEmpty(), config.rejected().toString());
        Scope elsewhere = Scope.of("other");
        assertEquals(1.25, config.settings().value(NavigationSettings.GROUP_LATERAL_SPACING, elsewhere));
        assertEquals(1.5, config.settings().value(NavigationSettings.GROUP_SPACING, elsewhere), "the new default");
        assertEquals(5, config.settings().value(NavigationSettings.GROUP_WIDTH_HYSTERESIS_BLOCKS, elsewhere));
        assertEquals(false, config.settings().value(NavigationSettings.GROUP_FOLLOWERS_FINISH_TRIP, elsewhere));
        assertEquals(new NavigationSettings.Resolved<>(true, Layer.WORLD, "w"),
            config.settings().resolve(NavigationSettings.GROUP_FOLLOWERS_FINISH_TRIP, Scope.of("w")));
        Scope inTheMarket = new Scope("w", java.util.List.of("market"), null, null);
        net.knightsandkings.knk.core.npc.nav.group.GroupSettings group =
            net.knightsandkings.knk.core.npc.nav.group.GroupSettings.of(config.settings(), inTheMarket);
        assertEquals(0, group.widthHysteresisBlocks());
        assertTrue(group.followersFinishTrip());
        assertEquals(1.25, group.formation().lateralSpacing());

        NpcNavigationConfig bad = parse("""
            npc:
              navigation:
                group-lateral-spacing: 0.5
                group-width-hysteresis-blocks: 20
                group-followers-finish-trip: sometimes
            """);
        assertEquals(3, bad.rejected().size(), bad.rejected().toString());
        assertEquals(1.0, bad.settings().value(NavigationSettings.GROUP_LATERAL_SPACING, elsewhere), "the default stays");
    }

    @Test
    void theShippedConfigHasTheNarrowedFormationKeysWithTheirDefaults() throws Exception {
        String shipped;
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            shipped = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\r\n", "\n"); // a Windows checkout (core.autocrlf) has CRLF line ends
        }
        assertTrue(shipped.contains("    group-spacing: 1.5\n"), "group-spacing 1.5");
        assertTrue(shipped.contains("    group-lateral-spacing: 1.0\n"));
        assertTrue(shipped.contains("    group-width-hysteresis-blocks: 3\n"));
        assertTrue(shipped.contains("    group-followers-finish-trip: false\n"));
    }
}
