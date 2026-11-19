package net.knightsandkings.knk.paper.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import net.knightsandkings.knk.core.map.OutlinePoint;
import net.knightsandkings.knk.core.map.RegionOutline;
import net.knightsandkings.knk.core.territory.TerritoryCache;
import net.knightsandkings.knk.core.territory.TerritoryCacheBuilder;
import net.knightsandkings.knk.core.territory.TerritoryHolder;
import net.knightsandkings.knk.core.territory.TerritoryInputs;
import net.knightsandkings.knk.core.territory.TerritorySettings;
import net.knightsandkings.knk.core.territory.TerritoryShape;
import net.knightsandkings.knk.core.territory.TerritoryTracker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** The lookup on moves: one event and one action bar line per change of area, nothing for moves inside it (#689). */
class TerritoryListenerTest {

    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final PluginManager events = mock(PluginManager.class);
    private TerritoryListener listener;

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn("world");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        listener = new TerritoryListener(tracker(), settings(true), events);
    }

    /** Phoenix's district 0..15 with range 40: the border runs between x 55 and 56. */
    private static TerritoryTracker tracker() {
        TerritoryInputs.Source district = new TerritoryInputs.Source(TerritoryInputs.SourceType.DISTRICT, 10, "Old Town",
                "world", "d", List.of(), TerritoryHolder.town(1), false, 40);
        RegionOutline outline = new RegionOutline("d", "world", List.of(new OutlinePoint(0, 0), new OutlinePoint(15, 0),
                new OutlinePoint(15, 15), new OutlinePoint(0, 15)), 0, 255);
        TerritoryCache cache = TerritoryCacheBuilder.build(
                new TerritoryInputs(true, "v1", Map.of(1, "Phoenix"), Map.of(), List.of(district), List.of()),
                List.of(new TerritoryCacheBuilder.PlacedSource(district, "world", TerritoryShape.of(List.of(outline)))),
                List.of(), 1L, 1000);
        return new TerritoryTracker(() -> cache);
    }

    private static TerritorySettings settings(boolean notify) {
        return new TerritorySettings(true, 10, notify, true, false, 0, Map.of());
    }

    private Location at(double x, double z) {
        return new Location(world, x, 64, z);
    }

    @Test
    void crossingTheBorder_firesTheEvent_andShowsTheLine_movesInsideDoNot() {
        listener.at(player, at(5, 5));          // the start
        listener.onMove(new PlayerMoveEvent(player, at(5, 5), at(30, 5)));
        verify(events, never()).callEvent(any());

        listener.onMove(new PlayerMoveEvent(player, at(55, 5), at(56, 5)));

        ArgumentCaptor<Event> event = ArgumentCaptor.forClass(Event.class);
        verify(events).callEvent(event.capture());
        PlayerTerritoryChangeEvent change = (PlayerTerritoryChangeEvent) event.getValue();
        assertEquals(TerritoryHolder.town(1), change.from().owner());
        assertTrue(change.to().isWilderness());
        ArgumentCaptor<Component> line = ArgumentCaptor.forClass(Component.class);
        verify(player).sendActionBar(line.capture());
        assertEquals("Leaving Phoenix: wilderness", PlainTextComponentSerializer.plainText().serialize(line.getValue()));
    }

    @Test
    void aMoveWithinOneBlockColumn_isNotLookedUp() {
        listener.at(player, at(55, 5));
        listener.onMove(new PlayerMoveEvent(player, at(55.2, 5.1), at(55.8, 5.9)));
        verify(events, never()).callEvent(any());
    }

    @Test
    void withNotifyPlayersOff_theEventStillFires_butNoLineIsShown() {
        TerritoryListener silent = new TerritoryListener(tracker(), settings(false), events);
        silent.at(player, at(5, 5));
        silent.at(player, at(80, 5));

        verify(events, times(1)).callEvent(any());
        verify(player, never()).sendActionBar(any(Component.class));
    }
}
