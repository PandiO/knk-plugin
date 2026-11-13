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
}
