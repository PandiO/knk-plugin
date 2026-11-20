package net.knightsandkings.knk.core.siege.economy;

import net.knightsandkings.knk.core.siege.AllianceResolver;
import net.knightsandkings.knk.core.siege.ObjectiveState;
import net.knightsandkings.knk.core.siege.ObjectiveState.CaptureEvent;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.ObjectiveState.StepResult;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard.BoardStep;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/**
 * Turns PvP Siege events into siege point awards (siege shop DESIGN § 5.2, P13–P17, W34, W50, W60;
 * PandiO/knk-workspace#866). One calculator per match: it keeps the fractions of gate damage and capture progress that
 * have not yet made a whole point, and who was active since the last trickle. Bukkit-free; main thread only. The
 * {@link SiegePointsEconomy} applies the awards to the ledger.
 * <p>
 * Teams: {@code teamOf} answers a member's team id, or a negative number for a non-member; teams the
 * {@link AllianceResolver} doesn't know count as non-members.
 */
public final class SiegePointsEarnCalculator {

    /** One award before it reaches the ledger. {@code multiplier} is the kill-streak multiplier (1 for every other source). */
    public record Award(UUID player, SiegePointsSource source, long amount, double multiplier) {
        public Award {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(source, "source");
        }
    }

    /**
     * A member died.
     *
     * @param killer       the player credited with the killing blow, null for a death without one (environment)
     * @param killerStreak the killer's streak including this kill (the roster's {@code killStreak} after it)
     * @param assisters    who damaged the victim enough in the window ({@link AssistTracker#assistersOnDeath})
     */
    public record Death(UUID victim, UUID killer, int killerStreak, List<UUID> assisters) {
        public Death {
            Objects.requireNonNull(victim, "victim");
            assisters = assisters == null ? List.of() : List.copyOf(assisters);
        }
    }

    /** Travel in one trickle interval that counts as activity on its own (P17; standing at spawn is not). */
    public static final double TRICKLE_MIN_TRAVEL_BLOCKS = 10.0;

    private static final double EPSILON = 1e-9;

    private final SiegePointsSettings settings;
    private final Map<UUID, Double> gateCarry = new HashMap<>();
    private final Map<UUID, Double> captureCarry = new HashMap<>();
    private final Set<UUID> activeSinceTrickle = new LinkedHashSet<>();
    private final Map<UUID, Double> travelSinceTrickle = new HashMap<>();
    private long secondsTicked;

    public SiegePointsEarnCalculator(SiegePointsSettings settings) {
        this.settings = settings == null ? SiegePointsSettings.defaults() : settings;
    }

    public SiegePointsSettings settings() {
        return settings;
    }

    // ==================== Kills, assists, deaths (P13, P14, W34, W60) ====================

    /** The multiplier of the n-th kill of a streak: {@code min(1 + step × (n − 1), cap)}; 1 for n ≤ 1. */
    public double streakMultiplier(int streak) {
        if (streak <= 1) return 1.0;
        return Math.min(1.0 + settings.streakStep() * (streak - 1), settings.streakCap());
    }

    /** Points for the n-th kill of a streak, rounded to whole points. */
    public long killPoints(int streak) {
        return Math.round(settings.pointsPerKill() * streakMultiplier(streak));
    }

    /**
     * A member's death (DESIGN § 5.2.1): the killer earns the kill with the streak multiplier, the victim the death
     * amount, every enemy assister other than the killer the assist amount. A suicide or a friendly-fire kill pays
     * nobody; a death without a killing member (environment) pays the victim and the assisters.
     */
    public List<Award> death(Death death, ToIntFunction<UUID> teamOf, AllianceResolver alliances) {
        Integer victimAlliance = allianceOf(death.victim(), teamOf, alliances);
        if (victimAlliance == null) return List.of();
        UUID killer = death.killer();
        if (killer != null && killer.equals(death.victim())) return List.of();
        Integer killerAlliance = killer == null ? null : allianceOf(killer, teamOf, alliances);
        if (killerAlliance != null && killerAlliance.equals(victimAlliance)) return List.of();
        if (killerAlliance == null) killer = null; // a non-member's kill counts as the environment

        List<Award> awards = new ArrayList<>();
        if (killer != null) {
            double multiplier = streakMultiplier(death.killerStreak());
            add(awards, killer, SiegePointsSource.KILL, Math.round(settings.pointsPerKill() * multiplier), multiplier);
            activeSinceTrickle.add(killer);
        }
        add(awards, death.victim(), SiegePointsSource.DEATH, settings.pointsPerDeath(), 1.0);
        for (UUID assister : death.assisters()) {
            if (assister.equals(killer) || assister.equals(death.victim())) continue;
            Integer alliance = allianceOf(assister, teamOf, alliances);
            if (alliance == null || alliance.equals(victimAlliance)) continue;
            add(awards, assister, SiegePointsSource.ASSIST, settings.pointsPerAssist(), 1.0);
        }
        return List.copyOf(awards);
    }

    // ==================== Gate damage ====================

    /** Effective gate HP the member took off one of the match's gates: 1 point per {@code gateHpPerPoint} HP, fractions kept. */
    public List<Award> gateDamage(UUID player, double loss) {
        if (player == null || !(loss > 0) || Double.isInfinite(loss) || settings.gateHpPerPoint() <= 0) return List.of();
        activeSinceTrickle.add(player);
        long whole = takeWhole(gateCarry, player, loss / settings.gateHpPerPoint());
        List<Award> awards = new ArrayList<>(1);
        add(awards, player, SiegePointsSource.GATE_DAMAGE, whole, 1.0);
        return awards;
    }

    // ==================== Capture progress and captures (P16, W50) ====================

