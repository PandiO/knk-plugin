package net.knightsandkings.knk.api.mapper;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.knightsandkings.knk.api.dto.UserSummaryDto;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** KNG-7: the user summary carries the resolved chat/tab-list colors over the wire. */
class UsersMapperDisplayColorsTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void userSummaryCarriesDisplayColorsBothWays() throws Exception {
        UserSummaryDto dto = mapper.readValue("{\"id\":1,\"username\":\"a\",\"premiumTierName\":\"Royal\","
                + "\"chatPrimaryColor\":\"&b\",\"chatSecondaryColor\":\"&9\",\"nameColor\":\"&b\"}", UserSummaryDto.class);
        UserSummary summary = UsersMapper.mapUserSummary(dto);

        assertEquals("&b", summary.chatPrimaryColor());
        assertEquals("&9", summary.chatSecondaryColor());
        assertEquals("&b", summary.nameColor());

        UserSummaryDto back = UsersMapper.mapUserSummary(summary);
        assertEquals("&b", back.chatPrimaryColor());
        assertEquals("&9", back.chatSecondaryColor());
        assertEquals("&b", back.nameColor());
    }

    @Test
    void olderApiWithoutColors_leavesThemNull() throws Exception {
        UserSummary summary = UsersMapper.mapUserSummary(mapper.readValue("{\"id\":1}", UserSummaryDto.class));

        assertNull(summary.chatPrimaryColor());
        assertNull(summary.chatSecondaryColor());
        assertNull(summary.nameColor());
    }

    @Test
    void withActiveModeAndWithFrozen_keepColors() {
        UserSummary summary = UsersMapper.mapUserSummary(new UserSummaryDto(1, "a", null, null, 0, 0, 0, false,
                null, null, null, null, 0, 12, "Noble", null, false, null, null, "&e", "&6", "&e"));

        UserSummary copied = summary.withActiveMode(net.knightsandkings.knk.core.domain.users.ActiveMode.STAFF)
                .withFrozen(true, "test");

        assertEquals("&e", copied.chatPrimaryColor());
        assertEquals("&6", copied.chatSecondaryColor());
        assertEquals("&e", copied.nameColor());
    }
}
