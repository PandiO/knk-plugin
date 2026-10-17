package net.knightsandkings.knk.paper.tasks;

/**
 * KNG-113: a gate door's region is coordinates in the door's own world, so it is drawn and saved only while the player
 * stands in that world. Kept apart from {@link GateDoorRegionCaptureHandler} so it can be tested without WorldEdit.
 */
final class GateRegionWorld {

    private GateRegionWorld() {
    }

    /** Why the player cannot draw the door's region where they stand, or null when they can. */
    static String wrongWorld(String doorWorld, String playerWorld, String doorName) {
        if (doorWorld == null || doorWorld.isBlank() || doorWorld.equalsIgnoreCase(playerWorld)) {
            return null;
        }
        return "[Gate Region] Door '" + doorName + "' is in world '" + doorWorld + "', but you are in '" + playerWorld
            + "'. Go to its world first (/gatedoor tp).";
    }
}