    /**
     * One second of the objective board: every objective that moved towards a side pays each living member of that
     * side inside its radius {@code pointsPerCapturePercent} per percent moved (attackers taking it and defenders
     * pushing it back alike; side-capture pressure pays nobody), and a capture pays {@code pointsPerCapture} to each
     * member of the capturing side inside the radius. Members in a radius count as active for the trickle.
     *
     * @param presence the living members inside each objective's radius, as the board got them
     * @param states   the objective states after the step
     */
    public List<Award> boardStep(BoardStep step, Map<Integer, List<Presence>> presence,
                                 IntFunction<ObjectiveState> states, AllianceResolver alliances) {
        Map<UUID, Long> progress = new LinkedHashMap<>();
        Map<UUID, Long> captures = new LinkedHashMap<>();
        for (StepResult r : step.steps()) {
            List<Presence> present = presence.getOrDefault(r.objectiveId(), List.of());
            present.forEach(p -> activeSinceTrickle.add(p.playerId()));
            ObjectiveState state = states.apply(r.objectiveId());
            if (state == null || r.delta() == 0) continue;
            int capturePoints = state.objective().capturePoints();
            if (capturePoints <= 0) continue;
            CaptureEvent capture = r.capture().orElse(null);
            int moved;
            int holderBefore;
            boolean towardsCapture = r.delta() > 0;
            if (towardsCapture) {
                moved = r.pointsBefore() - (capture != null ? 0 : r.pointsAfter());
                holderBefore = capture != null ? capture.previousHolderTeamId() : state.holderTeamId();
            } else {
                moved = r.pointsAfter() - r.pointsBefore();
                holderBefore = state.holderTeamId();
            }
            if (moved <= 0 || !alliances.knows(holderBefore)) continue;
            int holderAlliance = alliances.allianceOf(holderBefore);
            double percent = 100.0 * moved / capturePoints;
            for (Presence p : present) {
                if (!alliances.knows(p.teamId())) continue;
                boolean defender = alliances.allianceOf(p.teamId()) == holderAlliance;
                if (defender == towardsCapture) continue; // only the side the points moved towards
                long whole = takeWhole(captureCarry, p.playerId(), settings.pointsPerCapturePercent() * percent);
                if (whole > 0) progress.merge(p.playerId(), whole, Long::sum);
            }
        }
        for (CaptureEvent capture : step.captures()) {
            if (!alliances.knows(capture.newHolderTeamId())) continue;
            int capturingAlliance = alliances.allianceOf(capture.newHolderTeamId());
            for (Presence p : presence.getOrDefault(capture.objectiveId(), List.of())) {
                if (alliances.knows(p.teamId()) && alliances.allianceOf(p.teamId()) == capturingAlliance) {
                    captures.merge(p.playerId(), (long) settings.pointsPerCapture(), Long::sum);
                }
            }
        }
        List<Award> awards = new ArrayList<>();
        progress.forEach((player, amount) -> add(awards, player, SiegePointsSource.CAPTURE_PROGRESS, amount, 1.0));
        captures.forEach((player, amount) -> add(awards, player, SiegePointsSource.CAPTURE, amount, 1.0));
        return List.copyOf(awards);
    }

    // ==================== Trickle (P17) ====================

    /** The member fought, damaged a gate or worked an objective: active for this trickle interval. */
    public void markActive(UUID player) {
        if (player != null) activeSinceTrickle.add(player);
    }

    /** The member travelled this far (once-a-second samples); {@link #TRICKLE_MIN_TRAVEL_BLOCKS} in an interval is activity. */
    public void recordTravel(UUID player, double blocks) {
        if (player == null || !(blocks > 0) || Double.isInfinite(blocks)) return;
        double total = travelSinceTrickle.merge(player, blocks, Double::sum);
        if (total + EPSILON >= TRICKLE_MIN_TRAVEL_BLOCKS) activeSinceTrickle.add(player);
    }

    /**
     * One second of the match. Every {@code trickleIntervalSeconds} seconds each of {@code members} who was active in
     * the interval earns the trickle; the activity starts over for the next interval.
     */
    public List<Award> secondTicked(Collection<UUID> members) {
        secondsTicked++;
        if (secondsTicked % settings.trickleIntervalSeconds() != 0) return List.of();
        List<Award> awards = new ArrayList<>();
        for (UUID member : members) {
            if (activeSinceTrickle.contains(member)) add(awards, member, SiegePointsSource.TRICKLE, settings.trickleAmount(), 1.0);
        }
        activeSinceTrickle.clear();
        travelSinceTrickle.clear();
        return List.copyOf(awards);
    }

    /** The member left: their fractions and activity go. */
    public void forget(UUID player) {
        gateCarry.remove(player);
        captureCarry.remove(player);
        activeSinceTrickle.remove(player);
        travelSinceTrickle.remove(player);
    }

    // ==================== Helpers ====================

    private static Integer allianceOf(UUID player, ToIntFunction<UUID> teamOf, AllianceResolver alliances) {
        if (player == null) return null;
        int team = teamOf.applyAsInt(player);
        if (team < 0 || !alliances.knows(team)) return null;
        return alliances.allianceOf(team);
    }

    private static long takeWhole(Map<UUID, Double> carry, UUID player, double amount) {
        if (!(amount > 0) || Double.isInfinite(amount)) return 0;
        double total = carry.getOrDefault(player, 0.0) + amount;
        long whole = (long) Math.floor(total + EPSILON);
        carry.put(player, Math.max(0, total - whole));
        return whole;
    }

    private static void add(List<Award> awards, UUID player, SiegePointsSource source, long amount, double multiplier) {
        if (amount > 0) awards.add(new Award(player, source, amount, multiplier));
    }
}
