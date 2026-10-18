package net.knightsandkings.knk.paper.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.paper.tasks.TempRegionRetentionTask;

/** KNG-103: {@code /knk regions temp [purge [hours]]} next to {@code repair}. */
class RegionsAdminCommandTest {

    private final CommandSender sender = mock(CommandSender.class);
    private final TempRegionRetentionTask tempRegions = mock(TempRegionRetentionTask.class);
    private final RegionsAdminCommand command = new RegionsAdminCommand(null, null, mock(Plugin.class), tempRegions);

    @Test
    void completesRepairTempAndPurge() {
        assertEquals(List.of("repair", "temp"), command.complete(sender, new String[] {""}));
        assertEquals(List.of("temp"), command.complete(sender, new String[] {"t"}));
        assertEquals(List.of("purge"), command.complete(sender, new String[] {"temp", "p"}));
        assertEquals(List.of(), command.complete(sender, new String[] {"repair", ""}));
    }

    @Test
    void purgeNeedsAtLeastOneHour() {
        command.execute(sender, new String[] {"temp", "purge", "0"});
        command.execute(sender, new String[] {"temp", "purge", "soon"});

        verify(sender, org.mockito.Mockito.times(2)).sendMessage(contains("at least 1"));
        verifyNoInteractions(tempRegions);
    }

    @Test
    void anUnknownTempArgumentShowsTheUsage() {
        command.execute(sender, new String[] {"temp", "delete"});

        verify(sender).sendMessage(contains("Usage: /knk regions temp [purge [hours]]"));
        verifyNoInteractions(tempRegions);
    }

    @Test
    void withoutTheCleanupTaskTempSaysSo() {
        new RegionsAdminCommand(null, null, mock(Plugin.class)).execute(sender, new String[] {"temp"});

        verify(sender).sendMessage(contains("not running"));
    }
}
