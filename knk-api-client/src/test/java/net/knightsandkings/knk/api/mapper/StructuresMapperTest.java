package net.knightsandkings.knk.api.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.dto.StructureListDtoPagedResultDto;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.structures.StructureSummary;

/**
 * P2 (PandiO/knk-workspace#91, #677): the structure search lists every structure's effective {@code townId}, so a rural
 * structure (no district, no street) reaches the territory report and the managed-region repair with its town.
 */
class StructuresMapperTest {

    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void theSearchRows_carryTheTown_ruralRowsHaveNoDistrictOrStreet() throws Exception {
        String body = """
                {"items":[
                  {"id":10,"name":"Market warehouse","description":"","wgRegionId":"domain_10","houseNumber":3,
                   "streetId":5,"streetName":"Main","districtId":2,"districtName":"Market","worldName":"world",
                   "townId":1,"townName":"Phoenix","isRural":false,"territoryFlag":null},
                  {"id":11,"name":"Quarry","description":"","wgRegionId":"domain_11","houseNumber":0,
                   "streetId":null,"streetName":null,"districtId":null,"districtName":null,"worldName":"world",
                   "townId":1,"townName":"Phoenix","isRural":true,"territoryFlag":"OutsideTerritory"}],
                 "totalCount":2,"pageNumber":1,"pageSize":50}""";

        Page<StructureSummary> page = StructuresMapper.mapPagedList(json.readValue(body, StructureListDtoPagedResultDto.class));

        StructureSummary warehouse = page.items().get(0);
        StructureSummary quarry = page.items().get(1);
        assertEquals(2, warehouse.districtId());
        assertEquals(1, warehouse.townId());
        assertNull(quarry.districtId());
        assertNull(quarry.streetId());
        assertEquals(1, quarry.townId());
        assertEquals("world", quarry.worldName());
    }

    @Test
    void anApiFromBeforeP2_withoutTownId_mapsToNull() throws Exception {
        String body = """
                {"items":[{"id":10,"name":"Old","wgRegionId":"domain_10","districtId":2}],
                 "totalCount":1,"pageNumber":1,"pageSize":50}""";

        StructureSummary old = StructuresMapper.mapPagedList(json.readValue(body, StructureListDtoPagedResultDto.class))
                .items().get(0);

        assertEquals(2, old.districtId());
        assertNull(old.townId());
    }
}
