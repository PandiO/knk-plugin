package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.api.dto.ClaimTaskDto;
import net.knightsandkings.knk.api.dto.WorldTaskDto;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.paper.tasks.IWorldTaskHandler;
import net.knightsandkings.knk.paper.tasks.WorldTaskHandlerRegistry;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** PandiO/knk-workspace#180: claim by task code (checked by the API), id claims need knk.tasks.any, no handler = failed. */
class KnkTaskClaimCommandFlowTest {
    private final UUID playerId = UUID.fromString("0f6c2a1e-7d0b-4c52-9a51-3c1e6f0a9b11");
    private WorldTasksApi api;
    private WorldTaskHandlerRegistry registry;
    private Player player;
    private KnkTaskClaimCommand command;

    @BeforeEach
    void setUp() {
        api = mock(WorldTasksApi.class);
        registry = new WorldTaskHandlerRegistry();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Steve");
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        command = new KnkTaskClaimCommand(plugin, api, registry, "survival-1", Runnable::run);
    }

    private static WorldTaskDto task(int id, String status, String type) {
        return new WorldTaskDto(id, 1, "Town", 0, "Location", type, status, "ABC123", null, null, null, null,
            "{}", null, null, null);
    }

    @Test
    void aClaimByIdWithoutKnkTasksAnyIsRefusedBeforeAnyCall() {
        command.onCommand(player, mock(Command.class), "knk", new String[] {"42"});

        verify(player).sendMessage(ChatColor.RED + "Claim tasks with their task code from the web app (e.g. /knk task ABC123).");
        verifyNoInteractions(api);
    }

    @Test
    void aCodeClaimSendsTheCodeThePlayerAndThisServer_AndStartsTheHandler() {
        IWorldTaskHandler handler = mock(IWorldTaskHandler.class);
        when(handler.getFieldName()).thenReturn("Location");
        registry.registerHandler("LocationSelection", handler);
        when(api.getByLinkCode("abc123")).thenReturn(CompletableFuture.completedFuture(task(7, "Pending", "LocationSelection")));
        when(api.claim(eq(7), any(ClaimTaskDto.class)))
            .thenReturn(CompletableFuture.completedFuture(task(7, "InProgress", "LocationSelection")));

        command.onCommand(player, mock(Command.class), "knk", new String[] {"abc123"});

        ArgumentCaptor<ClaimTaskDto> claim = ArgumentCaptor.forClass(ClaimTaskDto.class);
        verify(api).claim(eq(7), claim.capture());
        assertEquals("abc123", claim.getValue().taskCode());
        assertEquals(ClaimTaskDto.MODE_CODE, claim.getValue().claimMode());
        assertEquals(playerId.toString(), claim.getValue().uuid());
        assertEquals("survival-1", claim.getValue().serverId());
        verify(handler).startTask(player, 7, "{}");
    }

    @Test
    void anIdClaimWithKnkTasksAnyUsesTheIdMode() {
        when(player.hasPermission(KnkTaskClaimCommand.CLAIM_BY_ID_PERMISSION)).thenReturn(true);
        when(api.getById(42)).thenReturn(CompletableFuture.completedFuture(task(42, "Pending", "LocationSelection")));
        when(api.claim(eq(42), any(ClaimTaskDto.class))).thenReturn(new CompletableFuture<>());

        command.onCommand(player, mock(Command.class), "knk", new String[] {"42"});

        ArgumentCaptor<ClaimTaskDto> claim = ArgumentCaptor.forClass(ClaimTaskDto.class);
        verify(api).claim(eq(42), claim.capture());
        assertEquals(ClaimTaskDto.MODE_ID, claim.getValue().claimMode());
        assertNull(claim.getValue().taskCode());
    }

    @Test
    void aTaskWithoutAHandlerHereIsFailedWithTheReason() {
        when(api.getByLinkCode("ABC123")).thenReturn(CompletableFuture.completedFuture(task(8, "Pending", "VerifyLocation")));
        when(api.claim(eq(8), any(ClaimTaskDto.class)))
            .thenReturn(CompletableFuture.completedFuture(task(8, "InProgress", "VerifyLocation")));
        when(api.fail(anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        command.onCommand(player, mock(Command.class), "knk", new String[] {"ABC123"});

        verify(api).fail(eq(8), contains("no handler for task type 'VerifyLocation'"));
    }

    @Test
    void aClaimThatLostTheRaceSaysSo() {
        when(api.getByLinkCode("ABC123")).thenReturn(CompletableFuture.completedFuture(task(9, "Pending", "LocationSelection")));
        when(api.claim(eq(9), any(ClaimTaskDto.class))).thenReturn(CompletableFuture.failedFuture(new ApiException(
            "u", 409, "Request failed", "{\"code\":\"WorldTaskConflict\",\"message\":\"Task 9 is already claimed by Alex.\",\"status\":\"InProgress\"}")));

        command.onCommand(player, mock(Command.class), "knk", new String[] {"ABC123"});

        verify(player).sendMessage(ChatColor.RED + "Task 9 is already claimed by Alex.");
        verify(api, never()).fail(anyInt(), anyString());
    }

    @Test
    void aTaskThatIsNoLongerPendingIsNotClaimed() {
        when(api.getByLinkCode("ABC123")).thenReturn(CompletableFuture.completedFuture(task(10, "InProgress", "LocationSelection")));

        command.onCommand(player, mock(Command.class), "knk", new String[] {"ABC123"});

        verify(api, never()).claim(anyInt(), any(ClaimTaskDto.class));
    }
}
