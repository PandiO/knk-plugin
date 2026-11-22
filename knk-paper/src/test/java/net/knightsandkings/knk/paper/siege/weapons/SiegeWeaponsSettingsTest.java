package net.knightsandkings.knk.paper.siege.weapons;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import net.knightsandkings.knk.api.realtime.RegionQueryEvents;
import net.knightsandkings.knk.core.regions.query.RegionShape;
import net.knightsandkings.knk.core.siege.weapons.SpotSiteCheck;
import net.knightsandkings.knk.paper.realtime.RealtimeDispatcher;
import net.knightsandkings.knk.paper.realtime.RegionQueryRealtimeSync;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code siege.weapons} block and A04: with the flag off (the default) nothing of the feature is registered. Since
 * #988 that is the weapon spot site check over the game hub; the spot itself is drawn with the normal region task.
 */
class SiegeWeaponsSettingsTest {

    @Test
    void theBundledConfig_HasTheFlag_Off() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in);
            YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertTrue(config.contains(SiegeWeaponsSettings.ENABLED_PATH));
            assertFalse(SiegeWeaponsSettings.from(config).enabled());
            // The existing siege key is untouched next to the new block.
            assertTrue(config.getInt("siege.rejoin-window-seconds") > 0);
        }
    }

    @Test
    void aMissingBlock_IsOff_AndTrueIsRead() {
        assertFalse(SiegeWeaponsSettings.from(new YamlConfiguration()).enabled());
        assertFalse(SiegeWeaponsSettings.from(null).enabled());
        YamlConfiguration on = new YamlConfiguration();
        on.set("siege.weapons.enabled", true);
        assertTrue(SiegeWeaponsSettings.from(on).enabled());
    }

    // ---- #988: the spot site check over the game hub, only with the flag on ----

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RegionShape SPOT = new RegionShape("siege", "cuboid", 0, 64, 0, 6, 68, 8, List.of());

    private final List<Object[]> sent = new ArrayList<>();

    private RegionQueryRealtimeSync register(SiegeWeaponsSettings settings, RealtimeDispatcher dispatcher,
                                             Function<String, SpotSiteCheck.BlockProbe> probes) {
        return SiegeWeaponsModule.registerSiteCheck(settings, dispatcher, Runnable::run,
                (regionId, world) -> "siege_spot_3".equals(regionId) ? Optional.of(SPOT) : Optional.empty(), probes,
                (method, body) -> sent.add(new Object[] {method, body}), Logger.getAnonymousLogger());
    }

    private static void ask(RealtimeDispatcher dispatcher, String queryId, String regionId) {
        dispatcher.dispatch(RealtimeEnvelope.parse("{\"v\":1,\"seq\":1,\"type\":\"SpotSiteCheckQuery\",\"at\":null,"
                + "\"actor\":null,\"payload\":{\"queryId\":\"" + queryId + "\",\"regionId\":\"" + regionId + "\"}}", MAPPER));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(int i) {
        return (Map<String, Object>) sent.get(i)[1];
    }

    @Test
    void flagOff_RegistersNoSiteCheck_SoTheApiGetsNoAnswer() {
        RealtimeDispatcher dispatcher = new RealtimeDispatcher(Logger.getAnonymousLogger());
        assertNull(register(SiegeWeaponsSettings.defaults(), dispatcher, w -> null));
        assertFalse(dispatcher.types().contains(RegionQueryEvents.SPOT_SITE_CHECK_QUERY));
        assertFalse(dispatcher.dispatch(RealtimeEnvelope.parse("{\"v\":1,\"seq\":1,\"type\":\"SpotSiteCheckQuery\","
                + "\"at\":null,\"actor\":null,\"payload\":{\"queryId\":\"q\",\"regionId\":\"siege_spot_3\"}}", MAPPER)));
        assertTrue(sent.isEmpty());
    }

    @Test
    void flagOn_AnswersTheSiteCheck_FromTheWorldsBlocks() {
        ServerMock server = MockBukkit.mock();
        try {
            WorldMock world = new WorldMock(Material.AIR, 0);
            world.setName("siege");
            server.addWorld(world);
            // A stone floor under the 7 x 9 spot, except one block; one block of stone inside the spot.
            for (int x = 0; x <= 6; x++) for (int z = 0; z <= 8; z++) world.getBlockAt(x, 63, z).setType(Material.STONE);
            world.getBlockAt(3, 63, 3).setType(Material.AIR);
            world.getBlockAt(2, 65, 2).setType(Material.STONE);
            RealtimeDispatcher dispatcher = new RealtimeDispatcher(Logger.getAnonymousLogger());
            assertNotNull(register(new SiegeWeaponsSettings(true), dispatcher,
                    name -> server.getWorld(name) == null ? null : new BukkitBlockProbe(server.getWorld(name))));

            ask(dispatcher, "q1", "siege_spot_3");

            assertEquals(RegionQueryEvents.SPOT_SITE_CHECK_RESULT, sent.get(0)[0]);
            assertEquals("found", body(0).get("result"));
            assertEquals(63, body(0).get("floorBlocks"));
            assertEquals(1, body(0).get("openFloor"));
            assertEquals(1L, body(0).get("blockedVolume"));
            assertEquals(2, ((List<?>) body(0).get("warnings")).size());

            ask(dispatcher, "q2", "nowhere");
            assertEquals("not-found", body(1).get("result"));
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void flagOn_AWorldThatIsntLoaded_AnswersError() {
        RealtimeDispatcher dispatcher = new RealtimeDispatcher(Logger.getAnonymousLogger());
        register(new SiegeWeaponsSettings(true), dispatcher, name -> null);
        ask(dispatcher, "q1", "siege_spot_3");
        assertEquals("error", body(0).get("result"));
        assertEquals("World siege isn't loaded.", body(0).get("message"));
    }
}
