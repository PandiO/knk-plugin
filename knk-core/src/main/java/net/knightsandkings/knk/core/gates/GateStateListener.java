package net.knightsandkings.knk.core.gates;

/**
 * Multicast observer of gate (door) state changes, registered through
 * {@link GateManager#addStateListener(GateStateListener)}.
 *
 * <p>Fired by {@link GateManager#fireStateChanged(int)} after every mutation of a door's
 * animation state that {@code GateManager} performs itself (open start, close start, animation
 * completion, forced state, (re)cache on reload) and by the knk-paper call sites that mutate gate
 * state outside it (destroy/respawn, jam, admin destroyed/active toggles). It is a *hint* that
 * something about the door changed: listeners re-read the door's effective accessors on
 * {@code CachedGateDoor} rather than trusting any payload. Navigation (DESIGN §6.7) uses it to
 * re-check active routes; D13's periodic re-check covers whatever no call site reports.
 *
 * <p>Unlike the one-shot {@link GateManager#setAnimationCompletionCallback} this is permanent
 * (until removed) and shared by any number of listeners. Fired on whichever thread performed the
 * mutation - the main thread for everything driven by the animation task and commands, but
 * {@link GateManager#cacheGate} runs on the async loader threads; listeners must be thread-safe
 * or hop to the main thread themselves.
 */
@FunctionalInterface
public interface GateStateListener {

    /**
     * A door's state may have changed.
     *
     * @param gateId the door's id ({@link GateManager#getGate(int)})
     */
    void gateStateChanged(int gateId);
}
