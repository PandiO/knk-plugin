package net.knightsandkings.knk.paper.tasks;

import org.bukkit.entity.Player;

/**
 * Interface for handlers that process specific types of world tasks.
 * Each task type (e.g., "SelectLocation", "SelectRegion") should have a handler implementation.
 */
public interface IWorldTaskHandler {
    /**
     * Get the task type/field name this handler supports.
     * @return The field name (e.g., "Location", "WgRegionId")
     */
    String getFieldName();

    /**
     * Start handling a task for a player.
     * This typically involves entering a "selection mode" where the player
     * can interact with the world to provide the required data.
     * 
     * @param player The player handling the task
     * @param taskId The task ID
     * @param inputJson Optional input data from the workflow session
     */
    void startTask(Player player, int taskId, String inputJson);

    /**
     * Check if a player is currently handling a task of this type.
     * @param player The player to check
     * @return true if the player is handling a task of this type
     */
    boolean isHandling(Player player);

    /**
     * Cancel the current task for a player.
     * @param player The player whose task should be cancelled
     */
    void cancel(Player player);

    /**
     * Get the current task ID for a player, if any.
     * @param player The player
     * @return The task ID, or null if not handling a task
     */
    Integer getTaskId(Player player);

    /**
     * PandiO/knk-workspace#180: whether this player (by UUID) holds a task here. Thread-safe: the chat listener asks it on
     * the async chat thread before it hops to the main thread. Handlers whose session lives only inside
     * {@link #startTask} (ItemScan, KitScan) hold nothing.
     */
    default boolean isHandling(java.util.UUID playerId) {
        return false;
    }

    /** #180: where an in-game {@code cancel} (hand back) or {@code discard} is reported. Set by the registry. */
    default void setLifecycle(WorldTaskLifecycle lifecycle) {
    }
}
