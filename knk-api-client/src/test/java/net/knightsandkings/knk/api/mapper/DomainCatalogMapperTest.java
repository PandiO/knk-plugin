package net.knightsandkings.knk.api.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.dto.DomainSummaryDto;
import net.knightsandkings.knk.core.domain.domains.KnkDomainSummary;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import org.junit.jupiter.api.Test;

/** POST api/Domains/search items: id, name, subtype, the /navigate default (KNG-73) and the road access (rev. 7 Part C). */
class DomainCatalogMapperTest {

    private final ObjectMapper json = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void theNavigationDefaultIsCarriedThrough() throws Exception {
        DomainSummaryDto dto = json.readValue(
            "{\"id\":11,\"name\":\"Keep Gate\",\"domainType\":\"GateStructure\",\"navigationDefault\":\"Region\",\"wgRegionId\":\"g\"}",
            DomainSummaryDto.class);

        KnkDomainSummary summary = DomainCatalogMapper.toCore(dto);

        assertEquals(new KnkDomainSummary(11, "Keep Gate", "GateStructure", "Region", null, "g"), summary);
    }

    @Test
    void anApiWithoutTheFieldLeavesItNull() throws Exception {
        DomainSummaryDto dto = json.readValue("{\"id\":1,\"name\":\"Kardenna\",\"domainType\":\"Town\"}", DomainSummaryDto.class);

        assertNull(DomainCatalogMapper.toCore(dto).navigationDefault());
        assertNull(DomainCatalogMapper.toCore(dto).roadAccess());
        assertFalse(DomainCatalogMapper.toCore(dto).roadAccessIgnored(), "unknown keeps the rule on the roads");
    }

    @Test
    void theRoadAccessIsCarriedThrough() throws Exception {
        DomainSummaryDto dto = json.readValue(
            "{\"id\":9,\"name\":\"Mill House\",\"domainType\":\"Structure\",\"navigationDefault\":\"Spawn\",\"roadAccess\":\"Ignored\",\"wgRegionId\":\"mill_house\"}",
            DomainSummaryDto.class);

        KnkDomainSummary summary = DomainCatalogMapper.toCore(dto);

        assertEquals(new KnkDomainSummary(9, "Mill House", "Structure", "Spawn", "Ignored", "mill_house"), summary);
        assertTrue(summary.roadAccessIgnored());
    }

    // ==================== destination engine E12 (PandiO/knk-workspace#103): the domain's Location ====================

    @Test
    void theLocationAndWorldAreCarriedThrough() throws Exception {
        DomainSummaryDto dto = json.readValue(
            "{\"id\":1,\"name\":\"Kardenna\",\"domainType\":\"Town\",\"worldName\":\"world\",\"location\":"
                + "{\"id\":7,\"name\":\"Kardenna spawn\",\"x\":10.5,\"y\":64,\"z\":-20.25,\"yaw\":90,\"pitch\":5.5,\"world\":\"world\"}}",
            DomainSummaryDto.class);

        KnkDomainSummary summary = DomainCatalogMapper.toCore(dto);

        assertEquals("world", summary.worldName());
        assertTrue(summary.locationSent());
        assertEquals(new KnkLocation(7, "Kardenna spawn", 10.5, 64.0, -20.25, 90f, 5.5f, "world"), summary.location());
    }

    @Test
    void anExplicitNullMeansTheDomainHasNoLocation() throws Exception {
        DomainSummaryDto dto = json.readValue(
            "{\"id\":3,\"name\":\"Market\",\"domainType\":\"District\",\"location\":null}", DomainSummaryDto.class);

        KnkDomainSummary summary = DomainCatalogMapper.toCore(dto);

        assertTrue(summary.locationSent(), "the API said: no Location");
        assertNull(summary.location());
    }

    @Test
    void anOlderApiWithoutTheFieldLeavesTheLocationUnknown() throws Exception {
        DomainSummaryDto dto = json.readValue("{\"id\":3,\"name\":\"Market\",\"domainType\":\"District\"}", DomainSummaryDto.class);

        KnkDomainSummary summary = DomainCatalogMapper.toCore(dto);

        assertFalse(summary.locationSent(), "no key: the API predates the field");
        assertNull(summary.location());
        assertNull(summary.worldName());
    }

    @Test
    void numbersSentAsTextAreRead() throws Exception {
        DomainSummaryDto dto = json.readValue(
            "{\"id\":3,\"name\":\"Market\",\"domainType\":\"District\",\"location\":"
                + "{\"id\":\"8\",\"x\":\"1.5\",\"y\":2,\"z\":3,\"yaw\":null,\"world\":\"world\"}}",
            DomainSummaryDto.class);

        assertEquals(new KnkLocation(8, null, 1.5, 2.0, 3.0, null, null, "world"), DomainCatalogMapper.toCore(dto).location());
    }
}
