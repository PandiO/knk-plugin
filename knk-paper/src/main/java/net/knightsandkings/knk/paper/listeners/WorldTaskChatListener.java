package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.tasks.GateDoorRegionCaptureHandler;
import net.knightsandkings.knk.paper.tasks.WorldTaskHandlerRegistry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * Listens for player chat events and routes them to active world task handlers.
 * Allows tasks to process chat input (e.g., 'save', 'cancel', 'pause', 'resume').
 */
public class WorldTaskChatListener implements Listener {
    private static final Logger LOGGER = Logger.getLogger(WorldTaskChatListener.class.getName());

    private final Plugin plugin;
    private final WorldTaskHandlerRegistry handlerRegistry;
    // Not part of WorldTaskHandlerRegistry - item 6.3/6.4's region capture/redefine is a direct
    // admin command, not a FormConfig-driven WorldTask (see GateDoorRegionCaptureHandler's
    // class Javadoc) - so it's routed here directly, matching the existing per-handler-type
    // dispatch style already used for WgRegionId/Location below rather than through the registry.
    private final GateDoorRegionCaptureHandler gateDoorRegionCaptureHandler;

    public WorldTaskChatListener(Plugin plugin, WorldTaskHandlerRegistry handlerRegistry,
                                  GateDoorRegionCaptureHandler gateDoorRegionCaptureHandler) {
        this.plugin = plugin;
        this.handlerRegistry = handlerRegistry;
        this.gateDoorRegionCaptureHandler = gateDoorRegionCaptureHandler;
    }

    /**
     * Handle player chat events and route to active task handlers.
     * If a handler processes the message, it is cancelled so it doesn't appear in chat.
     */
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        // Try to route to WgRegionId task handler first (most common use case)
        var wgHandler = handlerRegistry.getWgRegionIdHandler();
        if (wgHandler != null && wgHandler.onPlayerChat(player, message)) {
            event.setCancelled(true);
            LOGGER.fine("WorldTask chat message handled for player " + player.getName() + ": " + message);
            return;
        }

        // TODO: Route to other task handlers as they are implemented
        var locationHandler = handlerRegistry.getLocationTaskHandler();
        if (locationHandler != null && locationHandler.onPlayerChat(player, message)) {
            event.setCancelled(true);
            return;
        }

        if (gateDoorRegionCaptureHandler != null && gateDoorRegionCaptureHandler.onPlayerChat(player, message)) {
            event.setCancelled(true);
        }
    }
}
