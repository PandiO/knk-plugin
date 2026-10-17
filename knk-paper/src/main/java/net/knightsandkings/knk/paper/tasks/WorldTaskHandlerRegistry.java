package net.knightsandkings.knk.paper.tasks;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Registry for world task handlers.
 * Allows registering handlers for different field types and dispatching tasks to them.
 */
public class WorldTaskHandlerRegistry {
    private static final Logger LOGGER = Logger.getLogger(WorldTaskHandlerRegistry.class.getName());
    private final Map<String, IWorldTaskHandler> handlers = new HashMap<>();
    private final Map<String, IWorldTaskHandler> handlersByTaskType = new HashMap<>();

    /**
     * Register a handler for a specific field type.
     * @param handler The handler to register
     */
    public void registerHandler(IWorldTaskHandler handler) {
        String fieldName = handler.getFieldName();
        if (handlers.containsKey(fieldName)) {
            LOGGER.warning("Handler for field '" + fieldName + "' is already registered. Overwriting.");
        }
        handlers.put(fieldName, handler);
        LOGGER.info("Registered WorldTask handler for field: " + fieldName);
    }

    /**
     * Register a handler for a configured world task type.
     * @param taskType The task type emitted by FormConfig
     * @param handler The handler to register
     */
    public void registerHandler(String taskType, IWorldTaskHandler handler) {
        if (handlersByTaskType.containsKey(taskType)) {
            LOGGER.warning("Handler for task type '" + taskType + "' is already registered. Overwriting.");
        }
        handlersByTaskType.put(taskType, handler);
        LOGGER.info("Registered WorldTask handler for task type: " + taskType);
    }

    /**
     * Get a handler for a specific field type.
     * @param fieldName The field name
     * @return The handler, if registered
     */
    public Optional<IWorldTaskHandler> getHandler(String fieldName) {
        return Optional.ofNullable(handlers.get(fieldName));
    }

    /**
     * Resolve a handler by configured task type, falling back to the legacy field name.
     * @param taskType The configured task type
     * @param fieldName The entity field name
     * @return The handler, if registered
     */
    public Optional<IWorldTaskHandler> getHandler(String taskType, String fieldName) {
        IWorldTaskHandler taskTypeHandler = handlersByTaskType.get(taskType);
        return taskTypeHandler != null ? Optional.of(taskTypeHandler) : getHandler(fieldName);
    }

    /**
     * Start a task for a player using the appropriate handler.
     * @param player The player
     * @param fieldName The field name/type
     * @param taskId The task ID
     * @param inputJson Optional input data
     * @return true if a handler was found and started, false otherwise
     */
    public boolean startTask(Player player, String fieldName, int taskId, String inputJson) {
        Optional<IWorldTaskHandler> handler = getHandler(fieldName);
        if (handler.isPresent()) {
            handler.get().startTask(player, taskId, inputJson);
            return true;
        } else {
            LOGGER.warning("No handler registered for field: " + fieldName);
            return false;
        }
    }

    /**
     * Start a task using its configured task type, with legacy field-name fallback.
     */
    public boolean startTask(Player player, String taskType, String fieldName, int taskId, String inputJson) {
        Optional<IWorldTaskHandler> handler = getHandler(taskType, fieldName);
        if (handler.isPresent()) {
            handler.get().startTask(player, taskId, inputJson);
            return true;
        }

        LOGGER.warning("No handler registered for task type '" + taskType + "' or field '" + fieldName + "'");
        return false;
    }

    /**
     * Cancel any active task for a player.
     * @param player The player
     */
    public void cancelAllTasks(Player player) {
        for (IWorldTaskHandler handler : handlers.values()) {
            if (handler.isHandling(player)) {
                handler.cancel(player);
                LOGGER.fine("Cancelled task for player " + player.getName() + " in handler " + handler.getFieldName());
            }
        }
    }

    /**
     * Check if a player is handling any task.
     * @param player The player
     * @return true if the player is handling a task
     */
    public boolean isHandlingAnyTask(Player player) {
        return handlers.values().stream().anyMatch(h -> h.isHandling(player));
    }

    /**
     * Get the handler currently being used by a player.
     * @param player The player
     * @return The active handler, if any
     */
    public Optional<IWorldTaskHandler> getActiveHandler(Player player) {
        // A handler registered only by task type can hold a task too (KNG-174)
        return Stream.concat(handlers.values().stream(), handlersByTaskType.values().stream())
                .distinct()
                .filter(h -> h.isHandling(player))
                .findFirst();
    }

    /**
     * KNG-174: why the player can't claim another task yet, or empty when they can. A player with a task still open
     * (any handler is handling them) finishes or cancels it first; a second claim would start a second task that
     * shares the chat commands with the first.
     */
    public Optional<String> openTaskRefusal(Player player) {
        return getActiveHandler(player).map(handler -> {
            Integer taskId = handler.getTaskId(player);
            String task = taskId != null
                    ? "task #" + taskId + " (" + handler.getFieldName() + ")"
                    : "a " + handler.getFieldName() + " task";
            return "You still have " + task + " open. Finish it, or type 'cancel' to stop it, then claim again.";
        });
    }

    /**
     * Get the WgRegionId handler (convenience method).
     * @return The WgRegionId handler if registered, null otherwise
     */
    public WgRegionIdTaskHandler getWgRegionIdHandler() {
        IWorldTaskHandler handler = handlers.get("WgRegionId");
        if (handler instanceof WgRegionIdTaskHandler) {
            return (WgRegionIdTaskHandler) handler;
        }
        return null;
    }

    /**
     * Get the Location task handler (convenience method).
     * @return The Location task handler if registered, null otherwise
     */    
    public LocationTaskHandler getLocationTaskHandler() {
        IWorldTaskHandler handler = handlers.get("Location");
        if (handler instanceof LocationTaskHandler) {
            return (LocationTaskHandler) handler;
        }
        return null;
    }
}
