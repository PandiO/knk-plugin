package net.knightsandkings.knk.paper.minigame;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.minigame.MinigameRegistry;
import net.knightsandkings.knk.core.minigame.MinigameTeleportAttempt;
import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.paper.teleport.BackDeathExclusion;
import net.knightsandkings.knk.paper.teleport.TeleportCheck;
import net.knightsandkings.knk.paper.teleport.TeleportRestriction;

/**
 * The teleport engine's guard for every minigame (teleport DESIGN §4 D8/D9, arena duels DESIGN §11): asks the
 * {@link MinigameRegistry}, so the engine depends on no minigame. Registered once at startup; minigames that start
 * later are asked from then on.
 */
public final class MinigameTeleportRestriction implements TeleportRestriction {

    private final MinigameRegistry minigames;

    public MinigameTeleportRestriction(MinigameRegistry minigames) {
        this.minigames = Objects.requireNonNull(minigames, "minigames");
    }

    @Override
    public Optional<TeleportDenial> deny(TeleportCheck check) {
        return minigames.teleportRefusal(attemptOf(check));
    }

    @Override
    public Set<String> bypassNodes() {
        return minigames.teleportBypassNodes();
    }

    /** No {@code /back} after a death in a minigame that excludes it (teleport developer decision Q5). */
    public BackDeathExclusion backDeathExclusion() {
        return player -> minigames.excludesBackOnDeath(player.getUniqueId());
    }

    /** The engine's check as a minigame sees it. */
    public static MinigameTeleportAttempt attemptOf(TeleportCheck check) {
        Player subject = check.subject();
        Player visited = check.visited();
        boolean movesSelf = check.actor() instanceof Player actor && actor.getUniqueId().equals(subject.getUniqueId());
        return new MinigameTeleportAttempt(subject.getUniqueId(), subject.getName(), check.kind(), movesSelf,
                visited != null ? visited.getUniqueId() : null, visited != null ? visited.getName() : null,
                check::hasBypass);
    }
}
