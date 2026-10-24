package net.knightsandkings.knk.core.menu;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alpha cross-play finding 2026-10-24: Bukkit-free bookkeeping for a delayed
 * menu-to-menu switch for Bedrock players (Geyser + Floodgate).
 * <p>
 * Geyser turns a Java inventory switch into "close the Bedrock container form,
 * open a new one". When the server opens the next menu in the same tick as the
 * click, the Bedrock client's close of the previous form can arrive after the
 * new open, and Paper then closes the new menu - the player sees it flash and
 * vanish. knk-paper therefore closes the current menu, waits a few ticks and
 * only then opens the next one; this class tracks that per player with a token:
 * <ol>
 *   <li>{@link #begin} - the old menu was just closed; returns the token the
 *       delayed open must present. A newer {@code begin} supersedes it (the
 *       delayed open of a double click is dropped).</li>
 *   <li>{@link #opened} - the delayed open ran, if its token is still current.
 *       For {@code graceTicks} afterwards a client close is still treated as
 *       the stale close of the previous form.</li>
 *   <li>{@link #onClientClose} - what to do with a close the client sent.</li>
 * </ol>
 * Ticks are whatever monotonic tick clock the caller uses consistently (the
 * menu engine's {@code System.currentTimeMillis() / 50}).
 */
public final class MenuTransitionTracker {

    /** What a client-sent inventory close means for the player's menu. */
    public enum CloseVerdict {
        /** No transition in progress: a real close, handle it as usual. */
        NORMAL,
        /** The previous menu's close arriving while the next menu is not open yet: ignore it. */
        STALE_IGNORE,
        /** The previous menu's close arrived just after the next menu opened and closed it: show it again. */
        STALE_REOPEN
    }

    private static final class Transition {
        final long token;
        boolean opened;
        long graceUntilTick;

        Transition(long token) {
            this.token = token;
        }
    }

    private final Map<UUID, Transition> transitions = new ConcurrentHashMap<>();
    private long nextToken = 1;

    /** Starts a transition for {@code playerId}, superseding any earlier one, and returns its token. */
    public synchronized long begin(UUID playerId) {
        long token = nextToken++;
        transitions.put(playerId, new Transition(token));
        return token;
    }

    /**
     * The delayed open for {@code token} is about to run. Returns false (the open must be dropped)
     * when a newer transition or a {@link #cancel} replaced it; otherwise opens the grace window.
     */
    public synchronized boolean opened(UUID playerId, long token, long nowTick, int graceTicks) {
        Transition transition = transitions.get(playerId);
        if (transition == null || transition.token != token || transition.opened) {
            return false;
        }
        if (graceTicks <= 0) {
            transitions.remove(playerId);
            return true;
        }
        transition.opened = true;
        transition.graceUntilTick = nowTick + graceTicks;
        return true;
    }

    /**
     * A close the client sent at {@code nowTick}. Before the delayed open it is the previous
     * menu's close ({@link CloseVerdict#STALE_IGNORE}); within the grace window after the open
     * it is reported once as {@link CloseVerdict#STALE_REOPEN} and the transition ends, so a
     * second close is a real one. Otherwise {@link CloseVerdict#NORMAL}.
     */
    public synchronized CloseVerdict onClientClose(UUID playerId, long nowTick) {
        Transition transition = transitions.get(playerId);
        if (transition == null) {
            return CloseVerdict.NORMAL;
        }
        if (!transition.opened) {
            return CloseVerdict.STALE_IGNORE;
        }
        transitions.remove(playerId);
        return nowTick <= transition.graceUntilTick ? CloseVerdict.STALE_REOPEN : CloseVerdict.NORMAL;
    }

    /** Whether a delayed open is still pending for {@code playerId}. */
    public synchronized boolean isPending(UUID playerId) {
        Transition transition = transitions.get(playerId);
        return transition != null && !transition.opened;
    }

    /** Drops {@code playerId}'s transition (quit, or the menu was closed for good). */
    public synchronized void cancel(UUID playerId) {
        transitions.remove(playerId);
    }
}
