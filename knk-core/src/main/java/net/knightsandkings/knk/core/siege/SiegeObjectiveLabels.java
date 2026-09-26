package net.knightsandkings.knk.core.siege;

/**
 * What an objective's floating label says to one viewer (developer request 2026-09-26: the holding
 * side must see clearly that the objective is theirs, not "captured 40%" as if they still had to take
 * it). The Paper presenter turns this into text; one label per alliance plus a neutral one for
 * everyone outside the match.
 */
public final class SiegeObjectiveLabels {
    private SiegeObjectiveLabels() {}

    public enum Relation {
        /** The viewer's alliance holds it: defend. */
        OURS,
        /** Another alliance holds it: capture. */
        ENEMY,
        /** The viewer isn't in the match. */
        NEUTRAL
    }

    public enum Status {
        /** Full points, nobody attacking. */
        SECURE,
        /** Points below full, nobody attacking right now. */
        WEAKENED,
        /** A living attacker is inside the radius. */
        UNDER_ATTACK,
        /** Captured for good (recapture off, or instant victory). */
        FINAL
    }

    /** @param capturePercent how far the attackers have got, 0–100 ({@link ObjectiveState#capturePercent()}) */
    public record Label(Relation relation, Status status, int capturePercent) {}

    /**
     * @param viewerAlliance the viewer's alliance group, or null for a non-member
     */
    public static Label forViewer(ObjectiveState state, Integer viewerAlliance, AllianceResolver alliances) {
        Relation relation;
        if (viewerAlliance == null || !alliances.knows(state.holderTeamId())) relation = Relation.NEUTRAL;
        else relation = alliances.allianceOf(state.holderTeamId()) == viewerAlliance ? Relation.OURS : Relation.ENEMY;

        Status status;
        if (state.isCapturedFinal()) status = Status.FINAL;
        else if (state.isContested()) status = Status.UNDER_ATTACK;
        else if (state.points() < state.objective().capturePoints()) status = Status.WEAKENED;
        else status = Status.SECURE;
        return new Label(relation, status, state.capturePercent());
    }
}
