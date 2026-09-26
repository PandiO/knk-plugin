package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.siege.ObjectiveState.CaptureEvent;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.ObjectiveState.StepResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * All objectives of one running match: the per-second capture step across objectives, side-capture
 * pressure (DESIGN §7.4) and the holdings the win rules read (§7.5).
 * <p>
 * Within a step every objective is stepped first (in scenario order); side-capture pressure from
 * this step's captures is applied afterwards, so it shows in the next step. Only an objective's
 * <b>first</b> capture in the match applies pressure: no stacking from recapture ping-pong and no
 * refund when it is taken back.
 * Not thread-safe: the siege ticker drives it from the main thread.
 */
public final class SiegeObjectiveBoard {

    /** One second of capture across the board. */
    public record BoardStep(
            List<StepResult> steps,
            List<CaptureEvent> captures,
            Map<Integer, Integer> sideCaptureReductions,
            Optional<CaptureEvent> instantVictoryCapture
    ) {}

    private final KnkSiegeScenario scenario;
    private final CaptureCalculator calculator;
    private final AllianceResolver alliances;
    private final Map<Integer, ObjectiveState> states = new LinkedHashMap<>();
    private CaptureEvent instantVictoryCapture;

    public SiegeObjectiveBoard(KnkSiegeScenario scenario, CaptureCalculator calculator, AllianceResolver alliances) {
        this.scenario = scenario;
        this.calculator = calculator;
        this.alliances = alliances;
        for (KnkSiegeObjective objective : scenario.objectives()) {
            states.put(objective.id(), new ObjectiveState(objective, scenario.allowRecapture()));
        }
    }

    /**
     * @param presenceByObjective living members inside each objective's radius; objectives missing
     *                            from the map have nobody present
     */
    public BoardStep step(Map<Integer, List<Presence>> presenceByObjective) {
        List<StepResult> steps = new ArrayList<>();
        List<CaptureEvent> captures = new ArrayList<>();
        for (ObjectiveState state : states.values()) {
            StepResult result = state.step(presenceByObjective.getOrDefault(state.objectiveId(), List.of()), alliances, calculator);
            steps.add(result);
            result.capture().ifPresent(captures::add);
        }

        Map<Integer, Integer> reductions = new TreeMap<>();
        Optional<CaptureEvent> ivCapture = Optional.empty();
        for (CaptureEvent capture : captures) {
            if (capture.instantVictory()) {
                if (ivCapture.isEmpty()) ivCapture = Optional.of(capture);
            } else if (capture.firstCapture()) {
                applySideCapturePressure(reductions);
            }
        }
        if (instantVictoryCapture == null) ivCapture.ifPresent(c -> instantVictoryCapture = c);
        return new BoardStep(List.copyOf(steps), List.copyOf(captures), Map.copyOf(reductions), ivCapture);
    }

    private void applySideCapturePressure(Map<Integer, Integer> reductions) {
        int nonIvCount = scenario.nonInstantVictoryObjectiveCount();
        for (ObjectiveState state : states.values()) {
            if (!state.objective().instantVictory() || state.isCapturedFinal()) continue;
            int amount = calculator.sideCaptureReduction(state.objective().capturePoints(), nonIvCount);
            int removed = state.applySideCaptureReduction(amount);
            if (removed > 0) reductions.merge(state.objectiveId(), removed, Integer::sum);
        }
    }

    public List<ObjectiveState> objectives() {
        return List.copyOf(states.values());
    }

    public ObjectiveState objective(int objectiveId) {
        ObjectiveState state = states.get(objectiveId);
        if (state == null) throw new IllegalArgumentException("Unknown objective " + objectiveId);
        return state;
    }

    public boolean hasInstantVictoryObjectives() {
        return states.values().stream().anyMatch(s -> s.objective().instantVictory());
    }

    /** The first instant-victory capture of this match, if any (it ends the match). */
    public Optional<CaptureEvent> instantVictoryCapture() {
        return Optional.ofNullable(instantVictoryCapture);
    }

    /** Objectives currently held per alliance group (alliances holding none are absent). */
    public Map<Integer, Integer> heldCountsByAlliance() {
        return countByAlliance(false);
    }

    /** Instant-victory objectives currently held per alliance group. */
    public Map<Integer, Integer> heldInstantVictoryCountsByAlliance() {
        return countByAlliance(true);
    }

    private Map<Integer, Integer> countByAlliance(boolean instantVictoryOnly) {
        Map<Integer, Integer> counts = new TreeMap<>();
        for (ObjectiveState state : states.values()) {
            if (instantVictoryOnly && !state.objective().instantVictory()) continue;
            counts.merge(alliances.allianceOf(state.holderTeamId()), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Held objectives a team may spawn at (DESIGN §6.6): held by that team, {@code spawnWhenHeld},
     * and not contested (checked again at click time by the caller).
     */
    public List<ObjectiveState> spawnableObjectives(int teamId) {
        return states.values().stream()
                .filter(s -> s.holderTeamId() == teamId && s.objective().spawnWhenHeld() && !s.isContested())
                .toList();
    }
}
