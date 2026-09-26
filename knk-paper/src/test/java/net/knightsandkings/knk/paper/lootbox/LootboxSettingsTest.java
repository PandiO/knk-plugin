package net.knightsandkings.knk.paper.lootbox;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lootboxes Phase 3: the config.yml {@code lootboxes:} section and its defaults. */
class LootboxSettingsTest {

    @Test
    void missingSection_isTheDesignDefaults() {
        LootboxSettings settings = LootboxSettings.from(null);

        assertTrue(settings.enabled());
        assertEquals(60, settings.runtimeRefreshSeconds());
        assertEquals(5, settings.claimMaxDistance());
        assertTrue(settings.refuseWhenFull());
        assertFalse(settings.staffModeCanClaim());
        assertTrue(settings.forbiddenGround().containsAll(Set.of("WATER", "LAVA", "MAGMA_BLOCK", "CACTUS", "POWDER_SNOW")));
        assertEquals("&d", settings.colorFor(5));
    }

    @Test
    void section_overridesAndNormalises() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("lootboxes.enabled", false);
        config.set("lootboxes.server-id", " survival-1 ");
        config.set("lootboxes.full-inventory", "drop-owned");
        config.set("lootboxes.claim-max-distance", 3.5);
        config.set("lootboxes.runtime-refresh-seconds", 1);
        config.set("lootboxes.surface.forbidden-ground", List.of("water", " sand "));
        config.set("lootboxes.display.grade-colors.5", "&6");
        config.set("lootboxes.display.grade-colors.x", "&1");

        LootboxSettings settings = LootboxSettings.from(config.getConfigurationSection("lootboxes"));

        assertFalse(settings.enabled());
        assertEquals("survival-1", settings.serverId());
        assertFalse(settings.refuseWhenFull());
        assertEquals(3.5, settings.claimMaxDistance());
        assertEquals(10, settings.runtimeRefreshSeconds(), "clamped to at least 10 s");
        assertEquals(Set.of("WATER", "SAND"), settings.forbiddenGround());
        assertEquals("&6", settings.colorFor(5));
        assertEquals("&9", settings.colorFor(1));
    }

    @Test
    void coloredLabel_addsColourAndStars() {
        assertEquals("&dLegendary Weapons Lootbox ★★★★★", LootboxSettings.defaults().coloredLabel("Legendary Weapons Lootbox", 5));
        assertEquals("&9Lootbox ★", LootboxSettings.defaults().coloredLabel(null, 1));
    }
}
