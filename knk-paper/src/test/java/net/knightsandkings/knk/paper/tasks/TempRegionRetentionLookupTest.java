package net.knightsandkings.knk.paper.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.lootbox.KnkLootboxArea;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.ports.api.DomainsQueryApi;
import net.knightsandkings.knk.core.ports.api.LootboxesQueryApi;
import net.knightsandkings.knk.core.ports.api.ProductionSurveyApi;
import net.knightsandkings.knk.core.production.survey.AreaSpec;
import net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy.Usage;

/**
 * PandiO/knk-workspace#34: the temp-region cleanup's fresh lookup asks the lootbox spawn areas too, so an area whose
 * drawn region could not be renamed to {@code lootbox_area_<id>} keeps it; the region is deleted only when neither a
 * domain nor an area uses it. PandiO/knk-workspace#946: the production areas are asked too, so one whose drawn region
 * could not be renamed to {@code production_area_<id>} keeps it. Since PandiO/knk-workspace#985 (LD14) a landmark is a
 * domain: the domain lookup finds its regions and {@code api/landmarks} is no longer asked (the #945 owner is gone).
 * Each owner list is read at most once a minute, not once per region.
 */
class TempRegionRetentionLookupTest {

    private static final String TEMP = "tempregion_worldtask_41";

    private final DomainsQueryApi domains = mock(DomainsQueryApi.class);
    private final LootboxesQueryApi lootboxes = mock(LootboxesQueryApi.class);
    private final ProductionSurveyApi production = mock(ProductionSurveyApi.class);

    TempRegionRetentionLookupTest() {
        productionAreas(productionArea(3, "Region", "production_area_3"), productionArea(4, "Radius", null));
    }

    private static AreaSpec productionArea(int id, String shape, String regionId) {
        return new AreaSpec(id, 7, "Farm", "Field " + id, shape, true, true, "world", regionId, List.of(), null, null, null,
                null, 0, 60, 0, 9, 62, 9);
    }

    private void productionAreas(AreaSpec... list) {
        when(production.areas()).thenReturn(CompletableFuture.completedFuture(List.of(list)));
    }

    private static KnkLootboxArea area(int id, String regionId, boolean enabled) {
        return new KnkLootboxArea(id, "a" + id, "world", regionId, enabled, 3, 600, 100, 3, 24, 30, List.of(), List.of(), 0, null);
    }

    private void areas(KnkLootboxArea... areas) {
        when(lootboxes.getRuntimeConfig()).thenReturn(CompletableFuture.completedFuture(
                new KnkLootboxRuntimeConfig(true, 15, 10, 5, 6, null, null, null, List.of(), List.of(), List.of(areas))));
    }

    private void noDomain() {
        when(domains.getByWorldGuardRegionId(anyString())).thenReturn(CompletableFuture.failedFuture(
                new ApiException("http://api/Domains/by-region/x", 404, "not found", "")));
    }

    private Function<String, Usage> regionUsage() {
        return TempRegionRetentionTask.regionUsageVia(domains, lootboxes, production);
    }

    // ===== #34: lootbox areas =====

    @Test
    void anAreaOnTheRegion_disabledOrNot_keepsIt() {
        areas(area(1, "lootbox_spawn", true), area(2, "TEMPREGION_WORLDTASK_41", false));

        assertEquals(Usage.IN_USE, TempRegionRetentionTask.lootboxAreaUsageVia(lootboxes).apply(TEMP));
    }

    @Test
    void noAreaOnTheRegion_isUnused() {
        areas(area(1, "lootbox_spawn", true));

        assertEquals(Usage.UNUSED, TempRegionRetentionTask.lootboxAreaUsageVia(lootboxes).apply(TEMP));
    }

    @Test
    void anUnreachableApi_orNoApi_isUnknown() {
        when(lootboxes.getRuntimeConfig()).thenReturn(CompletableFuture.failedFuture(new ApiException("down")));

        assertEquals(Usage.UNKNOWN, TempRegionRetentionTask.lootboxAreaUsageVia(lootboxes).apply(TEMP));
        assertEquals(Usage.UNKNOWN, TempRegionRetentionTask.lootboxAreaUsageVia(null).apply(TEMP));
    }

    @Test
    void aLootboxConfigWithoutAreas_orA404_isUnknown() {
        when(lootboxes.getRuntimeConfig()).thenReturn(CompletableFuture.completedFuture(null));
        assertEquals(Usage.UNKNOWN, TempRegionRetentionTask.lootboxAreaUsageVia(lootboxes).apply(TEMP));

        // unchanged from #34: the lootbox list exists on every supported API, so a 404 is not "none"
        when(lootboxes.getRuntimeConfig()).thenReturn(CompletableFuture.failedFuture(
                new ApiException("http://api/LootboxSpawns/runtime-config", 404, "not found", "")));
        assertEquals(Usage.UNKNOWN, TempRegionRetentionTask.lootboxAreaUsageVia(lootboxes).apply(TEMP));
    }

