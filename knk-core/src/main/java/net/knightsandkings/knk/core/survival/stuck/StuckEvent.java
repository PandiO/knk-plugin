package net.knightsandkings.knk.core.survival.stuck;

import net.knightsandkings.knk.core.survival.SurvivalPoint;

import java.util.Optional;

/**
 * Stuck-enemy telemetry (DESIGN §3c: "log every stuck respawn, glow and system kill with the enemy, its last
 * position and target"). The adapter logs or records these; the core only produces them.
 *
 * @param kind         what happened
 * @param enemyId      the planned enemy's match-unique id
 * @param round        its round
 * @param atMillis     when
 * @param lastPosition the enemy's last reported position (empty when never sampled)
 * @param target       its last reported target (empty when none)
 * @param respawnCount how often it has been respawned so far (after this event, for respawns)
 */
public record StuckEvent(Kind kind, long enemyId, int round, long atMillis, Optional<SurvivalPoint> lastPosition,
                         Optional<String> target, int respawnCount) {

    public enum Kind {
        /** Removed and respawned as the same planned enemy nearer the players (rule 1). */
        STUCK_RESPAWN,
        /** Among the last K enemies: glowing and direct pursuit (rule 2). */
        GLOW,
        /** Stuck again after R respawns: killed by the system (rule 3). */
        RESPAWN_LIMIT_KILL,
        /** Round backstop T after the last planned spawn: killed by the system (rule 4). */
        BACKSTOP_KILL
    }

    public StuckEvent {
        if (kind == null) throw new IllegalArgumentException("kind is required");
        lastPosition = lastPosition == null ? Optional.empty() : lastPosition;
        target = target == null ? Optional.empty() : target;
    }

    /** True for the two system-kill kinds: dead for completion, not a kill (no reward, currency or statistics). */
    public boolean isSystemKill() {
        return kind == Kind.RESPAWN_LIMIT_KILL || kind == Kind.BACKSTOP_KILL;
    }
}
