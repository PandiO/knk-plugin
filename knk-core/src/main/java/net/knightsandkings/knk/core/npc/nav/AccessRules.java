package net.knightsandkings.knk.core.npc.nav;

import java.util.Optional;
import java.util.function.Predicate;

import net.knightsandkings.knk.core.gates.target.GateBox;

/**
 * The access rules as they are <b>now</b>, asked while an NPC walks (implementation plan Phase 3; BEHAVIOR_MODEL
 * NAV-02): a route planned under old rules is checked against these before it is walked on. Paper answers from
 * player navigation's access code ({@code NavigationAccess}), so NPCs and players are judged by the same rules.
 * Main thread; an implementation answers from caches and never blocks.
 */
public interface AccessRules {

    /** Rules that allow everything (tests, and a road navigation without access rules). */
    AccessRules OPEN = new AccessRules() {
        @Override
        public Predicate<String> mayEnter(AccessSubject subject) {
            return regionId -> true;
        }

        @Override
        public boolean gateBlocks(AccessSubject subject, int doorId) {
            return false;
        }
    };

    /**
     * The entry rule per WorldGuard region id for a subject, judged from the regions the subject stands in
     * ({@link AccessSubject#currentRegions}): a region it is in is not entered. The subject's own deny list wins.
     */
    Predicate<String> mayEnter(AccessSubject subject);

    /** Whether this gate door stops the subject now (closed, moving, jammed or locked, and not its to pass). */
    boolean gateBlocks(AccessSubject subject, int doorId);

    /**
     * Where a gate door's blocks are, closed and open, and so what its animation sweeps (PandiO/knk-workspace#507): a
     * walker in this box when the door starts moving is in its way. Empty when not known (the default): nobody is
     * held back by the door's box then.
     */
    default Optional<GateBox> doorBox(int doorId) {
        return Optional.empty();
    }
}
