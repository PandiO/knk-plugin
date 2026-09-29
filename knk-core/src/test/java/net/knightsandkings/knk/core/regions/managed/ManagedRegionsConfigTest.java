package net.knightsandkings.knk.core.regions.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ManagedRegionsConfigTest {

    @Test
    void anEmptyConfigMeansRepairOnWithSafeDefaults() {
        ManagedRegionsConfig config = ManagedRegionsConfig.defaults();
        assertTrue(config.repairEnabled());
        assertEquals(3, config.attempts());
        assertFalse(config.options().resourceProductionBlockBreak());
        assertFalse(config.options().manageGlobalRegion());
        assertTrue(config.overrides().isEmpty());
        assertTrue(config.warnings().isEmpty());
    }

    @Test
    void overridesAndExtraRegionsAreParsed() {
        ManagedRegionsConfig config = ManagedRegionsConfig.fromMap(Map.of(
                "startup-repair", Map.of("enabled", false, "attempts", 5, "page-size", 50),
                "overrides", Map.of("domain_47", Map.of("kind", "resource-production", "priority", 35,
                        "parent", "district_2", "flags", Map.of("block-break", "allow", "feed-amount", 20))),
                "extra-regions", List.of(Map.of("region", "arena_2", "kind", "arena", "parent", "district_2"))));

        assertFalse(config.repairEnabled());
        assertEquals(5, config.attempts());
        assertEquals(50, config.pageSize());
        RegionOverride override = config.overrides().get("domain_47");
        assertEquals(ManagedRegionKind.RESOURCE_PRODUCTION, override.kind());
        assertEquals(35, override.priority());
        assertEquals("district_2", override.parentRegionId());
        assertTrue(override.extraFlags().contains(FlagRule.seed("block-break", FlagState.ALLOW)));
        assertTrue(override.extraFlags().contains(FlagRule.seed("feed-amount", 20)));
        assertEquals(1, config.extraRegions().size());
        assertEquals(ManagedRegionKind.ARENA, config.extraRegions().get(0).kind());
    }

    @Test
    void badEntriesAreReportedAndSkippedNotFatal() {
        ManagedRegionsConfig config = ManagedRegionsConfig.fromMap(Map.of(
                "overrides", Map.of("domain_1", Map.of("kind", "castle")),
                "extra-regions", List.of(Map.of("region", "arena_9"), "nonsense")));

        assertNull(config.overrides().get("domain_1").kind());
        assertTrue(config.extraRegions().isEmpty());
        assertEquals(3, config.warnings().size());
    }
}
