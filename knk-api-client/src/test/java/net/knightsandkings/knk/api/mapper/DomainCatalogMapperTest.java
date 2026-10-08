package net.knightsandkings.knk.api.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.dto.DomainSummaryDto;
import net.knightsandkings.knk.core.domain.domains.KnkDomainSummary;
import org.junit.jupiter.api.Test;

/** POST api/Domains/search items: id, name, subtype and the /navigate default (KNG-73). */
class DomainCatalogMapperTest {

    private final ObjectMapper json = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void theNavigationDefaultIsCarriedThrough() throws Exception {
        DomainSummaryDto dto = json.readValue(
            "{\"id\":11,\"name\":\"Keep Gate\",\"domainType\":\"GateStructure\",\"navigationDefault\":\"Region\",\"wgRegionId\":\"g\"}",
            DomainSummaryDto.class);

        KnkDomainSummary summary = DomainCatalogMapper.toCore(dto);

        assertEquals(new KnkDomainSummary(11, "Keep Gate", "GateStructure", "Region"), summary);
    }

    @Test
    void anApiWithoutTheFieldLeavesItNull() throws Exception {
        DomainSummaryDto dto = json.readValue("{\"id\":1,\"name\":\"Kardenna\",\"domainType\":\"Town\"}", DomainSummaryDto.class);

        assertNull(DomainCatalogMapper.toCore(dto).navigationDefault());
    }
}
