package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.PermissionGroupListItemDto;
import net.knightsandkings.knk.core.domain.permissions.PermissionGroupSummary;
import net.knightsandkings.knk.core.roads.route.RouteMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
                        + "\"chatPrimaryColor\":\"&e\",\"chatSecondaryColor\":\"&6\",\"nameColor\":\"&e\"}",
                PermissionGroupListItemDto.class);

        assertEquals(new PermissionGroupSummary(4, "Noble", 10, true, 1.1, "&e", "&6", "&e"),
                PermissionGroupsQueryApiImpl.toSummary(noble));
    }

    @Test
    void mapsDefaultRouteModeAndIgnoresUnknownOrMissingValues() throws Exception {
        PermissionGroupListItemDto royal = mapper.readValue(
                "{\"id\":5,\"name\":\"Royal\",\"weight\":30,\"defaultRouteMode\":\"shortest\"}", PermissionGroupListItemDto.class);
        PermissionGroupListItemDto upper = mapper.readValue(
                "{\"id\":6,\"name\":\"Knight\",\"weight\":20,\"defaultRouteMode\":\"Efficient\"}", PermissionGroupListItemDto.class);
        PermissionGroupListItemDto none = mapper.readValue(
                "{\"id\":1,\"name\":\"Default\",\"weight\":0,\"defaultRouteMode\":null}", PermissionGroupListItemDto.class);
        PermissionGroupListItemDto old = mapper.readValue("{\"id\":2,\"name\":\"Old\",\"weight\":0}",
                PermissionGroupListItemDto.class);
        PermissionGroupListItemDto unknown = mapper.readValue(
                "{\"id\":3,\"name\":\"Future\",\"weight\":0,\"defaultRouteMode\":\"scenic\"}", PermissionGroupListItemDto.class);

        assertEquals(RouteMode.SHORTEST, PermissionGroupsQueryApiImpl.toSummary(royal).defaultRouteMode());
        assertEquals(RouteMode.EFFICIENT, PermissionGroupsQueryApiImpl.toSummary(upper).defaultRouteMode());
        assertNull(PermissionGroupsQueryApiImpl.toSummary(none).defaultRouteMode());
        assertNull(PermissionGroupsQueryApiImpl.toSummary(old).defaultRouteMode());
        assertNull(PermissionGroupsQueryApiImpl.toSummary(unknown).defaultRouteMode(), "unknown word: no default, no crash");
        assertNull(PermissionGroupsQueryApiImpl.toSummary(unknown).defaultRouteMode(), "logged once, still null");
    }
}
