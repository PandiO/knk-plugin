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

    /** A living match member inside the capture radius, with their distance to the capture point. */
    public record Presence(UUID playerId, int teamId, double distance) {
        public Presence {
            Objects.requireNonNull(playerId, "playerId");
        }
    }

    /**
     * A capture: the holder changed to the capturer's team.
     *
     * @param captureNumber  1 for the objective's first capture in this match, 2 for the next, ...
     * @param rewardEligible the capturer's first capture of this objective: the capture reward
     *                       counts once per participant per objective (DESIGN §7.6)
     * @param objectiveFinal the objective is now captured for good (recapture off, or instant victory)
     */
    public record CaptureEvent(
            int objectiveId,
            boolean instantVictory,
            int previousHolderTeamId,
            int newHolderTeamId,
            UUID capturerId,
            int captureNumber,
            boolean rewardEligible,
            boolean objectiveFinal
    ) {
        public boolean firstCapture() {
            return captureNumber == 1;
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
        for (Presence p : present) {
            if (!alliances.knows(p.teamId())) continue;
            if (alliances.allianceOf(p.teamId()) == holderAlliance) defenders++;
            else attackers.add(p);
        }

        boolean iv = objective.instantVictory();
        int delta = calculator.delta(attackers.size(), defenders, iv);
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
        boolean rewardEligible = rewardedCapturers.add(capturer.playerId());
        boolean isFinal = objective.instantVictory() || !allowRecapture;
        if (isFinal) {
            capturedFinal = true;
        } else {
            // D5: the new holder starts defending a full objective; the old holder's alliance attacks.
            points = objective.capturePoints();
        }
        CaptureEvent event = new CaptureEvent(objective.id(), objective.instantVictory(), previousHolder,
                holderTeamId, capturer.playerId(), captures.size() + 1, rewardEligible, isFinal);
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
