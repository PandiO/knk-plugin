package net.knightsandkings.knk.paper.siege.weapons;

import net.knightsandkings.knk.core.ports.api.SiegeWeaponSpotsApi;
import net.knightsandkings.knk.paper.commands.KnkAdminCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** The {@code siege.weapons} block and A04: with the flag off (the default) nothing of the feature is registered. */
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

    @Test
    void flagOff_RegistersNoCommandNoListenerAndCallsNoApi() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        KnkAdminCommand admin = mock(KnkAdminCommand.class);
        SiegeWeaponSpotsApi api = mock(SiegeWeaponSpotsApi.class);

        assertNull(SiegeWeaponsModule.register(plugin, SiegeWeaponsSettings.defaults(), admin, api, player -> Optional.empty()));

        verifyNoInteractions(plugin, admin, api);
    }
}
