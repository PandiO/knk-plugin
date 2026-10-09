package net.knightsandkings.knk.paper.locations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import net.knightsandkings.knk.core.domain.location.LocationOrphanDigest;
import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Shows a {@code LocationOrphanDigest} notification (KNG-80) - one line per Location retention run that
 * found new orphans - to every online staff member holding knk.admin.location.orphans.notify,
 * clickable to run {@code /knk location orphans}. Built like CurrencyAlertNotifier, with one addition:
 * the poller hands over server notifications as soon as anyone is online, so when no holder is online
 * the latest digest is kept here and shown to the next holder who joins (until the plugin restarts;
 * the items themselves stay in the web panel and /knk location orphans either way).
 */
public final class LocationOrphanNotifier implements Listener {
    private static final Logger LOGGER = Logger.getLogger(LocationOrphanNotifier.class.getName());

    public static final String NOTIFY_NODE = "knk.admin.location.orphans.notify";

    private final BiFunction<Player, String, CompletableFuture<PermissionDecision>> permissions;
    private final Supplier<Collection<? extends Player>> onlinePlayers;
    private final Executor mainThread;
    private final AtomicReference<LocationOrphanDigest> undelivered = new AtomicReference<>();

    public LocationOrphanNotifier(BiFunction<Player, String, CompletableFuture<PermissionDecision>> permissions,
                                  Supplier<Collection<? extends Player>> onlinePlayers, Executor mainThread) {
        this.permissions = permissions;
        this.onlinePlayers = onlinePlayers;
        this.mainThread = mainThread;
    }

    /** Main thread (PlayerNotificationPoller). */
    public void handle(PlayerNotification notification) {
        LocationOrphanDigest digest = notification.locationOrphanDigest();
        if (digest == null) {
            return;
        }
        LOGGER.info("Location retention run #" + digest.runId() + ": " + digest.newCount() + " new orphaned Location(s), "
            + digest.openCount() + " open");

        List<CompletableFuture<Boolean>> sends = new ArrayList<>();
        for (Player player : List.copyOf(onlinePlayers.get())) {
            sends.add(sendIfHolder(player, digest));
        }
        CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).whenComplete((ignored, ex) -> {
            boolean anyone = sends.stream().anyMatch(f -> f.getNow(false));
            if (!anyone) {
                undelivered.set(digest);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        showUndelivered(event.getPlayer());
    }

    /** A digest no staff member saw yet goes to {@code player} when they hold the node. */
    public void showUndelivered(Player player) {
        LocationOrphanDigest pending = undelivered.get();
        if (pending == null) {
            return;
        }
        sendIfHolder(player, pending).thenAccept(sent -> {
            if (sent) {
                undelivered.compareAndSet(pending, null);
            }
        });
    }

    /** Completes true when the message was handed to the main thread for a holder of the node. */
    private CompletableFuture<Boolean> sendIfHolder(Player player, LocationOrphanDigest digest) {
        return permissions.apply(player, NOTIFY_NODE)
            .thenApply(decision -> {
                if (decision != PermissionDecision.ALLOWED) {
                    return false;
                }
                mainThread.execute(() -> {
                    if (player.isOnline()) {
                        player.sendMessage(message(digest));
                    }
                });
                return true;
            })
            .exceptionally(ex -> {
                LOGGER.fine("Could not check " + NOTIFY_NODE + " for " + player.getName() + ": " + ex);
                return false;
            });
    }

    static Component message(LocationOrphanDigest digest) {
        String what = digest.newCount() == 1 ? "1 orphaned Location needs" : digest.newCount() + " orphaned Locations need";
        return Component.text("[Locations] ", NamedTextColor.GOLD)
            .append(Component.text(what + " review", NamedTextColor.YELLOW))
            .append(Component.text(" (" + digest.openCount() + " open in total). ", NamedTextColor.GRAY))
            .append(Component.text("Click to list them.", NamedTextColor.WHITE))
            .clickEvent(ClickEvent.runCommand("/knk location orphans"))
            .hoverEvent(HoverEvent.showText(Component.text("Check #" + digest.runId()
                + " - list them here, or keep/delete them in the web app (Player moderation → Orphaned Locations)")));
    }
}
