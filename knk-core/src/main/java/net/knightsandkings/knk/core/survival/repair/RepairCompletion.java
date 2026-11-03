package net.knightsandkings.knk.core.survival.repair;

/**
 * A finished repair (DESIGN §5): consumed exactly once per cycle. The adapter rechecks ownership and match state,
 * then starts the animated rebuild (destroyed door, S64) or sets full health in one step (damaged door, S49).
 *
 * @param doorId    the door
 * @param kind      what the completion restores
 * @param maxHealth the door's effective maximum health H
 */
public record RepairCompletion(String doorId, Kind kind, double maxHealth) {

    public enum Kind {
        /** The door was destroyed: start the animated rebuild; health is full at rebuild complete. */
        REBUILD_DESTROYED_DOOR,
        /** The door was damaged but standing: set it to full health in one step. */
        RESTORE_DAMAGED_DOOR
    }
}
