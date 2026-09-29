package net.knightsandkings.knk.core.teleport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The teleport engine's warmup state machine (docs/specs/teleport/DESIGN.md §3.4 step 2, §3.4.1):
 * at most one pending teleport per player; starting a new one replaces (and returns) the old one;
 * a pending teleport either becomes due and is taken for commit, or is cancelled. Taking or
 * cancelling removes it, so a warmup can never both commit and cancel, and a second tick can't
 * commit it twice.
 * <p>
 * Fixes v1's {@code TeleportDelay}: v1 removed from its map while iterating its key set (a
 * {@code ConcurrentModificationException} as soon as two warmups ran at once), and one missing user
 * aborted every other player's tick. Time is passed in, so the whole machine is unit-testable.
 *
 * @param <P> what the engine keeps per pending teleport (its plan)
 */
public final class WarmupBook<P> {

    public record Pending<P>(UUID subject, P payload, long startedAtMillis, long dueAtMillis) {

        /** Whole seconds until the teleport happens, rounded up; 0 once due. */
        public int secondsLeft(long nowMillis) {
            long left = dueAtMillis - nowMillis;
            return left <= 0 ? 0 : (int) ((left + 999) / 1000);
        }

        public boolean isDue(long nowMillis) {
            return nowMillis >= dueAtMillis;
        }
    }

    private final Map<UUID, Pending<P>> pending = new ConcurrentHashMap<>();

    /**
     * Start a warmup of {@code seconds} for {@code subject}.
     *
     * @return the warmup it replaced, if the subject already had one
     */
    public Optional<Pending<P>> start(UUID subject, P payload, long nowMillis, int seconds) {
        Pending<P> entry = new Pending<>(subject, payload, nowMillis, nowMillis + Math.max(0, seconds) * 1000L);
        return Optional.ofNullable(pending.put(subject, entry));
    }

    /** Stop the subject's warmup, returning it if there was one. */
    public Optional<Pending<P>> cancel(UUID subject) {
        return Optional.ofNullable(pending.remove(subject));
    }

    public Optional<Pending<P>> get(UUID subject) {
        return Optional.ofNullable(pending.get(subject));
    }

    public boolean isWarmingUp(UUID subject) {
        return pending.containsKey(subject);
    }

    /** Remove and return every warmup that is due at {@code nowMillis}. */
    public List<Pending<P>> takeDue(long nowMillis) {
        List<Pending<P>> due = new ArrayList<>();
        for (Pending<P> entry : List.copyOf(pending.values())) {
            if (entry.isDue(nowMillis) && pending.remove(entry.subject(), entry)) {
                due.add(entry);
            }
        }
        return due;
    }

    /** Every running warmup (a copy, safe to iterate while cancelling). */
    public List<Pending<P>> snapshot() {
        return List.copyOf(pending.values());
    }

    public int size() {
        return pending.size();
    }

    /**
     * Whether a move from one block position to another cancels a warmup: only a change of block
     * does, so looking around doesn't (v1 cancelled on any {@code PlayerMoveEvent}, head rotation
     * included - DESIGN §4 D3).
     */
    public static boolean changesBlock(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return fromX != toX || fromY != toY || fromZ != toZ;
    }
}
