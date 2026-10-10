package net.knightsandkings.knk.paper.listeners;

import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.settings.WeatherRules;
import net.knightsandkings.knk.paper.settings.GameSettingsManager;

/**
 * The per-world weather rule and world load/unload for the Game Settings
 * (docs/specs/game-settings/DESIGN.md §3.5/§3.6). Only natural and sleep weather changes are
 * steered; commands and plugins (including the manager's own changes) pass.
 */
public class GameSettingsWorldListener implements Listener {

    private final Plugin plugin;
    private final GameSettingsManager settings;

    public GameSettingsWorldListener(Plugin plugin, GameSettingsManager settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent event) {
        WeatherRules.Verdict verdict = WeatherRules.onRainChange(settings.weatherFor(event.getWorld()),
            event.toWeatherState(), event.getWorld().isThundering(), cause(event.getCause().name()));
        if (verdict == WeatherRules.Verdict.ALLOW) {
            return;
        }
        event.setCancelled(true);
        if (verdict == WeatherRules.Verdict.CANCEL_AND_PICK) {
            settings.pickWeightedWeather(event.getWorld());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onThunderChange(ThunderChangeEvent event) {
        WeatherRules.Verdict verdict = WeatherRules.onThunderChange(settings.weatherFor(event.getWorld()),
            event.getWorld().hasStorm(), event.toThunderState(), cause(event.getCause().name()));
        if (verdict != WeatherRules.Verdict.ALLOW) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        settings.apply(event.getWorld());
        Bukkit.getScheduler().runTask(plugin, settings::reportWorlds);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (!event.isCancelled()) {
            // The world is gone by the next tick.
            Bukkit.getScheduler().runTask(plugin, settings::reportWorlds);
        }
    }

    /** Both events' Cause enums have NATURAL and SLEEP; everything else (COMMAND, PLUGIN, UNKNOWN) passes. */
    static WeatherRules.Cause cause(String name) {
        return switch (name) {
            case "NATURAL" -> WeatherRules.Cause.NATURAL;
            case "SLEEP" -> WeatherRules.Cause.SLEEP;
            default -> WeatherRules.Cause.OTHER;
        };
    }
}
