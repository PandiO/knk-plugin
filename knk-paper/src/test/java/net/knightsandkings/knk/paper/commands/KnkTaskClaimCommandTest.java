package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.paper.tasks.IWorldTaskHandler;
import net.knightsandkings.knk.paper.tasks.WorldTaskHandlerRegistry;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** KNG-174: one open task at a time, and a readable task header. */
class KnkTaskClaimCommandTest {
    @Test
    void aPlayerWithAnOpenTaskIsRefusedBeforeAnythingIsFetchedOrClaimed() {
        WorldTasksApi worldTasksApi = mock(WorldTasksApi.class);
        WorldTaskHandlerRegistry registry = new WorldTaskHandlerRegistry();
        IWorldTaskHandler wgRegion = mock(IWorldTaskHandler.class);
        Player player = mock(Player.class);
        when(wgRegion.getFieldName()).thenReturn("WgRegionId");
        when(wgRegion.isHandling(player)).thenReturn(true);
        when(wgRegion.getTaskId(player)).thenReturn(271);
        registry.registerHandler(wgRegion);
        KnkTaskClaimCommand command = new KnkTaskClaimCommand(mock(Plugin.class), worldTasksApi, registry, "dev");

        boolean handled = command.onCommand(player, mock(Command.class), "claim", new String[] {"ABC123"});

        assertTrue(handled);
        verify(player).sendMessage(ChatColor.RED + "You still have task #271 (WgRegionId) open. "
                + "Finish it, or type 'cancel' to stop it, then claim again.");
        verifyNoInteractions(worldTasksApi);
    }

    @Test
    void theHeaderShowsTheEntityTypeAndFieldWhenThereIsAType() {
        assertEquals("GateStructure › LocationId", KnkTaskClaimCommand.taskLabel("GateStructure", "LocationId"));
    }

    @Test
    void theHeaderShowsJustTheFieldWithoutAType() {
        assertEquals("LocationId", KnkTaskClaimCommand.taskLabel(null, "LocationId"));
        assertEquals("LocationId", KnkTaskClaimCommand.taskLabel("  ", "LocationId"));
    }

    @Test
    void theHeaderNeverPrintsNull() {
        assertEquals("GateStructure", KnkTaskClaimCommand.taskLabel("GateStructure", null));
        assertEquals("(unknown field)", KnkTaskClaimCommand.taskLabel(null, null));
    }
}
