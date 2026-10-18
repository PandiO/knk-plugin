package net.knightsandkings.knk.paper.tasks;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorldTaskHandlerRegistryTest {
    @Test
    void startsLocationSelectionHandlerForEntitySpecificFieldName() {
        WorldTaskHandlerRegistry registry = new WorldTaskHandlerRegistry();
        IWorldTaskHandler handler = mock(IWorldTaskHandler.class);
        Player player = mock(Player.class);
        registry.registerHandler("LocationSelection", handler);

        boolean started = registry.startTask(
                player,
                "LocationSelection",
                "AnchorPointId",
                85,
                "{}"
        );

        assertTrue(started);
        verify(handler).startTask(player, 85, "{}");
    }

    // KNG-174: a second claim is refused while a task is open
    @Test
    void anOpenTaskIsNamedByIdAndField() {
        WorldTaskHandlerRegistry registry = new WorldTaskHandlerRegistry();
        IWorldTaskHandler handler = mock(IWorldTaskHandler.class);
        Player player = mock(Player.class);
        when(handler.getFieldName()).thenReturn("Location");
        when(handler.isHandling(player)).thenReturn(true);
        when(handler.getTaskId(player)).thenReturn(88);
        registry.registerHandler(handler);

        assertEquals(Optional.of("You still have task #88 (Location) open. "
                        + "Finish it, or type 'cancel' to stop it, then claim again."),
                registry.openTaskRefusal(player));
    }

    @Test
    void noOpenTaskMeansNoRefusal() {
        WorldTaskHandlerRegistry registry = new WorldTaskHandlerRegistry();
        IWorldTaskHandler handler = mock(IWorldTaskHandler.class);
        when(handler.getFieldName()).thenReturn("WgRegionId");
        registry.registerHandler(handler);

        assertEquals(Optional.empty(), registry.openTaskRefusal(mock(Player.class)));
    }

    @Test
    void aHandlerRegisteredOnlyByTaskTypeCountsToo() {
        WorldTaskHandlerRegistry registry = new WorldTaskHandlerRegistry();
        IWorldTaskHandler handler = mock(IWorldTaskHandler.class);
        Player player = mock(Player.class);
        when(handler.getFieldName()).thenReturn("Location");
        when(handler.isHandling(player)).thenReturn(true);
        when(handler.getTaskId(player)).thenReturn(null); // Mockito would answer 0
        registry.registerHandler("LocationSelection", handler);

        assertEquals(Optional.of(handler), registry.getActiveHandler(player));
        assertEquals(Optional.of("You still have a Location task open. "
                        + "Finish it, or type 'cancel' to stop it, then claim again."),
                registry.openTaskRefusal(player));
    }
}
