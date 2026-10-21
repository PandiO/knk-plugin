package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.duel.DuelCombatRules;
import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.minigame.MinigameParticipation;
import net.knightsandkings.knk.core.minigame.MinigameTeleportAttempt;
import net.knightsandkings.knk.core.minigame.Participation;
import net.knightsandkings.knk.core.teleport.TeleportDenial;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Arena duels as a {@link MinigameParticipation} (DESIGN §11, P0's suggested mapping): a duelist is a member from
 * LOCKING until their gear is back.
 * <ul>
 *   <li>stages: LOCKING, TRAVELLING, WARMUP → {@code STAGING}; BATTLE_COUNTDOWN, FIGHT → {@code PLAYING} (on the
 *       battleground: the {@code duel} statistics context, as P7 expects); ENDING → {@code FINISHING};</li>
 *   <li>{@link #holdsLootDrops}: from LOCKING until the restore, ENDING included (DESIGN §8.4) - lootbox reel items
 *       and other deliveries are handed over afterwards, and opening a lootbox is refused;</li>
 *   <li>combat: the fighting pair of one match (the KNG-11 enchantment safezone exemption);</li>
 *   <li>outage: the match is aborted and the gear comes back at once;</li>
 *   <li>teleports: {@link DuelTeleportRestriction} (DESIGN §8.3).</li>
 * </ul>
 */
public final class DuelParticipation implements MinigameParticipation {

    private final DuelService service;
    private final DuelTeleportRestriction teleports;

    public DuelParticipation(DuelService service) {
        this.service = Objects.requireNonNull(service, "service");
        this.teleports = new DuelTeleportRestriction(service::isParticipating);
    }

    @Override
    public String id() {
        return DuelService.MINIGAME_ID;
    }

    @Override
    public Optional<Participation> participationOf(UUID playerId) {
        if (!service.isParticipating(playerId)) return Optional.empty();
        return service.matchOf(playerId).map(match ->
                new Participation(playerId, DuelService.MINIGAME_ID, match.token().toString(), stageOf(match.phase())));
    }

    static Participation.Stage stageOf(DuelPhase phase) {
        return switch (phase) {
            case BATTLE_COUNTDOWN, FIGHT -> Participation.Stage.PLAYING;
            case ENDING, CLOSED -> Participation.Stage.FINISHING;
            default -> Participation.Stage.STAGING;
        };
    }

    @Override
    public boolean allowsCombat(UUID attacker, UUID victim) {
        return DuelCombatRules.decide(service.combatantOf(attacker), service.combatantOf(victim))
                == DuelCombatRules.Outcome.ALLOW;
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
    public boolean holdsLootDrops(UUID playerId) {
        return service.isParticipating(playerId);
    }

    /**
     * Members keep their board as by default, and so does everyone the duel sidebar is shown to, spectators included
     * (P6, DESIGN §8.7): the registry is the scoreboard predicate.
     */
    @Override
    public boolean keepsOwnScoreboard(UUID playerId) {
        return MinigameParticipation.super.keepsOwnScoreboard(playerId) || service.showsDuelBoard(playerId);
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
