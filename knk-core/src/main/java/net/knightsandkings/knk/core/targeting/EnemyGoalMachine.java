package net.knightsandkings.knk.core.targeting;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The per-actor goal state machine of a siege attacker (Siege Survival DESIGN §4 "default NPC route logic", NPC
 * platform §5a/§6 failure policy): select a target, approach it, then act on it (attack a player, hit a gate, stand
 * in a capture ring). Navigation failures are retried a bounded number of times; a target that stays unreachable is
 * put aside for a cool-down and the machine picks another one. Bukkit-free: the adapter observes the world each
 * tick and executes the returned action.
 */
public final class EnemyGoalMachine {

    public enum State { SELECTING, APPROACHING, ATTACKING, BREACHING, CAPTURING, IDLE }

    /** What the adapter should do this tick. */
    public sealed interface Action {
        /** Walk to the target's position (the adapter re-paths as it likes; the target id tells it when it changed). */
        record MoveTo(TargetCandidate target) implements Action { }
        /** Engage the player (the adapter sets the mob's attack target; the combat goals do the rest). */
        record AttackPlayer(TargetCandidate target) implements Action { }
        /** At the gate's stand point: hit the door on the adapter's timer. */
        record HitGate(TargetCandidate target) implements Action { }
        /** Inside the capture ring: stay (presence does the capture). */
        record Capture(TargetCandidate target) implements Action { }
        /** Nothing eligible or reachable: wait where it stands. */
        record Idle() implements Action { }
    }

    /**
     * What the adapter saw this tick.
     *
     * @param arrived           the actor is at its current target (within the kind's arrive distance; false without one)
     * @param navigationBlocked the mover reported it cannot reach the current target since the last observation
     * @param pursueDirectly    last-enemies rule: players are candidates whatever the aggro radius / leash
     */
    public record Observation(double x, double y, double z, List<TargetCandidate> candidates, boolean arrived,
                              boolean navigationBlocked, boolean pursueDirectly, long nowMillis) {
        public Observation {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        }
    }

    public record Decision(State state, Action action, Optional<TargetCandidate> target, boolean targetChanged) {
        public Decision {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(action, "action");
            target = target == null ? Optional.empty() : target;
        }
    }

    public static final int DEFAULT_MAX_BLOCKED_RETRIES = 3;
    public static final long DEFAULT_BLOCKED_COOLDOWN_MILLIS = 20_000L;

    private final TargetSelector selector;
    private final int maxBlockedRetries;
    private final long blockedCooldownMillis;
    private final Map<String, Long> blockedUntil = new HashMap<>();

    private State state = State.SELECTING;
    private TargetCommitment commitment;
    private TargetCandidate target;
    private int blockedRetries;
    private double anchorX;
    private double anchorY;
    private double anchorZ;

    public EnemyGoalMachine(TargetPolicy policy, double spawnX, double spawnY, double spawnZ) {
        this(policy, spawnX, spawnY, spawnZ, DEFAULT_MAX_BLOCKED_RETRIES, DEFAULT_BLOCKED_COOLDOWN_MILLIS);
    }

    public EnemyGoalMachine(TargetPolicy policy, double spawnX, double spawnY, double spawnZ, int maxBlockedRetries,
                            long blockedCooldownMillis) {
        this.selector = new TargetSelector(policy);
        if (maxBlockedRetries < 0) throw new IllegalArgumentException("maxBlockedRetries must be >= 0");
        if (blockedCooldownMillis < 0) throw new IllegalArgumentException("blockedCooldownMillis must be >= 0");
        this.maxBlockedRetries = maxBlockedRetries;
        this.blockedCooldownMillis = blockedCooldownMillis;
        this.anchorX = spawnX;
        this.anchorY = spawnY;
        this.anchorZ = spawnZ;
    }

    public State state() {
        return state;
    }

    public Optional<TargetCandidate> target() {
        return Optional.ofNullable(target);
    }

    public Optional<TargetCommitment> commitment() {
        return Optional.ofNullable(commitment);
    }

    public int blockedRetries() {
        return blockedRetries;
    }

    /** Whether a target id is in its blocked cool-down. */
    public boolean isBlocked(String targetId, long nowMillis) {
        Long until = blockedUntil.get(targetId);
        return until != null && until > nowMillis;
    }

    /** The leash anchor: the last structural (non-player) target, else the spawn point. */
    public double[] anchor() {
        return new double[] {anchorX, anchorY, anchorZ};
    }

    public Decision observe(Observation o) {
        // Navigation failure (§6 failure policy): bounded retries toward the same target, then it is put aside.
        if (o.navigationBlocked() && target != null && !o.arrived()) {
            blockedRetries++;
            if (blockedRetries > maxBlockedRetries) {
                blockedUntil.put(target.id(), o.nowMillis() + blockedCooldownMillis);
                commitment = null;
                target = null;
                blockedRetries = 0;
            }
        }
        List<TargetCandidate> candidates = o.candidates().stream()
            .map(c -> isBlocked(c.id(), o.nowMillis()) && c.eligible()
                ? TargetCandidate.denied(c.id(), c.kind(), c.x(), c.y(), c.z(), "blocked") : c)
            .toList();
        Optional<TargetSelector.Scored> chosen = selector.select(candidates, Optional.ofNullable(commitment),
            o.x(), o.y(), o.z(), anchorX, anchorY, anchorZ, o.pursueDirectly(), o.nowMillis());
        if (chosen.isEmpty()) {
            boolean changed = target != null;
            commitment = null;
            target = null;
            blockedRetries = 0;
            state = State.IDLE;
            return new Decision(state, new Action.Idle(), Optional.empty(), changed);
        }
        TargetCandidate next = chosen.get().candidate();
        boolean changed = target == null || !target.id().equals(next.id());
        if (changed) {
            commitment = new TargetCommitment(next.id(), next.kind(), chosen.get().score(), o.nowMillis());
            blockedRetries = 0;
        }
        target = next; // the same target's position may have moved (a player): follow it without a new commitment
        if (next.kind() != TargetKind.PLAYER) {
            anchorX = next.x();
            anchorY = next.y();
            anchorZ = next.z();
        }
        boolean arrived = o.arrived() && !changed;
        Action action;
        switch (next.kind()) {
            case PLAYER -> {
                state = State.ATTACKING;
                action = new Action.AttackPlayer(next);
            }
            case GATE -> {
                state = arrived ? State.BREACHING : State.APPROACHING;
                action = arrived ? new Action.HitGate(next) : new Action.MoveTo(next);
            }
            case OBJECTIVE, DISTRACTOR -> {
                state = arrived ? State.CAPTURING : State.APPROACHING;
                action = arrived ? new Action.Capture(next) : new Action.MoveTo(next);
            }
            default -> throw new IllegalStateException("unknown kind " + next.kind());
        }
        return new Decision(state, action, Optional.of(next), changed);
    }

    /** Forgets the current target (the adapter respawned the actor elsewhere or the target vanished for good). */
    public void release() {
        commitment = null;
        target = null;
        blockedRetries = 0;
        state = State.SELECTING;
    }
}
