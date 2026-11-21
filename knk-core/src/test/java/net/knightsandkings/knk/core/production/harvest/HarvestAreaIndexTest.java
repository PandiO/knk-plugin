package net.knightsandkings.knk.core.production.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.production.survey.AreaSpec;

/** PandiO/knk-workspace#134: the catalogue's areas by chunk; areas without a volume or too large are left out. */
class HarvestAreaIndexTest {

    @Test
    void areasAreFoundByPositionAndOddOnesAreLeftOut() {
        HarvestArea pit = HarvestFixtures.cuboid(5, 4, HarvestFixtures.QUARRY, true, 1);
        AreaSpec forestSpec = new AreaSpec(6, 2, "Logger", "Forest", "Radius", false, true, "world", null, List.of(),
                100, 64, 100, 20, null, null, null, null, null, null);
        HarvestArea forest = new HarvestArea(forestSpec, HarvestFixtures.LOGGER, true, 1);
        AreaSpec regionSpec = new AreaSpec(7, 3, "Farm", "Fields", "Region", true, true, "world", "domain_9", List.of(),
                null, null, null, null, null, null, null, null, null, null);
        HarvestArea region = new HarvestArea(regionSpec, HarvestFixtures.FARM, true, 1);
        AreaSpec hugeSpec = new AreaSpec(8, 3, "Farm", "Huge", "Cuboid", true, true, "world", null, List.of(),
                null, null, null, null, -5000, 0, -5000, 5000, 100, 5000);
        HarvestArea huge = new HarvestArea(hugeSpec, HarvestFixtures.FARM, true, 1);
        HarvestAreaIndex index = HarvestAreaIndex.build(List.of(forest, pit, region, huge),
                area -> area.spec().resolve(AreaSpec.RegionShapes.NONE, -64, 320), 256);
        assertEquals(2, index.entries().size());
        assertEquals(2, index.skipped().size());
        assertTrue(index.skipped().get(0).contains("domain_9"));
        assertTrue(index.skipped().get(1).contains("chunks"));
        assertEquals(5, index.at("world", 9, 69, 9).get(0).area().areaId());
        assertTrue(index.at("world", 10, 64, 9).isEmpty());
        assertEquals(6, index.at("world", 115, 70, 100).get(0).area().areaId());
        assertTrue(index.at("world", 121, 70, 100).isEmpty());
        assertTrue(index.at("other", 9, 64, 9).isEmpty());
    }
}
