package net.knightsandkings.knk.paper.tasks;

import org.bukkit.entity.Player;

/**
 * PandiO/knk-workspace#180: what a player handler reports when the player ends a task in chat. The handler stops its own
 * session (selection, temp region, WorldEdit session); the lifecycle tells the API.
 */
public interface WorldTaskLifecycle {
    /** {@code cancel}: the task goes back to Pending with its task code (someone can claim it again). */
    void handedBack(Player player, int taskId);

    /** {@code discard}: the task is cancelled for good (the web app shows it as cancelled). */
    void discarded(Player player, int taskId);

    WorldTaskLifecycle NONE = new WorldTaskLifecycle() {
        @Override
        public void handedBack(Player player, int taskId) {
        }

        @Override
        public void discarded(Player player, int taskId) {
        }
    };
}
