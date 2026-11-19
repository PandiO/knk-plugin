package net.knightsandkings.knk.paper.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.domains.DomainAccessRule;
import net.knightsandkings.knk.core.map.RegionRef;

/** Overview map F1 (PandiO/knk-workspace#750): the sub-regions an access sync's rules list, as regions to report. */
class MapReportingSubRegionsTest {

    @Test
    void everyListedSubRegion_inItsDomainsWorld() {
        List<DomainAccessRule> rules = List.of(
            new DomainAccessRule(1, "Town", "domain_1", true, true, "Town", "world",
                List.of("domain_1_sub_1", "domain_1_sub_2")),
            new DomainAccessRule(2, "No subs", "domain_2", true, true, "District", "world"),
            new DomainAccessRule(3, "Old", "domain_3", true, true, "Structure", null, List.of("domain_3_sub_7")));

        assertEquals(List.of(new RegionRef("world", "domain_1_sub_1"), new RegionRef("world", "domain_1_sub_2"),
            new RegionRef(null, "domain_3_sub_7")), MapReporting.subRegions(rules));
    }

    @Test
    void theMainRegionListedAsASubRegion_isLeftOut() {
        List<DomainAccessRule> rules = List.of(new DomainAccessRule(1, "Town", "domain_1", true, true, "Town", "world",
            List.of("DOMAIN_1", "domain_1_sub_1")));

        assertEquals(List.of(new RegionRef("world", "domain_1_sub_1")), MapReporting.subRegions(rules));
    }

    @Test
    void noRules_noRegions() {
        assertTrue(MapReporting.subRegions(null).isEmpty());
        assertTrue(MapReporting.subRegions(List.of()).isEmpty());
    }
}
