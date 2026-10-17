package net.knightsandkings.knk.paper.hub;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.kyori.adventure.text.Component;

/**
 * The hub's portals (hub DESIGN §11; developer decision 2026-10-12): walking into one of the hub's portal regions (a
 * WorldGuard region in the hub's world) takes the player into the game. The destination is the Game Settings join chain
 * without the hub: the player's group join spawn, else the default join spawn. Like Multiverse-Portals, the trigger is
 * movement into the region, with a short per-player cooldown.
 * <p>
 * Gameplay entry needs the API and the player's account (§13): while the API is down only exempt players pass, and a
 * player whose account hasn't loaded is told to wait. A destination the player may not enter (domain AllowEntry) is
 * refused. The teleport carries a one-off exit pass past the hub's own borders, and a successful entry ends the
 * player's outage return offer (§12).
 */
public final class HubPortalListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(HubPortalListener.class.getName());
    static final long COOLDOWN_MILLIS = 2_000L;

    private final HubService hub;
    private final HubOutageService outage;
    private final Predicate<Player> accountReady;
    private final Function<Player, Location> destination;
    private final HubOutageService.Access access;
    private final HubOutageService.Transfer transfer;
    private final Consumer<Player> arrived;
    private final LongSupplier clock;
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    /**
     * @param accountReady whether the player's account has loaded
     * @param destination  where the portal takes this player (group join spawn, else the default join spawn); null = none
     * @param arrived      after the teleport: the destination world's game mode, as on a join
     */
    public HubPortalListener(HubService hub, HubOutageService outage, Predicate<Player> accountReady,
                             Function<Player, Location> destination, HubOutageService.Access access,
                             HubOutageService.Transfer transfer, Consumer<Player> arrived, LongSupplier clock) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.outage = Objects.requireNonNull(outage, "outage");
        this.accountReady = Objects.requireNonNull(accountReady, "accountReady");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.access = Objects.requireNonNull(access, "access");
        this.transfer = Objects.requireNonNull(transfer, "transfer");
        this.arrived = Objects.requireNonNull(arrived, "arrived");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ() && from.getWorld() == to.getWorld())) {
            return;
        }
        Optional<String> portal = hub.portalAt(to);
        if (portal.isEmpty() || hub.portalAt(from).equals(portal)) {
            return;
        }
        use(event.getPlayer(), portal.get());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastUse.remove(event.getPlayer().getUniqueId());
    }

    /** What happened when a player walked into a portal. */
    enum Outcome { ENTERED, COOLDOWN, SERVICES_DOWN, ACCOUNT_LOADING, NO_DESTINATION, REFUSED, FAILED }

    Outcome use(Player player, String portal) {
        long now = clock.getAsLong();
        Long last = lastUse.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MILLIS) {
            return Outcome.COOLDOWN;
        }
        lastUse.put(player.getUniqueId(), now);
        if (outage.inOutage() && !outage.exempt(player)) {
            return tell(player, Outcome.SERVICES_DOWN, "The game world can't be entered while the server's services are down.");
        }
        if (!accountReady.test(player)) {
            return tell(player, Outcome.ACCOUNT_LOADING, "Your account is still loading. Try again in a moment.");
        }
        Location target = destination.apply(player);
        if (target == null || target.getWorld() == null || hub.inHub(target)) {
            LOGGER.warning("[KnK Hub] Portal " + portal + " has no gameplay destination for " + player.getName()
                + ": set a join spawn outside the hub on the Game Settings page");
            return tell(player, Outcome.NO_DESTINATION, "This portal has nowhere to take you yet.");
        }
        Optional<String> refused = access.refusal(player, target);
        if (refused.isPresent()) {
            return tell(player, Outcome.REFUSED, refused.get());
        }
        if (!transfer.move(player, target)) {
            return tell(player, Outcome.FAILED, "The portal could not take you. Try again.");
        }
        arrived.accept(player);
        outage.enteredGameplay(player);
        return Outcome.ENTERED;
    }

    private static Outcome tell(Player player, Outcome outcome, String message) {
        player.sendMessage(Component.text(message).color(ColorOptions.error));
        return outcome;
    }
}
