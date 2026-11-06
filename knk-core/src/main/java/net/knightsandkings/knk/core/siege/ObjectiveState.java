package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One objective's live state in a match (DESIGN §7.1–7.3): points and holder.
 * <p>
 * <b>Actor-aware (Siege Survival link S3):</b> a {@link Presence} is a player or an NPC actor with a weight; the
 * pressure of a side is the sum of its actors' weights fed into the same formula, so weight 1 for every player
 * leaves PvP capture identical. An NPC can capture (the closest contributing attacker); the event then carries no
 * player id.
 * <p>
 * <b>Holder-relative:</b> defenders of the objective are the holder team's alliance, attackers every
 * other alliance, whatever the teams are called or which role they have. With recapture enabled the
 * sides swap on every capture.
 * <p>
 * A capture happens in a step that leaves the points at 0 while moving them towards capture
 * ({@code delta > 0}); there is then always a living attacker in the radius to credit. Points pushed
 * to 0 by side-capture pressure alone therefore wait for the next attacker (legacy crashed here:
 * {@code setCaptured} looked for a capturer that might not exist).
 * Not thread-safe: the siege ticker drives it from the main thread.
 */
public final class ObjectiveState {

    /**
     * A living match actor inside the capture radius, with its distance to the capture point (DESIGN §7.2; actor-aware
     * since Siege Survival link S3, PandiO/knk-workspace#41). A player is one actor of weight 1; an NPC actor carries the
     * capture contribution weight of its difficulty. The capture formula sums weights per side, so with every weight 1
     * it counts heads exactly as before.
     *
     * @param actorId  the player's UUID, or the NPC entity's UUID
     * @param teamId   the actor's team
     * @param distance distance to the capture point
     * @param kind     player or NPC
     * @param weight   the actor's share of one capturing player (1 = one player); 0 or negative: present but no pressure
     */
    public record Presence(UUID actorId, int teamId, double distance, SiegeActorKind kind, double weight) {
        public Presence {
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(kind, "kind");
            if (Double.isNaN(weight) || Double.isInfinite(weight)) {
                throw new IllegalArgumentException("weight must be finite: " + weight);
            }
        }

        /** A player of weight 1: the PvP presence as it always was. */
        public Presence(UUID playerId, int teamId, double distance) {
            this(playerId, teamId, distance, SiegeActorKind.PLAYER, 1.0);
        }

        /** An NPC actor with the difficulty's capture contribution weight (1 = one player). */
        public static Presence npc(UUID entityId, int teamId, double distance, double weight) {
            return new Presence(entityId, teamId, distance, SiegeActorKind.NPC, weight);
        }

        /** The player behind this presence, or null for an NPC actor. */
        public UUID playerId() {
            return kind == SiegeActorKind.PLAYER ? actorId : null;
        }

        public boolean isPlayer() {
            return kind == SiegeActorKind.PLAYER;
        }

        /** Whether this actor adds pressure: a positive weight. */
        public boolean contributes() {
            return weight > 0;
        }
    }

    /**
     * A capture: the holder changed to the capturer's team.
     *
     * @param capturerId     the capturing player, or null when an NPC actor captured (link S3): roster credit,
     *                       the reward, the result's capturer and the announcement all treat null as "nobody"
     * @param captureNumber  1 for the objective's first capture in this match, 2 for the next, ...
     * @param rewardEligible the capturer's first capture of this objective: the capture reward
     *                       counts once per participant per objective (DESIGN §7.6); never for an NPC
     * @param objectiveFinal the objective is now captured for good (recapture off, or instant victory)
     * @param capturerKind   player or NPC
     * @param capturerActorId the capturing actor (the player, or the NPC entity); never null
     */
    public record CaptureEvent(
            int objectiveId,
            boolean instantVictory,
            int previousHolderTeamId,
            int newHolderTeamId,
            UUID capturerId,
            int captureNumber,
            boolean rewardEligible,
            boolean objectiveFinal,
            SiegeActorKind capturerKind,
            UUID capturerActorId
    ) {
        /** A capture by a player (the pre-S3 shape). */
        public CaptureEvent(int objectiveId, boolean instantVictory, int previousHolderTeamId, int newHolderTeamId,
                            UUID capturerId, int captureNumber, boolean rewardEligible, boolean objectiveFinal) {
            this(objectiveId, instantVictory, previousHolderTeamId, newHolderTeamId, capturerId, captureNumber,
                    rewardEligible, objectiveFinal, SiegeActorKind.PLAYER, capturerId);
        }

        public boolean firstCapture() {
            return captureNumber == 1;
        }

        /** Captured by an NPC actor: there is no player to credit. */
        public boolean capturedByNpc() {
            return capturerKind == SiegeActorKind.NPC;
        }
    }

    /** What one second of capture did to this objective. */
    public record StepResult(
            int objectiveId,
            int pointsBefore,
            int pointsAfter,
            int attackers,
            int defenders,
            int delta,
            boolean contested,
            Optional<CaptureEvent> capture
    ) {}

