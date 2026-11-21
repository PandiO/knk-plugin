package net.knightsandkings.knk.paper.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.landmarks.KnkLandmark;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.lootbox.KnkLootboxArea;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.navigation.model.Landmark;
import net.knightsandkings.knk.core.ports.api.DomainsQueryApi;
import net.knightsandkings.knk.core.ports.api.LandmarksApi;
import net.knightsandkings.knk.core.ports.api.LootboxesQueryApi;
import net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy.Usage;

/**
 * PandiO/knk-workspace#34: the temp-region cleanup's fresh lookup asks the lootbox spawn areas too, so an area whose
 * drawn region could not be renamed to {@code lootbox_area_<id>} keeps it; the region is deleted only when neither a
 * domain nor an area uses it. PandiO/knk-workspace#945: the landmarks are asked too, so a landmark whose drawn region
 * could not be renamed to {@code landmark_<id>} keeps it.
 */
class TempRegionRetentionLookupTest {

    private static final String TEMP = "tempregion_worldtask_41";

    private final DomainsQueryApi domains = mock(DomainsQueryApi.class);
    private final LootboxesQueryApi lootboxes = mock(LootboxesQueryApi.class);
    private final LandmarksApi landmarks = mock(LandmarksApi.class);

    TempRegionRetentionLookupTest() {
        noLandmark();
    }

    private static KnkLandmark landmark(int id, Landmark.Extent extent, String regionId) {
        return new KnkLandmark(id, "Landmark " + id, 300 + id, "world", 1, 64, 2, 0f, 0f, null, null, null, extent, regionId,
                null, 1.0, true);
    }

    private void landmarks(KnkLandmark... list) {
        when(landmarks.list(null)).thenReturn(CompletableFuture.completedFuture(List.of(list)));
    }

    private void noLandmark() {
        landmarks(landmark(9, Landmark.Extent.REGION, "landmark_9"), landmark(10, Landmark.Extent.NONE, null));
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
    void theCleanupDeletes_onlyWhenNeitherADomainNorAnAreaUsesIt() {
        noDomain();
        areas(area(1, "lootbox_spawn", true));
        Function<String, Usage> usage = TempRegionRetentionTask.regionUsageVia(domains, lootboxes, landmarks);

        assertEquals(Usage.UNUSED, usage.apply(TEMP));

        areas(area(1, TEMP, true));
        assertEquals(Usage.IN_USE, usage.apply(TEMP));
    }

    @Test
    void aDomainOnTheRegion_needsNoLootboxCall() {
        when(domains.getByWorldGuardRegionId(TEMP)).thenReturn(CompletableFuture.completedFuture(
                new net.knightsandkings.knk.core.domain.domains.DomainRegionSummary(3, "Cinix", null, TEMP, true, true, "Town",
                        List.of(), "world", List.of())));

        assertEquals(Usage.IN_USE, TempRegionRetentionTask.regionUsageVia(domains, lootboxes, landmarks).apply(TEMP));
        verify(lootboxes, never()).getRuntimeConfig();
    }

    @Test
    void anAreaLookupThatFails_keepsTheRegion() {
        noDomain();
        when(lootboxes.getRuntimeConfig()).thenReturn(CompletableFuture.failedFuture(new ApiException("down")));

        assertEquals(Usage.UNKNOWN, TempRegionRetentionTask.regionUsageVia(domains, lootboxes, landmarks).apply(TEMP));
    }

    // ===== #945: landmarks =====

    @Test
    void aLandmarkOnTheRegion_keepsIt() {
        landmarks(landmark(9, Landmark.Extent.REGION, "landmark_9"), landmark(11, Landmark.Extent.REGION, "TempRegion_WorldTask_41"));

        assertEquals(Usage.IN_USE, TempRegionRetentionTask.landmarkUsageVia(landmarks).apply(TEMP));
    }

    @Test
    void noLandmarkOnTheRegion_isUnused() {
        assertEquals(Usage.UNUSED, TempRegionRetentionTask.landmarkUsageVia(landmarks).apply(TEMP));
    }

    @Test
    void aLandmarkLookupThatFails_orNoApi_isUnknown() {
        when(landmarks.list(null)).thenReturn(CompletableFuture.failedFuture(new ApiException("down")));

        assertEquals(Usage.UNKNOWN, TempRegionRetentionTask.landmarkUsageVia(landmarks).apply(TEMP));
        assertEquals(Usage.UNKNOWN, TempRegionRetentionTask.landmarkUsageVia(null).apply(TEMP));
    }

    @Test
    void anApiWithoutLandmarks_404_hasNone() {
        when(landmarks.list(null)).thenReturn(CompletableFuture.failedFuture(
                new java.util.concurrent.CompletionException(new ApiException("http://api/landmarks", 404, "not found", ""))));

        assertEquals(Usage.UNUSED, TempRegionRetentionTask.landmarkUsageVia(landmarks).apply(TEMP));
    }

    @Test
    void theCleanupKeepsARegionALandmarkStandsOn_evenWithNoDomainOrArea() {
        noDomain();
        areas(area(1, "lootbox_spawn", true));
        Function<String, Usage> usage = TempRegionRetentionTask.regionUsageVia(domains, lootboxes, landmarks);
        assertEquals(Usage.UNUSED, usage.apply(TEMP));

        landmarks(landmark(11, Landmark.Extent.REGION, TEMP));
        assertEquals(Usage.IN_USE, usage.apply(TEMP));

        when(landmarks.list(null)).thenReturn(CompletableFuture.failedFuture(new ApiException("down")));
        assertEquals(Usage.UNKNOWN, usage.apply(TEMP));
    }

    @Test
    void anAreaOnTheRegion_needsNoLandmarkCall() {
        noDomain();
        areas(area(1, TEMP, true));

        assertEquals(Usage.IN_USE, TempRegionRetentionTask.regionUsageVia(domains, lootboxes, landmarks).apply(TEMP));
        verify(landmarks, never()).list(null);
    }
}
