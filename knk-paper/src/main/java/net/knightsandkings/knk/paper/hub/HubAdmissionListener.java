package net.knightsandkings.knk.paper.hub;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import net.kyori.adventure.text.Component;

/**
 * Hub admission (hub DESIGN §1, §3, §16.2 H1): with a hub selected, every joining player is placed at the hub spawn
 * while they are still connecting, so they never appear in the gameplay world first - also while the API is down, from
 * the Game Settings in the offline store. When the hub cannot take players (its world, region or spawn is missing), the
 * join is refused with the reason, except for operators, who can fix it.
 */
@SuppressWarnings("UnstableApiUsage")
public final class HubAdmissionListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(HubAdmissionListener.class.getName());

    private final HubService hub;
    private final Predicate<UUID> operator;

    public HubAdmissionListener(HubService hub, Predicate<UUID> operator) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.operator = Objects.requireNonNull(operator, "operator");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        String refusal = refusal(hub.unusable(), operator.test(event.getUniqueId()));
        if (refusal != null) {
            LOGGER.warning("[KnK Hub] Refused " + event.getName() + ": " + hub.unusable().orElse(""));
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(refusal));
        }
    }

    /** Every join, new or returning, starts at the hub spawn. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        Optional<Location> spawn = hub.admissionSpawn();
        spawn.ifPresent(event::setSpawnLocation);
    }

    /** The kick message while the selected hub is unusable, or null when the player may join. */
    static String refusal(Optional<String> unusable, boolean operator) {
        if (unusable.isEmpty() || operator) {
            return null;
        }
        return "The server's hub is not available right now. Please try again later.";
    }
}
