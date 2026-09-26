package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.siege.ObjectiveState.CaptureEvent;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.ObjectiveState.StepResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * Who is working which objective, second by second (smoke test 2026-09-26: capture feedback). Turns
 * the board's per-second {@link StepResult}s into:
 * <ul>
 *   <li>{@link Started}: a side <b>began</b> attacking (points moving towards capture) or defending
 *       (the holder's alliance pushing the points back up) an objective. Announce it once: the same
 *       activity on the same objective isn't announced again within {@code reannounceSeconds}, so a
 *       fight flickering on the edge of the ring doesn't spam.</li>
 *   <li>{@link Ongoing}: every second someone attacks or defends, with the players doing it, for
 *       continuous feedback.</li>
 * </ul>
 * A second with a capture reports nothing for that objective (the capture announcement covers it) and
 * resets its re-announce window, so the other side's first attack after a capture is announced.
 * Pure; main thread only.
 */
public final class CaptureActivityTracker {

    public enum Activity {
        NONE,
        /** Attackers outweigh defenders: the points move towards capture. */
        ATTACKING,
        /** Defenders outweigh attackers on a partly captured objective: the points recover. */
        DEFENDING
    }

    /**
     * A side began working an objective this second.
     *
     * @param holderTeamId the objective's holder (the defending side)
     * @param actors       the players doing it, closest to the capture point first
     * @param actorTeamIds their teams, in the same order, without duplicates
     * @param retake       attacking only: the attackers' alliance held this objective earlier in the match
     * @param capturePercent how far the objective is captured, 0-100
     */
    public record Started(int objectiveId, Activity activity, int holderTeamId, List<UUID> actors,
                          List<Integer> actorTeamIds, boolean retake, int capturePercent) {}

    /** Someone is attacking or defending the objective this second. */
    public record Ongoing(int objectiveId, Activity activity, List<UUID> actors, int capturePercent) {}

    public record Update(List<Started> started, List<Ongoing> ongoing) {}

    public static final int DEFAULT_REANNOUNCE_SECONDS = 15;

    private static final Comparator<Presence> CLOSEST_FIRST = Comparator.comparingDouble(Presence::distance);

    private final int reannounceSeconds;
    private final Map<Integer, Activity> current = new HashMap<>();
    private final Map<Integer, EnumMap<Activity, Long>> lastAnnounced = new HashMap<>();
    private long second;

    public CaptureActivityTracker() {
        this(DEFAULT_REANNOUNCE_SECONDS);
    }

    public CaptureActivityTracker(int reannounceSeconds) {
        this.reannounceSeconds = Math.max(0, reannounceSeconds);
    }

    /**
     * @param steps     this second's board step results
     * @param presence  the living members inside each objective's radius, as the board got them
     * @param states    the objective states after the step (holder, points, capture history)
     * @param alliances the match's alliances
     */
    public Update step(List<StepResult> steps, Map<Integer, List<Presence>> presence,
                       IntFunction<ObjectiveState> states, AllianceResolver alliances) {
        second++;
        List<Started> started = new ArrayList<>();
        List<Ongoing> ongoing = new ArrayList<>();
        for (StepResult r : steps) {
            int id = r.objectiveId();
            if (r.capture().isPresent()) {
                // The sides swapped (or the objective is done): the next attack is a new event.
                current.put(id, Activity.NONE);
                lastAnnounced.remove(id);
                continue;
            }
            ObjectiveState state = states.apply(id);
            int holderAlliance = alliances.allianceOf(state.holderTeamId());
            Activity activity;
            if (r.delta() > 0) {
                activity = Activity.ATTACKING;
            } else if (r.delta() < 0 && r.pointsBefore() < state.objective().capturePoints()) {
                activity = Activity.DEFENDING;
            } else {
                activity = Activity.NONE;
            }
            Activity previous = current.getOrDefault(id, Activity.NONE);
            current.put(id, activity);
            if (activity == Activity.NONE) continue;

            List<Presence> actorsPresent = presence.getOrDefault(id, List.of()).stream()
                    .filter(p -> alliances.knows(p.teamId()))
                    .filter(p -> (alliances.allianceOf(p.teamId()) == holderAlliance) == (activity == Activity.DEFENDING))
                    .sorted(CLOSEST_FIRST)
                    .toList();
            List<UUID> actors = actorsPresent.stream().map(Presence::playerId).toList();
            int percent = state.capturePercent();
            ongoing.add(new Ongoing(id, activity, actors, percent));

            if (activity == previous) continue;
            EnumMap<Activity, Long> announced = lastAnnounced.computeIfAbsent(id, k -> new EnumMap<>(Activity.class));
            Long last = announced.get(activity);
            if (last != null && second - last < reannounceSeconds) continue;
            announced.put(activity, second);

            Set<Integer> teams = new LinkedHashSet<>();
            actorsPresent.forEach(p -> teams.add(p.teamId()));
            boolean retake = activity == Activity.ATTACKING && heldBefore(state, teams, alliances);
            started.add(new Started(id, activity, state.holderTeamId(), actors, List.copyOf(teams), retake, percent));
        }
        return new Update(List.copyOf(started), List.copyOf(ongoing));
    }

    /** Did one of these teams' alliances hold the objective at the start or before one of its captures? */
    private static boolean heldBefore(ObjectiveState state, Set<Integer> teams, AllianceResolver alliances) {
        Set<Integer> attackerAlliances = new LinkedHashSet<>();
        teams.forEach(t -> attackerAlliances.add(alliances.allianceOf(t)));
        List<Integer> formerHolders = new ArrayList<>();
        formerHolders.add(state.initialHolderTeamId());
        for (CaptureEvent c : state.captures()) formerHolders.add(c.previousHolderTeamId());
        return formerHolders.stream().anyMatch(t -> alliances.knows(t) && attackerAlliances.contains(alliances.allianceOf(t)));
    }
}
