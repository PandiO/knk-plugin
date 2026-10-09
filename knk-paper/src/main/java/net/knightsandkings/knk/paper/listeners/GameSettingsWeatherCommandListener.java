package net.knightsandkings.knk.paper.listeners;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.knightsandkings.knk.core.settings.WeatherCommandNotice;
import net.knightsandkings.knk.paper.settings.GameSettingsManager;
import net.knightsandkings.knk.paper.utils.DisplayTextFormatter;

/**
 * Asks for confirmation before a player's {@code /weather} changes a world that has a weather rule
 * on the Game Settings page (KNG-52 round 3, developer request 2026-10-09): it names the rule and
 * says whether the next refresh will undo the change. The same command again (typed, or the
 * clickable [Change anyway]) within {@value #CONFIRM_SECONDS} s goes through. Players without
 * {@code minecraft.command.weather}, the console and worlds without a rule are not affected.
 */
public class GameSettingsWeatherCommandListener implements Listener {

    static final int CONFIRM_SECONDS = 15;
    private static final String WEATHER_NODE = "minecraft.command.weather";

    private final GameSettingsManager settings;
    private final LongSupplier clock;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(String command, long expiresAt) {
    }

    public GameSettingsWeatherCommandListener(GameSettingsManager settings) {
        this(settings, System::currentTimeMillis);
    }

    GameSettingsWeatherCommandListener(GameSettingsManager settings, LongSupplier clock) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().trim();
        String[] parts = message.substring(1).split("\\s+");
        String label = parts[0].toLowerCase(Locale.ROOT);
        if (!label.equals("weather") && !label.equals("minecraft:weather") || parts.length < 2) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission(WEATHER_NODE)) {
            return;
        }
        Optional<String> notice = WeatherCommandNotice.notice(player.getWorld().getName(), settings.weatherFor(player.getWorld()),
            WeatherCommandNotice.requested(parts[1]), settings.refreshIntervalSeconds());
        if (notice.isEmpty()) {
            return;
        }
        // "/weather clear" and "/minecraft:weather CLEAR" confirm each other.
        parts[0] = "weather";
        String normalized = String.join(" ", parts).toLowerCase(Locale.ROOT);
        long now = clock.getAsLong();
        Pending confirmed = pending.remove(player.getUniqueId());
        if (confirmed != null && confirmed.command().equals(normalized) && now <= confirmed.expiresAt()) {
            return;
        }
        event.setCancelled(true);
        pending.put(player.getUniqueId(), new Pending(normalized, now + CONFIRM_SECONDS * 1000L));
        player.sendMessage(DisplayTextFormatter.toComponent(notice.get()));
        player.sendMessage(Component.text("[Change anyway]", NamedTextColor.GREEN)
            .clickEvent(ClickEvent.runCommand(message))
            .hoverEvent(HoverEvent.showText(Component.text("Run " + message)))
            .append(Component.text(" or type the command again within " + CONFIRM_SECONDS + " s.", NamedTextColor.GRAY)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
