package net.knightsandkings.knk.core.npc.nav;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * An NPC's last footsteps (implementation plan 4b.2 item 6.1): the last {@link #KEEP} block positions it stood in, with
 * floor continuity between them (a drop of more than {@link HopPlanner#CONTINUITY_RANGE} blocks starts them again), so
 * a stuck NPC can walk back the way it came. Also notices falls (item 12): feet that dropped {@link #FALL_BLOCKS} or
 * more below the last floor stood on, with the NPC off the ground in between. Main thread; one per NPC.
 */
public final class RecentSteps {

    /** Footsteps kept. */
    public static final int KEEP = 8;
    /** A drop of this many blocks or more while off the ground is a fall. */
    public static final double FALL_BLOCKS = 3;

    private final Deque<NavPoint> steps = new ArrayDeque<>();
    private double groundY = Double.NaN;
    private boolean airborne;

    /**
     * Records where the NPC's feet are now; returns the drop in blocks when it just landed from a fall of
     * {@link #FALL_BLOCKS} or more, else 0.
     */
    public double record(NavPoint feet, boolean onGround) {
        double fell = 0;
        if (!onGround) {
            airborne = true;
        } else {
            if (airborne && !Double.isNaN(groundY) && groundY - feet.y() >= FALL_BLOCKS) {
                fell = groundY - feet.y();
            }
            airborne = false;
            groundY = feet.y();
        }
        NavPoint last = steps.peekLast();
        if (last != null && last.blockX() == feet.blockX() && last.blockY() == feet.blockY()
            && last.blockZ() == feet.blockZ()) {
            return fell;
        }
        if (last != null && (!last.world().equals(feet.world())
            || Math.abs(last.blockY() - feet.blockY()) > HopPlanner.CONTINUITY_RANGE || fell > 0)) {
            steps.clear(); // no continuity with what came before: those steps lead nowhere now
        }
        if (onGround) {
            steps.addLast(new NavPoint(feet.world(), feet.blockX() + 0.5, feet.blockY(), feet.blockZ() + 0.5));
            while (steps.size() > KEEP) {
                steps.pollFirst();
            }
        }
        return fell;
    }

    /** Forgets the footsteps (a new route from somewhere else, a teleport, a respawn). */
    public void clear() {
        steps.clear();
        groundY = Double.NaN;
        airborne = false;
    }

    public int size() {
        return steps.size();
    }

    /**
     * The way back (implementation plan 4b.2 item 6.1): the footsteps from the newest back, as feet positions at block
     * centres, until one is at least {@code blocks} away from {@code feet} horizontally; empty when the footsteps do
     * not reach a block away (nothing to walk back on).
     */
    public List<NavPoint> wayBack(NavPoint feet, double blocks) {
        List<NavPoint> out = new ArrayList<>();
        Iterator<NavPoint> it = steps.descendingIterator();
        double furthest = 0;
        while (it.hasNext()) {
            NavPoint step = it.next();
            if (step.blockX() == feet.blockX() && step.blockZ() == feet.blockZ() && out.isEmpty()) {
                continue; // the block it stands in
            }
            out.add(step);
            furthest = Math.max(furthest, step.horizontalDistance(feet));
            if (furthest >= blocks) {
                break;
            }
        }
        return furthest >= 1 ? out : List.of();
    }
}
