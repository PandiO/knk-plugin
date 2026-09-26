package net.knightsandkings.knk.api.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.knightsandkings.knk.api.dto.TitleBracketDto;
import net.knightsandkings.knk.api.dto.UserSummaryDto;
import net.knightsandkings.knk.core.domain.users.TitleBracket;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** InventoryMenu content port CP3: title-bracket list and user gender over the wire. */
class TitleBracketsMapperTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void mapsTheWebApiJson() throws Exception {
        String json = "[{\"id\":2,\"maleName\":\"Squire\",\"femaleName\":\"Maid\",\"minExperience\":100,"
                + "\"salary\":20,\"coinBonus\":5,\"gemBonus\":1,\"expBonus\":3}]";

        List<TitleBracket> brackets = TitleBracketsMapper.toCore(
                mapper.readValue(json, new TypeReference<List<TitleBracketDto>>() {}));

        assertEquals(List.of(new TitleBracket(2, "Squire", "Maid", 100, 20, 5, 1, 3)), brackets);
    }

    @Test
    void userSummaryCarriesGenderBothWays() throws Exception {
        UserSummaryDto dto = mapper.readValue("{\"id\":1,\"username\":\"a\",\"gender\":\"Female\"}", UserSummaryDto.class);
        UserSummary summary = UsersMapper.mapUserSummary(dto);

        assertEquals("Female", summary.gender());
        assertEquals("Female", UsersMapper.mapUserSummary(summary).gender());
        assertNull(UsersMapper.mapUserSummary(mapper.readValue("{\"id\":1}", UserSummaryDto.class)).gender());
    }
}