    private static final Comparator<Presence> CLOSEST_FIRST = Comparator.comparingDouble(Presence::distance);

    private final KnkSiegeObjective objective;
    private final boolean allowRecapture;
    private final Set<UUID> rewardedCapturers = new HashSet<>();
    private final List<CaptureEvent> captures = new ArrayList<>();
    private int holderTeamId;
    private int points;
    private boolean capturedFinal;
    private boolean contested;

    public ObjectiveState(KnkSiegeObjective objective, boolean allowRecapture) {
        this.objective = Objects.requireNonNull(objective, "objective");
        this.allowRecapture = allowRecapture;
        this.holderTeamId = objective.initialHolderTeamId();
        this.points = objective.capturePoints();
    }

    /**
     * One second of capture with the living members inside the radius (DESIGN §7.2).
     * Members of teams the resolver doesn't know are ignored.
     */
    public StepResult step(List<Presence> present, AllianceResolver alliances, CaptureCalculator calculator) {
        int before = points;
        if (capturedFinal) {
            contested = false;
            return new StepResult(objective.id(), before, before, 0, 0, 0, false, Optional.empty());
        }

        int holderAlliance = alliances.allianceOf(holderTeamId);
        List<Presence> attackers = new ArrayList<>();
        int defenders = 0;
        double attackWeight = 0;
        double defendWeight = 0;
        for (Presence p : present) {
            if (!alliances.knows(p.teamId()) || !p.contributes()) continue;
            if (alliances.allianceOf(p.teamId()) == holderAlliance) {
                defenders++;
                defendWeight += p.weight();
            } else {
                attackers.add(p);
                attackWeight += p.weight();
            }
        }

        boolean iv = objective.instantVictory();
        // Weighted sums (link S3): every player weighs 1, so a PvP match steps exactly as before.
        int delta = calculator.delta(attackWeight, defendWeight, iv);
        points = calculator.applyStep(points, objective.capturePoints(), delta);
        contested = !attackers.isEmpty();

        Optional<CaptureEvent> capture = Optional.empty();
        if (delta > 0 && points == 0 && !attackers.isEmpty()) {
            Presence capturer = attackers.stream().min(CLOSEST_FIRST).orElseThrow();
            capture = Optional.of(capture(capturer));
        }
        return new StepResult(objective.id(), before, points, attackers.size(), defenders, delta, contested, capture);
    }

    private CaptureEvent capture(Presence capturer) {
        int previousHolder = holderTeamId;
        holderTeamId = capturer.teamId();
        // An NPC capturer earns nothing (DESIGN §3b/§4: Survival keeps its own counters).
        boolean rewardEligible = capturer.isPlayer() && rewardedCapturers.add(capturer.playerId());
        boolean isFinal = objective.instantVictory() || !allowRecapture;
        if (isFinal) {
            capturedFinal = true;
        } else {
            // D5: the new holder starts defending a full objective; the old holder's alliance attacks.
            points = objective.capturePoints();
        }
        CaptureEvent event = new CaptureEvent(objective.id(), objective.instantVictory(), previousHolder,
                holderTeamId, capturer.playerId(), captures.size() + 1, rewardEligible, isFinal,
                capturer.kind(), capturer.actorId());
        captures.add(event);
        return event;
    }

    /**
     * Side-capture pressure (DESIGN §7.4): lowers the points, at most to 0, without capturing.
     * No effect on an objective that is captured for good.
     *
     * @return the points actually removed
     */
    public int applySideCaptureReduction(int amount) {
        if (capturedFinal || amount <= 0) return 0;
        int before = points;
        points = Math.max(0, points - amount);
        return before - points;
    }

    public KnkSiegeObjective objective() {
        return objective;
    }

    public int objectiveId() {
        return objective.id();
    }

    public int holderTeamId() {
        return holderTeamId;
    }

    public int initialHolderTeamId() {
        return objective.initialHolderTeamId();
    }

    public int points() {
        return points;
    }

    /** Captured for good: stops scoring (recapture off, or an instant-victory objective). */
    public boolean isCapturedFinal() {
        return capturedFinal;
    }

    public boolean hasBeenCaptured() {
        return !captures.isEmpty();
    }

    /** A living attacker was inside the radius in the last step (DESIGN §7.2 "contested"). */
    public boolean isContested() {
        return contested;
    }

    /** Every capture in this match, oldest first (one {@code SiegeMatchObjectiveResult} each). */
    public List<CaptureEvent> captures() {
        return List.copyOf(captures);
    }

    /** Percentage captured, 0–100, as legacy {@code getCapturePercentage} showed it. */
    public int capturePercent() {
        int capturePoints = objective.capturePoints();
        if (capturePoints <= 0) return 100;
        return 100 - (int) (100L * points / capturePoints);
    }
}
