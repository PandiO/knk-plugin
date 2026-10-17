package net.knightsandkings.knk.paper.hub;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
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
 * Hub admission (hub DESIGN §1, §3, §16.2 H1): with a hub selected, a player whose join would use the server's default
 * spawn is placed at the hub spawn while they are still connecting, so they never appear in the gameplay world first -
 * also while the API is down, from the Game Settings in the offline store. Owners and the groups' own join settings
 * keep working ({@link HubJoinRoute}). When the hub cannot take players (its world, region or spawn is missing), a join
 * bound for it is refused with the reason, except for operators, who can fix it.
 */
@SuppressWarnings("UnstableApiUsage")
public final class HubAdmissionListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(HubAdmissionListener.class.getName());

    private final HubService hub;
    private final Predicate<UUID> operator;
    private final Function<UUID, HubJoinRoute> route;
    private BiConsumer<UUID, Location> outageAdmission = (uuid, loggedOutAt) -> { };

    /**
     * @param route where a joining player goes; called after their account was loaded (it may wait on a permission
     *              check, both events are asynchronous)
     */
    public HubAdmissionListener(HubService hub, Predicate<UUID> operator, Function<UUID, HubJoinRoute> route) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.operator = Objects.requireNonNull(operator, "operator");
        this.route = Objects.requireNonNull(route, "route");
    }

    /** Told where a player logged out when the outage, not their settings, sends them to the hub (§12 return spot). */
    public void setOutageAdmission(BiConsumer<UUID, Location> outageAdmission) {
        this.outageAdmission = outageAdmission != null ? outageAdmission : (uuid, loggedOutAt) -> { };
    }

    /** HIGH: after the account is loaded (PlayerListener, NORMAL), so the player's groups are known. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED || !hub.configured()) {
            return;
        }
        if (!route.apply(event.getUniqueId()).toHub()) {
            return;
        }
        String refusal = refusal(hub.unusable(), operator.test(event.getUniqueId()));
        if (refusal != null) {
            LOGGER.warning("[KnK Hub] Refused " + event.getName() + ": " + hub.unusable().orElse(""));
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(refusal));
        }
    }

    /** A join bound for the hub starts at the hub spawn; the join teleport then leaves the player there. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        UUID uuid = event.getConnection().getProfile().getId();
        if (uuid == null) {
            return;
        }
        hub.consumeAdmitted(uuid); // a flag left by a connection that dropped before joining must not skip this join's teleport
        if (!hub.configured()) {
            return;
        }
        HubJoinRoute joinRoute = route.apply(uuid);
        if (!joinRoute.toHub()) {
            return;
        }
        Optional<Location> spawn = hub.admissionSpawn();
        if (spawn.isPresent() && joinRoute == HubJoinRoute.OUTAGE_HUB && !event.isNewPlayer()) {
            outageAdmission.accept(uuid, event.getSpawnLocation());
        }
        spawn.ifPresent(location -> {
            event.setSpawnLocation(location);
            hub.markAdmitted(uuid);
        });
    }

    /** The kick message while the selected hub is unusable, or null when the player may join. */
    static String refusal(Optional<String> unusable, boolean operator) {
        if (unusable.isEmpty() || operator) {
            return null;
        }
        return "The server's hub is not available right now. Please try again later.";
    }
}
