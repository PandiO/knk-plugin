package net.knightsandkings.knk.core.navigation.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.navigation.destinations.Candidate;
import net.knightsandkings.knk.core.navigation.destinations.NavigationContext;
import net.knightsandkings.knk.core.navigation.destinations.Provider;
import net.knightsandkings.knk.core.siege.AllianceResolver;
import net.knightsandkings.knk.core.siege.SiegeCompassTargets;
import net.knightsandkings.knk.core.siege.SiegeCompassTargets.Target;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard;

/**
 * A running siege match's objectives and team spawn as a provider of the destination engine (DESIGN § 4.1 scope 60,
 * § 5, phase 3, PandiO/knk-workspace#69): {@link SiegeCompassTargets} for the player's team, in the compass's order
 * (each target's position in that order is its priority), with the gate approach of #176 applied when the list is
 * built - an objective to capture behind a closed gate of the player's own alliance leads to the gate. Keys stay
 * {@code objective:<id>} and {@code spawn:<id>}; the type words are {@code objective} and {@code spawn}, so
 * {@code objective:Keep Gate} and {@code objective:#7} work in a match too (§ 3.1). Not cached (E18): the board and
 * the gate states change during the match. Main thread.
 */
public final class SiegeMatchProvider implements Provider {

    public static final String ID = "siege-match";

    /**
     * What the provider needs of the player's running match.
     *
     * @param scenario  the match's scenario
     * @param board     the objective board now
     * @param alliances who is allied with whom
     * @param team      the player's team id
     */
    public record Match(KnkSiegeScenario scenario, SiegeObjectiveBoard board, AllianceResolver alliances, int team) {
        public Match {
            Objects.requireNonNull(scenario, "scenario");
            Objects.requireNonNull(board, "board");
            Objects.requireNonNull(alliances, "alliances");
        }
    }

    /** Where to head instead of an objective's capture point for a member of a team (#176); empty: the point. */
    @FunctionalInterface
    public interface GateApproach {
        GateApproach NONE = (objective, team) -> Optional.empty();

        Optional<KnkLocation> approach(KnkSiegeObjective objective, KnkSiegeTeam team);
    }

    private final Function<UUID, Optional<Match>> matchOf;
    private final GateApproach gateApproach;
    private final Function<Target, String> detail;

    /**
     * @param matchOf      the player's running match with their team; empty outside a match or without a team
     * @param gateApproach the gate approach of #176
     * @param detail       the note shown with a target ("defend, under attack, 62% held")
     */
    public SiegeMatchProvider(Function<UUID, Optional<Match>> matchOf, GateApproach gateApproach,
                              Function<Target, String> detail) {
        this.matchOf = Objects.requireNonNull(matchOf, "matchOf");
        this.gateApproach = gateApproach == null ? GateApproach.NONE : gateApproach;
        this.detail = detail == null ? t -> null : detail;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Candidate> candidates(NavigationContext ctx) {
        Optional<Match> match = matchOf.apply(ctx.player());
        if (match.isEmpty()) {
            return List.of();
        }
        Match m = match.get();
        Optional<KnkSiegeTeam> ownTeam = m.scenario().team(m.team());
        List<Target> targets = SiegeCompassTargets.forTeam(m.scenario(), m.board(), m.alliances(), m.team());
        List<Candidate> out = new ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            KnkLocation at = target.location();
            if (target.kind() == SiegeCompassTargets.Kind.CAPTURE && ownTeam.isPresent()) {
                Optional<KnkSiegeObjective> objective = objectiveOf(m.scenario(), target.key());
                if (objective.isPresent()) {
                    at = gateApproach.approach(objective.get(), ownTeam.get()).orElse(at);
                }
            }
            if (at == null || at.x() == null || at.y() == null || at.z() == null) {
                continue;
            }
            String world = at.world() == null ? ctx.world() : at.world();
            out.add(new Candidate(target.key(), target.label(), typeWordOf(target.key()), List.of(), idOf(target.key()),
                detail.apply(target), i, ID, new Candidate.Place.Point(world, at.x(), at.y(), at.z())));
        }
        return out;
    }

    /** {@code objective} for {@code objective:<id>}, {@code spawn} for {@code spawn:<id>}. */
    static String typeWordOf(String key) {
        int colon = key == null ? -1 : key.indexOf(':');
        return colon > 0 ? key.substring(0, colon) : "objective";
    }

    /** The number after the colon, or {@link Candidate#NO_ID}. */
    static int idOf(String key) {
        int colon = key == null ? -1 : key.indexOf(':');
        if (colon < 0) {
            return Candidate.NO_ID;
        }
        try {
            return Integer.parseInt(key.substring(colon + 1));
        } catch (NumberFormatException e) {
            return Candidate.NO_ID;
        }
    }

    /** The objective a target's key ({@code objective:<id>}) names. */
    static Optional<KnkSiegeObjective> objectiveOf(KnkSiegeScenario scenario, String key) {
        if (key == null || !key.startsWith("objective:")) {
            return Optional.empty();
        }
        int id = idOf(key);
        return scenario.objectives().stream().filter(o -> o.id() == id).findFirst();
    }
}
