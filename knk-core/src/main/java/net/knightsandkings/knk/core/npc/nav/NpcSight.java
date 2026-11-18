package net.knightsandkings.knk.core.npc.nav;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;

/**
 * Whether any player can see a place (implementation plan 4c.4 item 7, link M7): a pushed NPC that could not walk
 * back teleports home only where no player sees it go or arrive. Main thread; an implementation never loads a chunk.
 */
@FunctionalInterface
public interface NpcSight {

    /** Nobody ever sees anything (tests, engines without players): a teleport home is always allowed. */
    NpcSight NOBODY = feet -> false;

    /** Every place is seen: a teleport home never happens. */
    NpcSight EVERYBODY = feet -> true;

    /** Whether a player is near enough to see a body standing at {@code feet} and has a line of sight to it. */
    boolean seen(NavPoint feet);
}
