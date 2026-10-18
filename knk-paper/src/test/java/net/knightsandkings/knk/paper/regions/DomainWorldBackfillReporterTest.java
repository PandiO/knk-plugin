package net.knightsandkings.knk.paper.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.ports.api.DomainWorldBackfillApi.MissingDomain;

/** KNG-112: the report names, for each region of a domain without a world, every loaded world that has it. */
class DomainWorldBackfillReporterTest {

    @Test
    void eachMissingDomainsRegionIsLookedUpInEveryLoadedWorld() {
        Map<String, Predicate<String>> worlds = new LinkedHashMap<>();
        worlds.put("hub", Set.of("town_1", "town_2")::contains);
        worlds.put("gameplay", Set.of("town_1")::contains);
        List<MissingDomain> missing = List.of(
            new MissingDomain(1, "Both", "Town", "town_1", List.of()),
            new MissingDomain(2, "Hub only", "Town", "town_2", List.of()),
            new MissingDomain(3, "Nowhere", "Town", "town_9", List.of()),
            new MissingDomain(4, "No region", "Town", " ", List.of()));

        Map<String, List<String>> found = DomainWorldBackfillReporter.worldsOf(missing, worlds);

        assertEquals(Map.of("town_1", List.of("hub", "gameplay"), "town_2", List.of("hub"), "town_9", List.of()), found);
    }
}
