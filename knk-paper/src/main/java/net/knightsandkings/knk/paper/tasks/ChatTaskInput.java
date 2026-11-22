package net.knightsandkings.knk.paper.tasks;

import org.bukkit.entity.Player;

/**
 * A player world task handler that reads chat commands ({@code save}, {@code cancel} ...) and is registered only by task
 * type (PandiO/knk-workspace#799; since #988 only the production {@code CaptureRadius} task). {@code WorldTaskChatListener} routes the chat of a player such a handler is handling
 * to it, after the WgRegionId and Location handlers it has always asked first.
 */
public interface ChatTaskInput {

    /** On the main thread; true when the message belonged to the task (it is hidden from chat). */
    boolean onPlayerChat(Player player, String message);
}