    @Test
    void theCleanupDeletes_onlyWhenNeitherADomainNorAnAreaUsesIt() {
        noDomain();
        areas(area(1, "lootbox_spawn", true));
        assertEquals(Usage.UNUSED, regionUsage().apply(TEMP));

        areas(area(1, TEMP, true));
        assertEquals(Usage.IN_USE, regionUsage().apply(TEMP));
    }

    @Test
    void aDomainOnTheRegion_needsNoOtherCall() {
        when(domains.getByWorldGuardRegionId(TEMP)).thenReturn(CompletableFuture.completedFuture(
                new net.knightsandkings.knk.core.domain.domains.DomainRegionSummary(3, "Cinix", null, TEMP, true, true, "Town",
                        List.of(), "world", List.of())));

        assertEquals(Usage.IN_USE, regionUsage().apply(TEMP));
        verify(lootboxes, never()).getRuntimeConfig();
        verify(production, never()).areas();
    }

    @Test
    void anAreaLookupThatFails_keepsTheRegion() {
        noDomain();
        when(lootboxes.getRuntimeConfig()).thenReturn(CompletableFuture.failedFuture(new ApiException("down")));

        assertEquals(Usage.UNKNOWN, regionUsage().apply(TEMP));
    }

    // ===== #985: a landmark is a domain (LD14) =====

    @Test
    void aLandmarksRegion_IsKeptByTheDomainLookup() {
        when(domains.getByWorldGuardRegionId(TEMP)).thenReturn(CompletableFuture.completedFuture(
                new net.knightsandkings.knk.core.domain.domains.DomainRegionSummary(11, "Tower", null, TEMP, true, true,
                        "Landmark", List.of(), "world", List.of())));

        assertEquals(Usage.IN_USE, regionUsage().apply(TEMP));
        verify(lootboxes, never()).getRuntimeConfig();
        verify(production, never()).areas();
    }

    // ===== #946: production areas =====

    @Test
    void aProductionAreaOnTheRegion_keepsIt() {
        productionAreas(productionArea(3, "Region", "production_area_3"), productionArea(5, "Region", "TEMPREGION_WORLDTASK_41"));

        assertEquals(Usage.IN_USE, TempRegionRetentionTask.productionAreaUsageVia(production).apply(TEMP));
    }

    @Test
    void noProductionAreaOnTheRegion_isUnused() {
        assertEquals(Usage.UNUSED, TempRegionRetentionTask.productionAreaUsageVia(production).apply(TEMP));
    }

    @Test
    void aProductionAreaLookupThatFails_isUnknown_A404IsNone() {
        when(production.areas()).thenReturn(CompletableFuture.failedFuture(new RuntimeException("Failed to read the production areas")));
        assertEquals(Usage.UNKNOWN, TempRegionRetentionTask.productionAreaUsageVia(production).apply(TEMP));
        assertEquals(Usage.UNKNOWN, TempRegionRetentionTask.productionAreaUsageVia(null).apply(TEMP));

        when(production.areas()).thenReturn(CompletableFuture.failedFuture(
                new java.util.concurrent.CompletionException(new ApiException("http://api/ProductionAreas", 404, "not found", ""))));
        assertEquals(Usage.UNUSED, TempRegionRetentionTask.productionAreaUsageVia(production).apply(TEMP));
    }

    @Test
    void theCleanupKeepsARegionAProductionAreaStandsOn_evenWithNoOtherOwner() {
        noDomain();
        areas(area(1, "lootbox_spawn", true));
        productionAreas(productionArea(5, "Region", TEMP));

        assertEquals(Usage.IN_USE, regionUsage().apply(TEMP));
    }

    // ===== reuse of the owner lists =====

    @Test
    void anOwnerListIsReadOncePerMinute_notOncePerRegion() {
        AtomicLong now = new AtomicLong(1_000_000);
        Function<String, Usage> usage = TempRegionRetentionTask.ownerUsageVia("production area", production::areas,
                AreaSpec::wgRegionId, true, now::get);

        assertEquals(Usage.IN_USE, usage.apply("production_area_3"));
        assertEquals(Usage.UNUSED, usage.apply(TEMP));
        assertEquals(Usage.UNUSED, usage.apply("tempregion_worldtask_42"));
        verify(production, times(1)).areas();

        productionAreas(productionArea(5, "Region", TEMP));
        now.addAndGet(TempRegionRetentionTask.OWNER_LIST_REUSE_MILLIS);
        assertEquals(Usage.IN_USE, usage.apply(TEMP));
        verify(production, times(2)).areas();
    }

    @Test
    void aFailedReadIsNotReused() {
        AtomicLong now = new AtomicLong(1_000_000);
        when(production.areas()).thenReturn(CompletableFuture.failedFuture(new ApiException("down")));
        Function<String, Usage> usage = TempRegionRetentionTask.ownerUsageVia("production area", production::areas,
                AreaSpec::wgRegionId, true, now::get);
        assertEquals(Usage.UNKNOWN, usage.apply(TEMP));

        productionAreas(productionArea(5, "Region", TEMP));
        assertEquals(Usage.IN_USE, usage.apply(TEMP));
    }
}
