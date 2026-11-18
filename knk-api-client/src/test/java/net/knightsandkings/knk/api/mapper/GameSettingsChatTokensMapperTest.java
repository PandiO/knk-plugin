package net.knightsandkings.knk.api.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.dto.GameSettingsDto;
import net.knightsandkings.knk.core.domain.settings.KnkChatTokenSettings;

/** The Game Settings chat-token limits (PandiO/knk-workspace#233) reach the plugin; missing = off. */
class GameSettingsChatTokensMapperTest {

    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void theLimitsAreRead() throws Exception {
        var dto = json.readValue("{\"joinSpawnMode\":\"WorldSpawn\",\"chatTokens\":{\"maxTokensPerMessage\":3,\"statCooldownSeconds\":10}}",
            GameSettingsDto.class);

        assertEquals(new KnkChatTokenSettings(3, 10), GameSettingsMapper.toCore(dto).chatTokens());
    }

    @Test
    void anOlderApiWithoutTheBlock_OrAMissingField_IsOff() throws Exception {
        assertEquals(KnkChatTokenSettings.off(),
            GameSettingsMapper.toCore(json.readValue("{\"joinSpawnMode\":\"WorldSpawn\"}", GameSettingsDto.class)).chatTokens());
        assertEquals(new KnkChatTokenSettings(2, 0), GameSettingsMapper.toCore(
            json.readValue("{\"chatTokens\":{\"maxTokensPerMessage\":2}}", GameSettingsDto.class)).chatTokens());
        assertEquals(KnkChatTokenSettings.off(), new KnkChatTokenSettings(-4, -1));
    }
}
