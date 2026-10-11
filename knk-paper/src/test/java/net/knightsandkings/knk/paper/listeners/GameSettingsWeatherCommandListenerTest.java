package net.knightsandkings.knk.paper.listeners;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.knightsandkings.knk.core.domain.settings.KnkWeather;
import net.knightsandkings.knk.core.domain.settings.KnkWeatherSettings;
import net.knightsandkings.knk.paper.settings.GameSettingsManager;

/** KNG-52 round 3: /weather in a world with a weather rule asks for confirmation first. */
class GameSettingsWeatherCommandListenerTest {

    private final GameSettingsManager settings = mock(GameSettingsManager.class);
    private final Player player = mock(Player.class);
    private final World world = mock(World.class);
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final GameSettingsWeatherCommandListener listener = new GameSettingsWeatherCommandListener(settings, now::get);

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.hasPermission("minecraft.command.weather")).thenReturn(true);
        when(world.getName()).thenReturn("world");
        when(settings.refreshIntervalSeconds()).thenReturn(30);
        when(settings.weatherFor(world)).thenReturn(new KnkWeatherSettings(KnkWeatherSettings.Mode.CONSTANT, KnkWeather.RAIN, Set.of(), 34, 33, 33));
    }

    private PlayerCommandPreprocessEvent event(String message) {
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getMessage()).thenReturn(message);
        return event;
    }

    @Test
    void theFirstWeatherCommandIsHeldAndExplained_TheSameAgainGoesThrough() {
        PlayerCommandPreprocessEvent first = event("/weather clear");
        listener.onCommand(first);
        verify(first).setCancelled(true);
        verify(player, times(2)).sendMessage(any(Component.class));

        now.addAndGet(10_000);
        PlayerCommandPreprocessEvent again = event("/minecraft:weather CLEAR");
        listener.onCommand(again);
        verify(again, never()).setCancelled(anyBoolean());
    }

    @Test
    void theConfirmationExpires() {
        listener.onCommand(event("/weather clear"));
        now.addAndGet(16_000);

        PlayerCommandPreprocessEvent late = event("/weather clear");
        listener.onCommand(late);
        verify(late).setCancelled(true);
    }

    @Test
    void noRule_NoPermission_OrAnotherCommand_PassStraightThrough() {
        when(settings.weatherFor(world)).thenReturn(KnkWeatherSettings.normal());
        PlayerCommandPreprocessEvent normal = event("/weather clear");
        listener.onCommand(normal);
        verify(normal, never()).setCancelled(anyBoolean());

        when(settings.weatherFor(world)).thenReturn(new KnkWeatherSettings(KnkWeatherSettings.Mode.CONSTANT, KnkWeather.RAIN, Set.of(), 34, 33, 33));
        when(player.hasPermission("minecraft.command.weather")).thenReturn(false);
        PlayerCommandPreprocessEvent denied = event("/weather clear");
        listener.onCommand(denied);
        verify(denied, never()).setCancelled(anyBoolean());

        when(player.hasPermission("minecraft.command.weather")).thenReturn(true);
        PlayerCommandPreprocessEvent other = event("/weatherman clear");
        listener.onCommand(other);
        PlayerCommandPreprocessEvent usage = event("/weather");
        listener.onCommand(usage);
        verify(other, never()).setCancelled(anyBoolean());
        verify(usage, never()).setCancelled(anyBoolean());
    }
}
