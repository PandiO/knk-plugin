package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.tasks.GateDoorRegionCaptureHandler;
import net.knightsandkings.knk.paper.tasks.WorldTaskHandlerRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Listens for player chat events and routes them to active world task handlers.
 * Allows tasks to process chat input (e.g., 'save', 'cancel', 'discard', 'pause', 'resume').
 *
 * <p>PandiO/knk-workspace#180: chat arrives on an async thread, but the handlers read the player's location, change
 * WorldGuard regions and their own state. So the handlers run on the main thread: the listener checks (thread-safe)
 * whether the player has a task at all, and only then waits for the main thread to route the message (at most
 * {@link #MAIN_THREAD_WAIT_MILLIS}), so it can still hide a handled command from chat. Other players' chat is not delayed.
 */
public class WorldTaskChatListener implements Listener {
    private static final Logger LOGGER = Logger.getLogger(WorldTaskChatListener.class.getName());
    static final long MAIN_THREAD_WAIT_MILLIS = 2000;

    /** Runs a call on the server's main thread and waits for its answer. */
    public interface MainThread {
        boolean isMainThread();

        <T> T call(Callable<T> call, long timeoutMillis) throws InterruptedException, ExecutionException, TimeoutException;

        static MainThread bukkit(Plugin plugin) {
            return new MainThread() {
                @Override
                public boolean isMainThread() {
                    return plugin.getServer().isPrimaryThread();
                }

                @Override
                public <T> T call(Callable<T> call, long timeoutMillis)
                        throws InterruptedException, ExecutionException, TimeoutException {
                    return plugin.getServer().getScheduler().callSyncMethod(plugin, call).get(timeoutMillis, TimeUnit.MILLISECONDS);
                }
            };
        }
    }

    private final WorldTaskHandlerRegistry handlerRegistry;
    // Not part of WorldTaskHandlerRegistry - item 6.3/6.4's region capture/redefine is a direct
    // admin command, not a FormConfig-driven WorldTask (see GateDoorRegionCaptureHandler's
    // class Javadoc) - so it's routed here directly, matching the existing per-handler-type
    // dispatch style already used for WgRegionId/Location below rather than through the registry.
    private final GateDoorRegionCaptureHandler gateDoorRegionCaptureHandler;
    private final MainThread mainThread;

    public WorldTaskChatListener(Plugin plugin, WorldTaskHandlerRegistry handlerRegistry,
                                  GateDoorRegionCaptureHandler gateDoorRegionCaptureHandler) {
        this(handlerRegistry, gateDoorRegionCaptureHandler, MainThread.bukkit(plugin));
    }

    public WorldTaskChatListener(WorldTaskHandlerRegistry handlerRegistry,
                                 GateDoorRegionCaptureHandler gateDoorRegionCaptureHandler, MainThread mainThread) {
        this.handlerRegistry = handlerRegistry;
        this.gateDoorRegionCaptureHandler = gateDoorRegionCaptureHandler;
        this.mainThread = mainThread;
    }

    /**
     * Handle player chat events and route to active task handlers.
     * If a handler processes the message, it is cancelled so it doesn't appear in chat.
     */
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        if (mainThread.isMainThread()) {
            if (route(player, message)) {
                event.setCancelled(true);
            }
            return;
        }

        boolean hasTask = handlerRegistry.isHandling(player.getUniqueId())
            || (gateDoorRegionCaptureHandler != null && gateDoorRegionCaptureHandler.isHandling(player));
        if (!hasTask) {
            return;
        }

        try {
            if (mainThread.call(() -> route(player, message), MAIN_THREAD_WAIT_MILLIS)) {
                event.setCancelled(true);
            }
        } catch (TimeoutException e) {
            // The main thread is busy; the command still runs when it gets there. Keep it out of public chat.
            event.setCancelled(true);
            LOGGER.warning("World task chat input of " + player.getName() + " waited over " + MAIN_THREAD_WAIT_MILLIS + " ms for the main thread");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOGGER.warning("World task chat input of " + player.getName() + " failed: " + e.getCause());
        }
    }

    /** On the main thread: the first handler that takes the message. */
    private boolean route(Player player, String message) {
        // Try to route to WgRegionId task handler first (most common use case)
        var wgHandler = handlerRegistry.getWgRegionIdHandler();
        if (wgHandler != null && wgHandler.onPlayerChat(player, message)) {
            LOGGER.fine("WorldTask chat message handled for player " + player.getName());
            return true;
        }

        var locationHandler = handlerRegistry.getLocationTaskHandler();
        if (locationHandler != null && locationHandler.onPlayerChat(player, message)) {
            return true;
        }

        return gateDoorRegionCaptureHandler != null && gateDoorRegionCaptureHandler.onPlayerChat(player, message);
    }
}
