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
    /**
     * ... when the engine, which can tell, saw the body fall at least this far in one go at one of the looks (a
     * 3-block fall lasts about 15 ticks, so a look every 5 ticks sees at least this much; a stair step never does).
     */
    public static final double ENGINE_FALL_BLOCKS = 1.5;
    /** A step this far (horizontally) from the last one was no step: the body was pushed or teleported. */
    public static final double JUMP_BLOCKS = 2.5;

    private final Deque<NavPoint> steps = new ArrayDeque<>();
    private double groundY = Double.NaN;
    private boolean airborne;
    /** The most fall distance the engine reported while off the ground this time (NaN: it cannot tell). */
    private double maxFall = Double.NaN;

    /**
     * Records where the NPC's feet are now; returns the drop in blocks when it just landed from a fall of
     * {@link #FALL_BLOCKS} or more, else 0.
     */
    public double record(NavPoint feet, boolean onGround) {
        return record(feet, onGround, Double.NaN);
    }

    /**
     * As {@link #record(NavPoint, boolean)}, with the engine's fall distance ({@link NpcMover#fallDistance()}): a drop
     * counts as a fall only when the engine also saw the body fall {@link #ENGINE_FALL_BLOCKS} or more in one go, so
     * walking down a flight between two looks (several stair steps, each a short hop) is no fall.
     */
    public double record(NavPoint feet, boolean onGround, double fallDistance) {
        double fell = 0;
        if (!onGround) {
            airborne = true;
            if (!Double.isNaN(fallDistance)) {
                maxFall = Double.isNaN(maxFall) ? fallDistance : Math.max(maxFall, fallDistance);
            }
        } else {
            if (airborne && !Double.isNaN(groundY) && groundY - feet.y() >= FALL_BLOCKS
                && (Double.isNaN(maxFall) || maxFall >= ENGINE_FALL_BLOCKS)) {
                fell = groundY - feet.y();
            }
            airborne = false;
            maxFall = Double.NaN;
            groundY = feet.y();
        }
        NavPoint last = steps.peekLast();
        if (last != null && last.blockX() == feet.blockX() && last.blockY() == feet.blockY()
            && last.blockZ() == feet.blockZ()) {
            return fell;
        }
        if (last != null && (!last.world().equals(feet.world())
            || Math.abs(last.blockY() - feet.blockY()) > HopPlanner.CONTINUITY_RANGE || fell > 0
            || last.horizontalDistance(feet) > JUMP_BLOCKS)) {
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
