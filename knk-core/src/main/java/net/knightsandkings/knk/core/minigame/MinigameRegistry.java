package net.knightsandkings.knk.core.minigame;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.statistics.StatisticsContext;
import net.knightsandkings.knk.core.teleport.TeleportDenial;

/**
 * Every minigame's {@link MinigameParticipation}, composed (arena duels DESIGN §11). The plugin creates one at
 * startup, before any hook that reads it, and each minigame registers itself when its runtime starts; until then
 * (or when a minigame failed to start) nobody is a member, as the old null checks on the siege service did.
 * <p>
 * A player is a member of at most one minigame at a time (the minigames' join rules refuse a second one), so the
 * questions about one player are answered by the first minigame that knows them. Thread-safe.
 */
public final class MinigameRegistry {

    private static final Logger LOGGER = Logger.getLogger(MinigameRegistry.class.getName());

    private final List<MinigameParticipation> minigames = new CopyOnWriteArrayList<>();

    /** Adds a minigame. Its id must be unique. */
    public void register(MinigameParticipation minigame) {
        Objects.requireNonNull(minigame, "minigame");
        String id = Objects.requireNonNull(minigame.id(), "minigame id");
        synchronized (minigames) {
            if (find(id).isPresent()) {
                throw new IllegalStateException("A minigame '" + id + "' is already registered");
            }
            minigames.add(minigame);
        }
    }

    public void unregister(MinigameParticipation minigame) {
        minigames.remove(minigame);
    }

    public Optional<MinigameParticipation> find(String id) {
        return minigames.stream().filter(m -> m.id().equals(id)).findFirst();
    }

    public List<MinigameParticipation> minigames() {
        return List.copyOf(minigames);
    }

    // ==================== membership ====================

    public Optional<Participation> participationOf(UUID playerId) {
        if (playerId == null) return Optional.empty();
        for (MinigameParticipation minigame : minigames) {
            Optional<Participation> found = minigame.participationOf(playerId);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    /** A member of any minigame, at any stage (navigation leaves them out). */
    public boolean isMember(UUID playerId) {
        return participationOf(playerId).isPresent();
    }

    /** {@link Participation.Stage#hasLeftOpenWorld()}: discovery, {@code /hub}, the domain-access respawn. */
    public boolean hasLeftOpenWorld(UUID playerId) {
        return participationOf(playerId).map(p -> p.stage().hasLeftOpenWorld()).orElse(false);
    }

    /** {@link Participation.Stage#isAway()}. */
    public boolean isAway(UUID playerId) {
        return participationOf(playerId).map(p -> p.stage().isAway()).orElse(false);
    }

    /** The minigame's context while the player plays a match ({@code siege}, {@code duel}), else empty. */
    public Optional<StatisticsContext> statisticsContextOf(UUID playerId) {
        return participationOf(playerId)
                .filter(p -> p.stage() == Participation.Stage.PLAYING)
                .map(p -> StatisticsContext.of(p.minigame()));
    }

    /** Why {@code /hub} refuses this player ("You are in a siege."), or empty. */
    public Optional<String> hubRefusal(UUID playerId) {
        return participationOf(playerId)
                .filter(p -> p.stage().hasLeftOpenWorld())
                .map(p -> "You are in a " + p.minigame() + ".");
    }

    // ==================== hooks ====================

    public boolean allowsCombat(UUID attacker, UUID victim) {
        if (attacker == null || victim == null) return false;
        for (MinigameParticipation minigame : minigames) {
            if (minigame.allowsCombat(attacker, victim)) return true;
        }
        return false;
    }

    public double headshotMultiplier(UUID attacker, UUID victim) {
        if (attacker == null || victim == null) return 1.0;
        for (MinigameParticipation minigame : minigames) {
            double multiplier = minigame.headshotMultiplier(attacker, victim);
            if (multiplier != 1.0) return multiplier;
        }
        return 1.0;
    }

    /** Asks every minigame (each may hold the player); true only when all of them let go. */
    public boolean releaseForOutage(UUID playerId) {
        boolean free = true;
        for (MinigameParticipation minigame : minigames) {
            free &= minigame.releaseForOutage(playerId);
        }
        return free;
    }

    public void resumeAfterOutage() {
        for (MinigameParticipation minigame : minigames) {
            try {
                minigame.resumeAfterOutage();
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "Resuming minigame '" + minigame.id() + "' after the outage failed", e);
            }
        }
    }

    public boolean holdsLootDrops(UUID playerId) {
        if (playerId == null) return false;
        return minigames.stream().anyMatch(m -> m.holdsLootDrops(playerId));
    }

    public boolean keepsOwnScoreboard(UUID playerId) {
        if (playerId == null) return false;
        return minigames.stream().anyMatch(m -> m.keepsOwnScoreboard(playerId));
    }

    public boolean excludesBackOnDeath(UUID playerId) {
        if (playerId == null) return false;
        return minigames.stream().anyMatch(m -> m.excludesBackOnDeath(playerId));
    }

    /** The first minigame's refusal, in registration order. */
    public Optional<TeleportDenial> teleportRefusal(MinigameTeleportAttempt attempt) {
        for (MinigameParticipation minigame : minigames) {
            Optional<TeleportDenial> denial = minigame.teleportRefusal(attempt);
            if (denial.isPresent()) return denial;
        }
        return Optional.empty();
    }

    public Set<String> teleportBypassNodes() {
        Set<String> nodes = new LinkedHashSet<>();
        minigames.forEach(m -> nodes.addAll(m.teleportBypassNodes()));
        return Set.copyOf(nodes);
    }
}
