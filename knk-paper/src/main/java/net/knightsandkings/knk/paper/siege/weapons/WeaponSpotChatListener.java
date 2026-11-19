package net.knightsandkings.knk.paper.siege.weapons;

import net.knightsandkings.knk.paper.listeners.WorldTaskChatListener.MainThread;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Routes chat to an open {@link WeaponSpotTask} on the main thread, the way {@code WorldTaskChatListener} does for the
 * world tasks (a player with no spot task is not delayed), and ends the task when its admin quits.
 */
public class WeaponSpotChatListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(WeaponSpotChatListener.class.getName());
    private static final long MAIN_THREAD_WAIT_MILLIS = 2000;

    private final WeaponSpotTask task;
    private final MainThread mainThread;

    public WeaponSpotChatListener(WeaponSpotTask task, MainThread mainThread) {
        this.task = task;
        this.mainThread = mainThread;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!task.isHandling(player.getUniqueId())) return;
        String message = event.getMessage();
        if (mainThread.isMainThread()) {
            if (task.onPlayerChat(player, message)) event.setCancelled(true);
            return;
        }
        try {
            if (mainThread.call(() -> task.onPlayerChat(player, message), MAIN_THREAD_WAIT_MILLIS)) {
                event.setCancelled(true);
            }
        } catch (TimeoutException e) {
            event.setCancelled(true);
            LOGGER.warning("Weapon spot chat input of " + player.getName() + " waited over " + MAIN_THREAD_WAIT_MILLIS
                    + " ms for the main thread");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOGGER.warning("Weapon spot chat input of " + player.getName() + " failed: " + e.getCause());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        task.forget(event.getPlayer().getUniqueId());
    }
}
