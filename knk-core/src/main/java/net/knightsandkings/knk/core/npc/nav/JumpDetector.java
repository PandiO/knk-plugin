package net.knightsandkings.knk.core.npc.nav;

/**
 * Tells a body's real jumps from its steps up (link B4, PandiO/knk-workspace#673; chain report decision 34.2.2), one
 * sample per tick. A jump leaves the ground with the take-off speed of a jump (vanilla 0.42 blocks per tick upwards),
 * so it is counted by its <b>upward speed</b>: the first tick the body moves up at {@link #TAKE_OFF_SPEED} or more.
 * A step up a half-block stair or slab lifts the feet by up to 0.6 in one tick without that speed (the body stays on
 * the ground), so it is no jump. Where the engine cannot tell the speed ({@link NpcMover#verticalSpeed()} NaN), the
 * rise per tick is used, and only while the body is off the ground after standing on it. One detector per body; pure.
 */
public final class JumpDetector {

    /**
     * The upward speed, in blocks per tick, from which a body is jumping: below a jump's take-off speed (0.42) and
     * its next tick (0.33), well above what a step up or a walk leaves (0 or the gravity tick's -0.08).
     */
    public static final double TAKE_OFF_SPEED = 0.3;

    private boolean started;
    private double prevY;
    private boolean prevGround;
    private boolean inJump;

    /**
     * The next tick's sample; true when a jump starts on it.
     *
     * @param y             feet height
     * @param onGround      whether the body stands on the ground
     * @param verticalSpeed the engine's vertical speed in blocks per tick, NaN when it cannot tell
     */
    public boolean sample(double y, boolean onGround, double verticalSpeed) {
        boolean rising;
        if (!started) {
            started = true;
            rising = !Double.isNaN(verticalSpeed) && verticalSpeed >= TAKE_OFF_SPEED;
        } else if (!Double.isNaN(verticalSpeed)) {
            rising = verticalSpeed >= TAKE_OFF_SPEED;
        } else {
            // without the speed: a rise of a take-off tick, off the ground, from the ground or still going up
            rising = !onGround && y - prevY >= TAKE_OFF_SPEED && (prevGround || inJump);
        }
        boolean starts = rising && !inJump;
        inJump = rising || (inJump && !onGround);
        prevY = y;
        prevGround = onGround;
        return starts;
    }

    /** Whether the body is in a jump it has not landed from yet. */
    public boolean inJump() {
        return inJump;
    }
}
