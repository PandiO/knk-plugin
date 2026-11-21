package net.knightsandkings.knk.api.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.dto.GameSettingsDto;
import net.knightsandkings.knk.core.domain.settings.KnkMenuItemSettings;

/** The game settings' {@code menuItem} section (PandiO/knk-workspace#870) reaches the plugin (#871, A29). */
class GameSettingsMenuItemMapperTest {

    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Shaped like knk-web-api's {@code MenuItemSettings.Defaults()} as {@code GET api/GameSettings} sends it. */
    static final String SEEDED = """
        {"joinSpawnMode":"WorldSpawn","menuItem":{"enabled":true,"giveTo":"all","giveOnJoin":true,"keepOnDeath":true,
         "material":"NETHER_STAR","name":"&6&lMenu &7(click to open)","lore":["&7Opens the Knights & Kings menu (&e/menu&7)."],
         "slot":8,"droppable":false,"movable":false,
         "clicks":{"LEFT_CLICK":{"type":"OpenMenu","menuKey":"main"},"RIGHT_CLICK":{"type":"OpenMenu","menuKey":"main"},
                   "SHIFT_LEFT_CLICK":{"type":"OpenMenu","menuKey":"main"},"SHIFT_RIGHT_CLICK":{"type":"OpenMenu","menuKey":"main"},
                   "INVENTORY_CLICK":{"type":"OpenMenu","menuKey":"main"}},
         "overrides":{"siege.hub":{"LEFT_CLICK":{"type":"OpenMenu","menuKey":"siege.information"}},
                      "siege.match":{"SHIFT_RIGHT_CLICK":{"type":"OpenMenu","menuKey":"siege.shop"},
                                     "LEFT_CLICK":{"type":"RunCommand","command":"siege menu"}}}}}
        """;

    @Test
    void theSectionIsRead() throws Exception {
        KnkMenuItemSettings m = GameSettingsMapper.toCore(json.readValue(SEEDED, GameSettingsDto.class)).menuItem();

        assertTrue(m.enabled());
        assertEquals("all", m.giveTo());
        assertEquals("NETHER_STAR", m.material());
        assertEquals(List.of("&7Opens the Knights & Kings menu (&e/menu&7)."), m.lore());
        assertEquals(8, m.slot());
        assertEquals(5, m.clicks().size());
        assertEquals(new KnkMenuItemSettings.Action("OpenMenu", "main", null), m.clicks().get("INVENTORY_CLICK"));
        assertEquals(List.of("siege.hub", "siege.match"), List.copyOf(m.overrides().keySet()));
        assertEquals(new KnkMenuItemSettings.Action("OpenMenu", "siege.shop", null),
            m.overrides().get("siege.match").get("SHIFT_RIGHT_CLICK"));
        assertEquals(new KnkMenuItemSettings.Action("RunCommand", null, "siege menu"),
            m.overrides().get("siege.match").get("LEFT_CLICK"));
    }

    @Test
    void anOlderApiWithoutTheSectionSendsNone_SoTheConfigFileStays() throws Exception {
        assertNull(GameSettingsMapper.toCore(json.readValue("{\"joinSpawnMode\":\"WorldSpawn\"}", GameSettingsDto.class)).menuItem());
    }

    @Test
    void missingFieldsTakeTheConfigFileDefaults() throws Exception {
        KnkMenuItemSettings m = GameSettingsMapper.toCore(json.readValue("{\"menuItem\":{}}", GameSettingsDto.class)).menuItem();
        assertTrue(m.enabled());
        assertTrue(m.giveOnJoin());
        assertEquals(8, m.slot());
        assertFalse(m.movable());
        assertTrue(m.clicks().isEmpty());
        assertTrue(m.overrides().isEmpty());
    }
}
