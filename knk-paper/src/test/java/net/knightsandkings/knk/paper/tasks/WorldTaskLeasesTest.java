package net.knightsandkings.knk.paper.tasks;

import net.knightsandkings.knk.api.dto.ClaimTaskDto;
import net.knightsandkings.knk.api.dto.WorldTaskDto;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PandiO/knk-workspace#180: the claims this server holds, heartbeats, hand-backs and the lease-tracking API wrapper. */
class WorldTaskLeasesTest {
    private final UUID steve = UUID.randomUUID();
    private WorldTasksApi api;
    private WorldTaskLeases leases;
    private final List<String> lost = new ArrayList<>();

    @BeforeEach
    void setUp() {
        api = mock(WorldTasksApi.class);
        leases = new WorldTaskLeases(api, "survival-1");
        leases.setOnLost((held, status) -> lost.add(held.taskId() + ":" + status));
        when(api.release(anyInt(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(api.cancel(anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private static WorldTaskDto task(int id, String status) {
        return new WorldTaskDto(id, 1, null, 0, "Location", "LocationSelection", status, "ABC123", "survival-1", "Steve",
            null, null, "{}", null, null, null);
    }

    private static ApiException conflict(String status) {
        return new ApiException("u", 409, "Request failed", "{\"code\":\"WorldTaskConflict\",\"status\":\"" + status + "\"}");
    }

    @Test
    void aHeartbeatRefusedWith409LosesTheClaimAndNamesTheStatus() {
        leases.hold(1, steve, "LocationSelection");
        leases.hold(2, steve, "WgRegionId");
        when(api.heartbeat(1, "survival-1")).thenReturn(CompletableFuture.completedFuture(task(1, "InProgress")));
        when(api.heartbeat(2, "survival-1")).thenReturn(CompletableFuture.failedFuture(conflict("Cancelled")));

        leases.heartbeatAll().join();

        assertTrue(leases.holds(1));
        assertFalse(leases.holds(2));
        assertEquals(List.of("2:Cancelled"), lost);
    }

    @Test
    void aHeartbeatThatGetsNoAnswerKeepsTheClaim() {
        leases.hold(3, steve, "LocationSelection");
        when(api.heartbeat(3, "survival-1")).thenReturn(CompletableFuture.failedFuture(new RuntimeException("connect timed out")));

        leases.heartbeatAll().join();

        assertTrue(leases.holds(3));
        assertTrue(lost.isEmpty());
    }

    @Test
    void cancelInGameReleasesAndDiscardCancels() {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Steve");
        leases.hold(4, steve, "LocationSelection");
        leases.hold(5, steve, "LocationSelection");

        leases.handedBack(player, 4);
        leases.discarded(player, 5);

        verify(api).release(eq(4), eq("survival-1"), anyString());
        verify(api).cancel(eq(5), anyString());
        assertTrue(leases.held().isEmpty());
    }

    @Test
    void quittingReleasesOnlyThatPlayersTasks_AndDisableReleasesTheRest() {
        UUID alex = UUID.randomUUID();
        leases.hold(6, steve, "LocationSelection");
        leases.hold(7, alex, "LocationSelection");
        leases.hold(8, null, "GateBlockScan");

        leases.releaseHeldBy(steve, "left").join();
        verify(api).release(eq(6), eq("survival-1"), eq("left"));
        verify(api, never()).release(eq(7), anyString(), anyString());

        leases.releaseAll("stopped").join();
        verify(api).release(eq(7), eq("survival-1"), eq("stopped"));
        verify(api).release(eq(8), eq("survival-1"), eq("stopped"));
        assertTrue(leases.held().isEmpty());
    }

    @Test
    void afterARestartTasksTheApiStillHasThisServerHoldingAreHandedBack() {
        leases.hold(10, steve, "LocationSelection");
        when(api.listOpenForServer("survival-1"))
            .thenReturn(CompletableFuture.completedFuture(List.of(task(9, "InProgress"), task(10, "InProgress"))));

        int released = leases.releaseOrphans().join();

        assertEquals(1, released);
        verify(api).release(eq(9), eq("survival-1"), anyString());
        verify(api, never()).release(eq(10), anyString(), anyString());
    }

    @Test
    void theTrackingApiHoldsAClaimUntilItIsCompleted() {
        LeaseTrackingWorldTasksApi tracking = new LeaseTrackingWorldTasksApi(api, leases);
        when(api.claim(eq(11), any(ClaimTaskDto.class))).thenReturn(CompletableFuture.completedFuture(task(11, "InProgress")));
        when(api.complete(11, "{}", "survival-1")).thenReturn(CompletableFuture.completedFuture(task(11, "Completed")));

        tracking.claim(11, ClaimTaskDto.byCode("ABC123", null, "Steve", steve.toString())).join();
        assertEquals(steve, leases.held().get(0).playerId());
        verify(api).claim(eq(11), eq(ClaimTaskDto.byCode("ABC123", "survival-1", "Steve", steve.toString())));

        tracking.complete(11, "{}").join();
        assertFalse(leases.holds(11));
    }

    @Test
    void theTrackingApiHandsBackAHeadlessScanWhoseResultCouldNotBeSent_ButNotAPlayersTask() {
        LeaseTrackingWorldTasksApi tracking = new LeaseTrackingWorldTasksApi(api, leases);
        leases.hold(12, null, "GateBlockScan");
        leases.hold(13, steve, "LocationSelection");
        when(api.complete(anyInt(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));

        tracking.complete(12, "{}").exceptionally(e -> null).join();
        tracking.complete(13, "{}").exceptionally(e -> null).join();

        verify(api).release(eq(12), eq("survival-1"), anyString());
        verify(api, never()).release(eq(13), anyString(), anyString());
        assertTrue(leases.holds(13));
    }

    @Test
    void theTrackingApiForgetsAClaimTheApiRefusedForGood() {
        LeaseTrackingWorldTasksApi tracking = new LeaseTrackingWorldTasksApi(api, leases);
        leases.hold(14, steve, "LocationSelection");
        when(api.complete(anyInt(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(conflict("Cancelled")));

        tracking.complete(14, "{}").exceptionally(e -> null).join();

        assertFalse(leases.holds(14));
    }

    @Test
    void apiErrorsAreReadThroughWrappers() {
        RuntimeException wrapped = new RuntimeException(new java.util.concurrent.CompletionException(conflict("Expired")));
        assertEquals(409, WorldTaskApiErrors.status(wrapped));
        assertEquals("Expired", WorldTaskApiErrors.currentStatus(wrapped).orElseThrow());
        assertTrue(WorldTaskApiErrors.taskMovedOn(wrapped));
        assertEquals(-1, WorldTaskApiErrors.status(new RuntimeException("x")));
    }
}
