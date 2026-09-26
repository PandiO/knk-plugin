package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.PermissionGroupListItemDto;
import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Content port CP5: the group list carries PermissionGroup.SalaryMultiplier. */
class PermissionGroupsQueryApiImplTest {

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void mapsSalaryMultiplierAndDefaultsItToOne() throws Exception {
        PermissionGroupListItemDto royal = mapper.readValue(
                "{\"id\":5,\"name\":\"Royal\",\"weight\":30,\"isPremiumTier\":true,\"salaryMultiplier\":1.2,\"chatPrefix\":null}",
                PermissionGroupListItemDto.class);
        PermissionGroupListItemDto old = mapper.readValue("{\"id\":1,\"name\":\"Default\",\"weight\":0}",
                PermissionGroupListItemDto.class);

        assertEquals(new PermissionGroupSummary(5, "Royal", 30, true, 1.2), PermissionGroupsQueryApiImpl.toSummary(royal));
        assertEquals(1.0, PermissionGroupsQueryApiImpl.toSummary(old).salaryMultiplier());
    }

    @Test
    void mapsDisplayColors() throws Exception {
        PermissionGroupListItemDto noble = mapper.readValue(
                "{\"id\":4,\"name\":\"Noble\",\"weight\":10,\"isPremiumTier\":true,\"salaryMultiplier\":1.1,"
                        + "\"chatPrimaryColor\":\"YELLOW\",\"chatSecondaryColor\":\"GOLD\",\"nameColor\":\"YELLOW\"}",
                PermissionGroupListItemDto.class);

        assertEquals(new PermissionGroupSummary(4, "Noble", 10, true, 1.1, "YELLOW", "GOLD", "YELLOW"),
                PermissionGroupsQueryApiImpl.toSummary(noble));
    }
}
