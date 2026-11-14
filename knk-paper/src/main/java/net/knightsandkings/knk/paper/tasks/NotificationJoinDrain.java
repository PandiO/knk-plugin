package net.knightsandkings.knk.paper.tasks;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Shows a joining player what was queued for them while they were offline (PandiO/knk-workspace#485), also while the
 * realtime hub is connected. The {@link PlayerNotificationPoller} stops its HTTP poll while the hub pushes, and a push
 * for an offline player stays in the API's queue; without this, such a notification (an {@code AccountLinked} after a
 * game-first registration, for one) would wait for the next hub reconnect instead of the next join.
 *
 * <p>While the hub is down the poll runs every few seconds and picks it up anyway, so nothing extra happens then. The
 * drain runs {@link #DELAY_TICKS} after the join, so it reads after the join messages.
 */
public final class NotificationJoinDrain implements Listener {
    /** Two seconds: after the welcome and join messages. */
    public static final long DELAY_TICKS = 40L;

    private final BooleanSupplier hubConnected;
    private final Runnable drain;
    private final Consumer<Runnable> later;

    /**
     * @param hubConnected whether the realtime hub is connected (the poll is paused)
     * @param drain        {@link PlayerNotificationPoller#drainNow()}
     * @param later        runs the drain {@link #DELAY_TICKS} from now (the Bukkit scheduler)
     */
    public NotificationJoinDrain(BooleanSupplier hubConnected, Runnable drain, Consumer<Runnable> later) {
        this.hubConnected = hubConnected;
        this.drain = drain;
        this.later = later;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (hubConnected.getAsBoolean()) {
            later.accept(drain);
        }
    }
}
