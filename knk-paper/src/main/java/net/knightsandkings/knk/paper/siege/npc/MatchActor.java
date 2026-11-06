package net.knightsandkings.knk.paper.siege.npc;

import java.util.Objects;
import java.util.UUID;

/**
 * A mob registered as an actor of a match's NPC team (Siege Survival link S3, PandiO/knk-workspace#41).
 *
 * @param entityId      the Bukkit entity
 * @param lobbyId       the siege lobby whose match the actor belongs to
 * @param teamId        the NPC team (a scenario team)
 * @param captureWeight the difficulty's capture contribution per NPC (1 = one player; 0 = present, no pressure)
 */
public record MatchActor(UUID entityId, int lobbyId, int teamId, double captureWeight) {

    public MatchActor {
        Objects.requireNonNull(entityId, "entityId");
        if (Double.isNaN(captureWeight) || Double.isInfinite(captureWeight) || captureWeight < 0) {
            throw new IllegalArgumentException("captureWeight must be finite and not negative: " + captureWeight);
        }
    }
}
