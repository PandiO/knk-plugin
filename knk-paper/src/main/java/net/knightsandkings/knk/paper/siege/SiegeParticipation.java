package net.knightsandkings.knk.paper.siege;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.minigame.MinigameParticipation;
import net.knightsandkings.knk.core.minigame.MinigameTeleportAttempt;
import net.knightsandkings.knk.core.minigame.Participation;
import net.knightsandkings.knk.core.siege.SiegeCombatRules;
import net.knightsandkings.knk.core.siege.SiegePhase;
import net.knightsandkings.knk.core.teleport.TeleportDenial;

/**
 * Siege as a {@link MinigameParticipation} (arena duels DESIGN §11). The stages follow the lobby phase, so the
 * shared hooks read the same memberships as before the registry:
 * <ul>
 *   <li>any lobby member ({@link SiegeService#lobbyOf}): navigation;</li>
 *   <li>{@link Participation.Stage#hasLeftOpenWorld()} = {@link SiegeService#isParticipant} (HUB, IN_PROGRESS,
 *       ENDING, COOLDOWN): discovery, {@code /hub}, the domain-access respawn;</li>
 *   <li>{@link Participation.Stage#isAway()} = {@link SiegeService#activeLobbyOf} (HUB, IN_PROGRESS): teleports,
 *       {@code /back}, scoreboard, lootbox deliveries;</li>
 *   <li>{@link Participation.Stage#PLAYING} = {@link SiegeService#runningMatchOf} (IN_PROGRESS, on the roster):
 *       the {@code siege} statistics context.</li>
 * </ul>
 */
public final class SiegeParticipation implements MinigameParticipation {

    public static final String ID = "siege";

    private final SiegeService service;
    private final SiegeTeleportRestriction teleports;
    private final Function<UUID, Player> players;

    public SiegeParticipation(SiegeService service) {
        this(service, Bukkit::getPlayer);
    }

    SiegeParticipation(SiegeService service, Function<UUID, Player> players) {
        this.service = Objects.requireNonNull(service, "service");
        this.players = Objects.requireNonNull(players, "players");
        this.teleports = new SiegeTeleportRestriction(id -> service.activeLobbyOf(id).isPresent());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Optional<Participation> participationOf(UUID playerId) {
        return service.lobbyOf(playerId).map(rt -> {
            Optional<SiegeMatch> match = rt.match();
            boolean onRoster = match.map(m -> m.roster().contains(playerId)).orElse(false);
            return new Participation(playerId, ID, match.map(SiegeMatch::matchToken).orElse(null),
                    stageOf(rt.phase(), onRoster));
        });
    }

    /** The shared stage of a member of a lobby in {@code phase}. */
    static Participation.Stage stageOf(SiegePhase phase, boolean onRoster) {
        return switch (phase) {
            case DISABLED, MATCHMAKING -> Participation.Stage.QUEUED;
            case HUB -> Participation.Stage.STAGING;
            case IN_PROGRESS -> onRoster ? Participation.Stage.PLAYING : Participation.Stage.STAGING;
            case ENDING, COOLDOWN -> Participation.Stage.FINISHING;
        };
    }

    @Override
    public boolean allowsCombat(UUID attacker, UUID victim) {
        Player a = players.apply(attacker);
        Player v = players.apply(victim);
        return a != null && v != null && service.allowsCombat(a, v);
    }

    @Override
    public double headshotMultiplier(UUID attacker, UUID victim) {
        Player attackerPlayer = players.apply(attacker);
        Player victimPlayer = players.apply(victim);
        if (attackerPlayer == null || victimPlayer == null) {
            return 1.0;
        }
        SiegeCombatRules.Combatant a = service.combatantOf(attackerPlayer);
        SiegeCombatRules.Combatant v = service.combatantOf(victimPlayer);
        return a != null && v != null && a.lobbyId() == v.lobbyId() ? service.headshotMultiplier(v.lobbyId()) : 1.0;
    }

    @Override
    public boolean releaseForOutage(UUID playerId) {
        return service.releaseForOutage(playerId);
    }

    @Override
    public void resumeAfterOutage() {
        service.resumeAfterOutage();
    }

    @Override
    public Optional<TeleportDenial> teleportRefusal(MinigameTeleportAttempt attempt) {
        return teleports.deny(attempt);
    }

    @Override
    public Set<String> teleportBypassNodes() {
        return teleports.bypassNodes();
    }
}
