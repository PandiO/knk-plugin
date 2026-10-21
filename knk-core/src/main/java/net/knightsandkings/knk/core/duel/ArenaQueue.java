package net.knightsandkings.knk.core.duel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One arena's queue (DESIGN §5.2, D13): matches that were accepted but aren't on a battleground yet.
 *
 * <ul>
 *   <li><b>Cap</b>: {@code MaxQueuedMatches} counts admitted matches (LOCKING, TRAVELLING, WARMUP).
 *       {@link #hasRoom()} is the advisory check at invite; {@link #admit} is the binding check at accept,
 *       before lock-in.</li>
 *   <li><b>FIFO</b>: a match whose warmup is over joins the waiting line ({@link #readyForBattleground});
 *       {@link #advance} hands free battlegrounds out in the order matches became ready.</li>
 *   <li><b>Wait timeout</b>: a match that waited {@code MaxWaitSeconds} without a battleground is timed
 *       out (the runtime cancels it with {@link DuelEndReason#NO_BATTLEGROUND} and refunds).</li>
 * </ul>
 * A match leaves the queue when it gets a battleground or ends ({@link #remove}); it gives its
 * battleground back with {@link #releaseBattleground}. Time is the server tick. Not thread-safe.
 */
public final class ArenaQueue {

    /** A match got a battleground: call {@link DuelStateMachine#battlegroundAssigned}. */
    public record Assignment(String matchToken, int battlegroundId) {}

    /** What one {@link #advance} decided. */
    public record Update(List<Assignment> assigned, List<String> timedOut) {
        public Update {
            assigned = List.copyOf(assigned);
            timedOut = List.copyOf(timedOut);
        }

        public boolean isEmpty() {
            return assigned.isEmpty() && timedOut.isEmpty();
        }
    }

    private final BattlegroundAllocator allocator;
    private int maxQueuedMatches;
    private long maxWaitTicks;
    /** Admitted matches; value = the tick they became ready for a battleground, or null while in warmup. */
    private final Map<String, Long> queued = new LinkedHashMap<>();

    public ArenaQueue(BattlegroundAllocator allocator, int maxQueuedMatches, int maxWaitSeconds) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        configure(maxQueuedMatches, maxWaitSeconds);
    }

    /** Arena settings changed. Matches already admitted stay admitted, even above a lowered cap. */
    public void configure(int maxQueuedMatches, int maxWaitSeconds) {
        if (maxQueuedMatches < 0 || maxWaitSeconds < 0) throw new IllegalArgumentException("must be >= 0");
        this.maxQueuedMatches = maxQueuedMatches;
        this.maxWaitTicks = DuelTicks.ofSeconds(maxWaitSeconds);
    }

    public BattlegroundAllocator allocator() {
        return allocator;
    }

    /** Advisory, at invite: "The Arena is full (3/3 waiting), try again shortly." */
    public boolean hasRoom() {
        return queued.size() < maxQueuedMatches;
    }

    /** Binding, at accept before lock-in: false (nothing admitted) when the queue is full. */
    public boolean admit(String matchToken) {
        Objects.requireNonNull(matchToken, "matchToken");
        if (queued.containsKey(matchToken)) return true;
        if (!hasRoom()) return false;
        queued.put(matchToken, null);
        return true;
    }

    /** Warmup is over ({@link DuelEffect.AwaitBattleground}): the match joins the waiting line. */
    public void readyForBattleground(String matchToken, long nowTick) {
        if (queued.containsKey(matchToken) && queued.get(matchToken) == null) queued.put(matchToken, nowTick);
    }

    /**
     * Hands free battlegrounds to waiting matches, first ready first served, and times out matches that
     * waited {@code MaxWaitSeconds}. Assigned and timed-out matches leave the queue.
     */
    public Update advance(long nowTick) {
        List<Map.Entry<String, Long>> waiting = new ArrayList<>();
        for (Map.Entry<String, Long> e : queued.entrySet()) {
            if (e.getValue() != null) waiting.add(e);
        }
        waiting.sort(Map.Entry.comparingByValue()); // stable: equal ticks keep admission order
        List<Assignment> assigned = new ArrayList<>();
        List<String> timedOut = new ArrayList<>();
        for (Map.Entry<String, Long> e : waiting) {
            String token = e.getKey();
            Optional<Integer> battleground = allocator.hasFree() ? allocator.allocate(token) : Optional.empty();
            if (battleground.isPresent()) {
                assigned.add(new Assignment(token, battleground.get()));
            } else if (nowTick - e.getValue() >= maxWaitTicks) {
                timedOut.add(token);
            }
        }
        for (Assignment a : assigned) queued.remove(a.matchToken());
        for (String token : timedOut) queued.remove(token);
        return new Update(assigned, timedOut);
    }

    /** The match ended before it got a battleground (or was refused): it no longer counts. */
    public void remove(String matchToken) {
        queued.remove(matchToken);
    }

    /** The match is over: its battleground is free again. Returns the battleground, or null. */
    public Integer releaseBattleground(String matchToken) {
        remove(matchToken);
        return allocator.release(matchToken);
    }

    /** 1-based place in the waiting line ("#2 in line"), or 0 when the match isn't waiting. */
    public int positionOf(String matchToken) {
        Long ready = queued.get(matchToken);
        if (ready == null) return 0;
        int position = 1;
        for (Iterator<Map.Entry<String, Long>> it = queued.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> e = it.next();
            if (e.getKey().equals(matchToken) || e.getValue() == null) continue;
            if (e.getValue() < ready) position++;
            else if (e.getValue().equals(ready) && admittedBefore(e.getKey(), matchToken)) position++;
        }
        return position;
    }

    /** Admitted matches not yet on a battleground ("2/3 waiting"). */
    public int queuedCount() {
        return queued.size();
    }

    public int maxQueuedMatches() {
        return maxQueuedMatches;
    }

    public boolean contains(String matchToken) {
        return queued.containsKey(matchToken);
    }

    private boolean admittedBefore(String a, String b) {
        for (String token : queued.keySet()) {
            if (token.equals(a)) return true;
            if (token.equals(b)) return false;
        }
        return false;
    }
}
