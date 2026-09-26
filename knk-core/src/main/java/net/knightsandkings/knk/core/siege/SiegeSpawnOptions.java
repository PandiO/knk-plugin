package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeSpawnpoint;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.SpawnChoice;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.SpawnKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A member's spawn options (DESIGN §6.6, MENU_TEMPLATES C.4): held objectives with
 * {@code SpawnWhenHeld} first, then the team's spawnpoints (v2 order). Shared by the chat picker
 * ({@code /siege spawn}), the respawn listener and, in Phase 8b, the {@code siege.spawn-options}
 * content source, so all three agree.
 */
public final class SiegeSpawnOptions {
    private SiegeSpawnOptions() {}

    /**
     * One option.
     *
     * @param available false while the objective is contested (can't be picked; DESIGN §6.6)
     * @param current   this is the member's current choice (or, with no choice, the default spawnpoint)
     */
    public record SpawnOption(SpawnKind kind, int id, String name, KnkLocation location, boolean available, boolean current) {
        public SpawnChoice choice() {
            return new SpawnChoice(kind, id);
        }
    }

    /** Every option for {@code team}, marking {@code current} (null = the default spawnpoint). */
    public static List<SpawnOption> forTeam(KnkSiegeTeam team, SiegeObjectiveBoard board, SpawnChoice current) {
        SpawnChoice effective = current != null ? current : defaultChoice(team).orElse(null);
        List<SpawnOption> options = new ArrayList<>();
        if (board != null) {
            for (ObjectiveState state : board.objectives()) {
                KnkSiegeObjective objective = state.objective();
                if (state.holderTeamId() != team.id() || !objective.spawnWhenHeld()) continue;
                SpawnChoice choice = SpawnChoice.objective(objective.id());
                options.add(new SpawnOption(SpawnKind.OBJECTIVE, objective.id(), objective.name(),
                        objective.captureLocation(), !state.isContested(), choice.equals(effective)));
            }
        }
        for (KnkSiegeSpawnpoint spawnpoint : team.spawnpoints()) {
            SpawnChoice choice = SpawnChoice.spawnpoint(spawnpoint.id());
            options.add(new SpawnOption(SpawnKind.SPAWNPOINT, spawnpoint.id(), spawnpoint.name(),
                    spawnpoint.location(), true, choice.equals(effective)));
        }
        return List.copyOf(options);
    }

    /** The team's default spawnpoint (lowest sortOrder) as a choice. */
    public static Optional<SpawnChoice> defaultChoice(KnkSiegeTeam team) {
        return team.defaultSpawnpoint().map(s -> SpawnChoice.spawnpoint(s.id()));
    }

    /**
     * Is {@code choice} still pickable for {@code team} right now? Spawnpoints must be the team's
     * own; objectives must be held by the team, {@code SpawnWhenHeld}, and not contested.
     */
    public static boolean isAvailable(KnkSiegeTeam team, SiegeObjectiveBoard board, SpawnChoice choice) {
        if (choice == null) return false;
        return forTeam(team, board, null).stream()
                .anyMatch(o -> o.kind() == choice.kind() && o.id() == choice.id() && o.available());
    }

    /**
     * Where the member respawns: their choice while it is still available, else the team's
     * default spawnpoint (DESIGN §6.6: a lost objective falls back to spawnpoint SortOrder 0).
     * A contested objective also falls back, like the picker refuses it.
     */
    public static Optional<SpawnOption> resolveRespawn(KnkSiegeTeam team, SiegeObjectiveBoard board, SpawnChoice choice) {
        List<SpawnOption> options = forTeam(team, board, null);
        if (choice != null) {
            Optional<SpawnOption> chosen = options.stream()
                    .filter(o -> o.kind() == choice.kind() && o.id() == choice.id() && o.available())
                    .findFirst();
            if (chosen.isPresent()) return chosen;
        }
        return options.stream().filter(o -> o.kind() == SpawnKind.SPAWNPOINT).findFirst();
    }

    /** The spawn picker opens only when the team has at least two options (v1 rule, DESIGN §6.5/§6.6). */
    public static boolean pickerWorthOpening(KnkSiegeTeam team, SiegeObjectiveBoard board) {
        return forTeam(team, board, null).size() >= 2;
    }
}
