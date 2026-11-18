package net.knightsandkings.knk.paper.tasks;

import net.knightsandkings.knk.api.dto.ClaimTaskDto;
import net.knightsandkings.knk.api.dto.WorldTaskDto;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PandiO/knk-workspace#180: the headless poller claims a scan before it runs it; a taken scan is skipped. */
class HeadlessWorldTaskPollerClaimTest {
    private static WorldTaskDto scan(int id, String status) {
        return new WorldTaskDto(id, 1, null, 0, "BlockSnapshots", "GateBlockScan", status, null, null, null, null, null,
            "{\"gateDoorId\":3}", null, null, null);
    }

    @Test
    void aScanIsClaimedFirstAndOnlyRunWhenTheClaimSucceeds() {
        WorldTasksApi api = mock(WorldTasksApi.class);
        IHeadlessWorldTaskHandler handler = mock(IHeadlessWorldTaskHandler.class);
        when(handler.supports("GateBlockScan")).thenReturn(true);
        when(api.listByStatus("Pending")).thenReturn(CompletableFuture.completedFuture(List.of(scan(1, "Pending"), scan(2, "Pending"))));
        when(api.claim(eq(1), any(ClaimTaskDto.class))).thenReturn(CompletableFuture.completedFuture(scan(1, "InProgress")));
        when(api.claim(eq(2), any(ClaimTaskDto.class))).thenReturn(CompletableFuture.failedFuture(
            new ApiException("u", 409, "Request failed", "{\"status\":\"InProgress\"}")));
        HeadlessWorldTaskPoller poller = new HeadlessWorldTaskPoller(api, mock(Plugin.class), 5, 60, "survival-1");
        poller.registerHandler(handler);

        poller.pollOnce();

        verify(api).claim(eq(1), eq(ClaimTaskDto.headless("survival-1")));
        verify(handler).execute(eq(scan(1, "InProgress")), any());
        verify(handler, never()).execute(eq(scan(2, "Pending")), any());
        verify(handler, never()).execute(eq(scan(2, "InProgress")), any());
    }

    @Test
    void whileTheHubIsConnectedThePollIsOnlyTheSixtySecondSafetyNet() {
        // PandiO/knk-workspace#181: WorldTaskCreated starts scans; the poll stays at the idle interval.
        HeadlessWorldTaskPoller poller = new HeadlessWorldTaskPoller(mock(WorldTasksApi.class), mock(Plugin.class), 5, 60, "s1");

        org.junit.jupiter.api.Assertions.assertEquals(60L * 20L, poller.nextInterval(true, true));
        org.junit.jupiter.api.Assertions.assertEquals(5L * 20L, poller.nextInterval(true, false));
        org.junit.jupiter.api.Assertions.assertEquals(10L * 20L, poller.nextInterval(false, false));
    }
}
