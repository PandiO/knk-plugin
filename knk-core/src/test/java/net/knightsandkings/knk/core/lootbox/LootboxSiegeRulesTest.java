package net.knightsandkings.knk.core.lootbox;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeDistrict;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lootboxes x siege: which regions count as a siege arena, and whether a spawn spot is in one. */
class LootboxSiegeRulesTest {

    private static KnkSiegeScenario scenario(String townRegion, KnkSiegeDistrict... districts) {
        return new KnkSiegeScenario(1, "Siege of Cinix", null, 1, "Cinix", townRegion, List.of(districts), null,
                2, 20, null, null, null, null, true, false, true, List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void aScenarioWithDistricts_isItsDistrictRegions_notTheWholeTown() {
        Set<String> ids = LootboxSiegeRules.arenaRegionIds(List.of(scenario("domain_1",
                new KnkSiegeDistrict(10, "Old Town", "Domain_10"), new KnkSiegeDistrict(11, "Harbour", "domain_11"))));

        assertEquals(Set.of("domain_10", "domain_11"), ids);
    }

    @Test
    void aScenarioWithoutDistricts_isItsTownRegion() {
        assertEquals(Set.of("domain_1"), LootboxSiegeRules.arenaRegionIds(List.of(scenario("domain_1"))));
    }

    @Test
    void districtsWithoutARegion_fallBackToTheTown() {
        assertEquals(Set.of("domain_1"), LootboxSiegeRules.arenaRegionIds(List.of(scenario("domain_1",
                new KnkSiegeDistrict(10, "Old Town", null), new KnkSiegeDistrict(11, "Harbour", " ")))));
    }

    @Test
    void severalSieges_areCombined_andMissingDataIsSkipped() {
        Set<String> ids = LootboxSiegeRules.arenaRegionIds(Arrays.asList(
                scenario("domain_1"), null, scenario(null), scenario("domain_2", new KnkSiegeDistrict(20, "Keep", "domain_20"))));

        assertEquals(Set.of("domain_1", "domain_20"), ids);
        assertTrue(LootboxSiegeRules.arenaRegionIds(null).isEmpty());
    }

    @Test
    void insideAny_matchesRegionIdsIgnoringCase() {
        Set<String> arena = Set.of("domain_10");

        assertTrue(LootboxSiegeRules.insideAny(List.of("lootbox_spawn", "Domain_10"), arena));
        assertFalse(LootboxSiegeRules.insideAny(List.of("lootbox_spawn", "domain_1"), arena));
        assertFalse(LootboxSiegeRules.insideAny(List.of("domain_10"), Set.of()));
        assertFalse(LootboxSiegeRules.insideAny(null, arena));
    }
}
