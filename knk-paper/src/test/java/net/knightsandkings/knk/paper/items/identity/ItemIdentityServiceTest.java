package net.knightsandkings.knk.paper.items.identity;

import net.knightsandkings.knk.core.items.identity.ItemConversionLog.Count;
import net.knightsandkings.knk.core.items.identity.ItemConversionLog.Source;
import net.knightsandkings.knk.core.items.identity.ItemIdentityResolution.Kind;
import net.knightsandkings.knk.core.items.identity.MaterialBlueprintMap;
import net.knightsandkings.knk.paper.mapper.BlueprintTag;
import net.knightsandkings.knk.paper.mapper.ItemInstanceTag;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.Optional;

import static net.knightsandkings.knk.paper.items.identity.ItemIdentityTestSupport.named;
import static net.knightsandkings.knk.paper.items.identity.ItemIdentityTestSupport.service;
import static net.knightsandkings.knk.paper.items.identity.ItemIdentityTestSupport.taggedPlain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#141: the one resolver on real item stacks, the retag rule, the log and its report. */
class ItemIdentityServiceTest {

    private final ItemIdentityTestSupport.FakeApi api = new ItemIdentityTestSupport.FakeApi();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void resolve_followsInstanceThenBlueprintThenVanillaMatch() {
        ItemIdentityService service = service(api, true);

        assertEquals(10, service.resolve(new ItemStack(Material.OAK_LOG)).blueprintId());

        ItemStack unique = named(Material.DIAMOND_SWORD, "Steel Sword");
        ItemMeta meta = unique.getItemMeta();
        ItemInstanceTag.stamp(meta, 77L);
        unique.setItemMeta(meta);
        assertEquals(Kind.INSTANCE, service.resolve(unique).kind());

        BlueprintTag.setStampingEnabled(true);
        try {
            ItemStack bread = named(Material.BREAD, "Phoenix Bread");
            BlueprintTag.stampIfEnabled(bread, 55);
            assertEquals(55, service.resolve(bread).blueprintId());
        } finally {
            BlueprintTag.setStampingEnabled(false);
        }

        assertEquals("unresolved_custom_name", service.resolve(named(Material.BREAD, "Bread from another plugin")).outcome());
        assertEquals("unresolved_no_match", service.resolve(new ItemStack(Material.STONE)).outcome());
    }

    @Test
    void convert_leavesPlainItemsAlone_stripsAStaleTag_andCountsTheStackSize() {
        ItemIdentityService service = service(api, true);
        ItemStack plain = new ItemStack(Material.OAK_LOG, 16);
        ItemStack stale = taggedPlain(Material.OAK_LOG, 4, 10);
        ItemStack namedBread = named(Material.BREAD, "x");

        assertFalse(service.convert(plain, Source.PICKUP));
        assertTrue(service.convert(stale, Source.PICKUP));
        assertFalse(service.convert(namedBread, Source.CRAFT));

        assertEquals(Optional.empty(), BlueprintTag.read(stale));
        assertTrue(stale.isSimilar(plain));
        assertTrue(namedBread.getItemMeta().hasDisplayName());
        assertEquals(List.of(
                new Count("craft", "unresolved_custom_name", "minecraft:bread", 1),
                new Count("pickup", "normalized", "minecraft:oak_log", 4),
                new Count("pickup", "vanilla_match", "minecraft:oak_log", 16)), service.conversionLog().drain());
    }

    @Test
    void convert_beforeTheMapIsLoaded_countsNothing() {
        ItemIdentityService service = service(api, false);
        service.convert(new ItemStack(Material.OAK_LOG), Source.PICKUP);
        assertTrue(service.conversionLog().drain().isEmpty());
    }

    @Test
    void refreshMap_loadsTheMap_andAFailureKeepsThePreviousOne() {
        ItemIdentityService service = service(api, false);
        service.refreshMap().join();
        assertTrue(service.mapLoaded());
        assertEquals(4, service.map().size());

        api.fail = true;
        service.refreshMap().join();
        assertEquals(4, service.map().size());
    }

    @Test
    void report_sendsTheCounts_andKeepsThemWhenTheApiIsDown() {
        ItemIdentityService service = service(api, true);
        service.convert(new ItemStack(Material.OAK_LOG, 3), Source.BLOCK_DROP);

        api.fail = true;
        service.reportAsync().join();
        assertTrue(api.reports.isEmpty());

        api.fail = false;
        service.convert(new ItemStack(Material.OAK_LOG, 2), Source.BLOCK_DROP);
        service.reportAsync().join();
        assertEquals(List.of(List.of(new Count("block_drop", "vanilla_match", "minecraft:oak_log", 5))), api.reports);
    }

    @Test
    void settings_areOffByDefault_andConvertNeedsEnabled() {
        YamlConfiguration config = new YamlConfiguration();
        assertEquals(ItemIdentitySettings.DISABLED, ItemIdentitySettings.fromConfig(config));
        config.set("items.identity.enabled", false);
        config.set("items.identity.convert-on-obtain", true);
        assertFalse(ItemIdentitySettings.fromConfig(config).convertOnObtain());
        config.set("items.identity.enabled", true);
        config.set("items.identity.map-refresh-minutes", 0);
        ItemIdentitySettings on = ItemIdentitySettings.fromConfig(config);
        assertTrue(on.enabled() && on.convertOnObtain());
        assertEquals(1, on.mapRefreshMinutes());
    }

    @Test
    void bundledConfig_shipsBothSwitchesOff() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            ItemIdentitySettings settings = ItemIdentitySettings.fromConfig(bundled);
            assertFalse(settings.enabled());
            assertFalse(settings.convertOnObtain());
            assertTrue(bundled.isSet("items.identity.convert-on-obtain"));
        }
    }

    @Test
    void emptyMap_isTheDefault() {
        assertEquals(MaterialBlueprintMap.EMPTY, service(api, false).map());
    }
}
