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

import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
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
}
