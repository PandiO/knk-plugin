package net.knightsandkings.knk.core.siege;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Places kept for members who disconnected (quit, or a server kick such as the flying kick) from a round
 * at the hub or in a running match (alpha test run 1, PandiO/knk-workspace#150; DESIGN §13 Q5 "keep the
 * slot for N seconds"). Coming back within the window with the same round still running puts them back
 * on their team; the runtime holds back the match API's {@code participantLeft} until the slot
 * {@link #expire expires}, so a member who came back is present at the end (rewards, no loss).
 * <p>
 * A window of zero turns this off (the MVP rule: a disconnect is a leave). Main thread only.
 */
public final class SiegeRejoinSlots {

    /**
     * @param teamId the team they were on (the split's team at the hub, the roster's in a match); null at the
     *               hub before the team split
     * @param userId their user id, null when it was never known
     * @param leftAt when they disconnected - also the {@code leftAt} reported when the slot expires
     * @param duringMatch they left the running match (a match participant); false at the hub, before the start
     */
    public record Slot(UUID playerId, int lobbyId, String matchToken, Integer teamId, Integer userId, Instant leftAt,
                       boolean duringMatch) {
        public Slot {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(matchToken, "matchToken");
            Objects.requireNonNull(leftAt, "leftAt");
        }
    }

    private final Duration window;
    private final Map<UUID, Slot> slots = new LinkedHashMap<>();

    /** @param window how long a slot is kept; zero or negative turns rejoining off */
    public SiegeRejoinSlots(Duration window) {
        this.window = window == null || window.isNegative() ? Duration.ZERO : window;
    }

    public boolean isEnabled() {
        return !window.isZero();
    }

    public Duration window() {
        return window;
    }

    /** Keeps a slot (replacing an older one of the same player). False, and nothing kept, when turned off. */
    public boolean hold(Slot slot) {
        if (!isEnabled()) return false;
        slots.put(slot.playerId(), slot);
        return true;
    }

    /**
     * The player's slot when it belongs to the round {@code matchToken} of lobby {@code lobbyId} and is still
     * within the window at {@code now}; it is removed. Otherwise nothing changes (an expired slot waits for
     * {@link #expire}, one of an older round for {@link #dropLobby}).
     */
    public Optional<Slot> claim(UUID playerId, int lobbyId, String matchToken, Instant now) {
        Slot slot = slots.get(playerId);
        if (slot == null || slot.lobbyId() != lobbyId || !slot.matchToken().equals(matchToken) || isExpired(slot, now)) {
            return Optional.empty();
        }
        slots.remove(playerId);
        return Optional.of(slot);
    }

    /** The player's slot, if any (expired or not). */
    public Optional<Slot> slotOf(UUID playerId) {
        return Optional.ofNullable(slots.get(playerId));
    }

    /** Removes and returns every slot whose window has passed at {@code now}, in the order they were kept. */
    public List<Slot> expire(Instant now) {
        List<Slot> expired = new ArrayList<>();
        for (Iterator<Slot> it = slots.values().iterator(); it.hasNext(); ) {
            Slot slot = it.next();
            if (isExpired(slot, now)) {
                expired.add(slot);
                it.remove();
            }
        }
        return expired;
    }

    /** The lobby's round is over: removes and returns its slots. */
    public List<Slot> dropLobby(int lobbyId) {
        List<Slot> dropped = new ArrayList<>();
        for (Iterator<Slot> it = slots.values().iterator(); it.hasNext(); ) {
            Slot slot = it.next();
            if (slot.lobbyId() == lobbyId) {
                dropped.add(slot);
                it.remove();
            }
        }
        return dropped;
    }

    /** Removes the player's slot (they joined something else, or an admin took them out). */
    public Optional<Slot> forget(UUID playerId) {
        return Optional.ofNullable(slots.remove(playerId));
    }

    public int size() {
        return slots.size();
    }

    private boolean isExpired(Slot slot, Instant now) {
        return !now.isBefore(slot.leftAt().plus(window));
    }
}
