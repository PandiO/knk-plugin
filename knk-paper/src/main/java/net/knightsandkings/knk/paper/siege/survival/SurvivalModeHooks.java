package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.siege.SiegeScenarioRules;
import net.knightsandkings.knk.core.siege.WinResolver;
import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.SiegeMatch;
import net.knightsandkings.knk.paper.siege.SiegeModeHooks;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Survival's answers to {@link SiegeModeHooks} (DESIGN §3): a scenario with a (ready) survival configuration starts
 * with the survival minimum (S37: 1), is untimed (no timer-expiry defender victory), puts every human on the human
 * team (the empty NPC team can't forfeit the match), holds no rejoin slot (disconnect = leave), plays without the
 * side-capture reduction (S51), and its result comes from the survival end reason (S8 replaces the PvP result).
 * Lobbies whose scenario has no survival configuration get the PvP defaults.
 */
public final class SurvivalModeHooks implements SiegeModeHooks {

    private final SurvivalConfigurationCache cache;
    private final Function<Integer, Optional<SurvivalMatch>> matchOfLobby;
    private final Function<Integer, Optional<SurvivalConfiguration>> frozenOfLobby;
    private final Logger logger;

    public SurvivalModeHooks(SurvivalConfigurationCache cache, Function<Integer, Optional<SurvivalMatch>> matchOfLobby,
                             Function<Integer, Optional<SurvivalConfiguration>> frozenOfLobby, Logger logger) {
        this.cache = cache;
        this.matchOfLobby = matchOfLobby;
        this.frozenOfLobby = frozenOfLobby;
        this.logger = logger;
    }

    /** The configuration a lobby's scenario would play: the lobby-level one when fetched, else the scenario-level one. */
    Optional<SurvivalConfiguration> configurationFor(int lobbyId, KnkSiegeScenario scenario) {
        if (scenario == null) return Optional.empty();
        Optional<SurvivalConfiguration> frozen = frozenOfLobby.apply(lobbyId).filter(c -> c.scenarioId() == scenario.id());
        return frozen.isPresent() ? frozen : cache.get(scenario.id());
    }

    private boolean isSurvival(SiegeLobbyRuntime lobby) {
        if (matchOfLobby.apply(lobby.id()).isPresent()) return true;
        return lobby.drawnScenario().map(s -> configurationFor(lobby.id(), s).isPresent()).orElse(false);
    }

    @Override
    public SiegeScenarioRules scenarioRules(int lobbyId) {
        return new SiegeScenarioRules() {
            @Override
            public int playersMin(KnkSiegeScenario scenario) {
                return configurationFor(lobbyId, scenario).map(SurvivalConfiguration::minPlayers).orElse(scenario.playersMin());
            }

            @Override
            public boolean timed(KnkSiegeScenario scenario) {
                return configurationFor(lobbyId, scenario).isEmpty();
            }

            @Override
            public boolean startable(KnkSiegeScenario scenario) {
                Optional<SurvivalConfiguration> config = configurationFor(lobbyId, scenario);
                if (config.isEmpty() || config.get().readiness().ready()) return true;
                if (logger != null) {
                    logger.warning("[Survival] Scenario " + scenario.id() + " '" + scenario.name() + "' has survival settings but is not"
                        + " ready; the round cancels. Readiness errors: " + config.get().readiness().errors());
                }
                return false;
            }
        };
    }

    /** The human team: the configured one when the scenario has it, else the first Defender (S4-5). */
    @Override
    public OptionalInt singleTeam(SiegeLobbyRuntime lobby, KnkSiegeScenario scenario) {
        Optional<SurvivalConfiguration> config = configurationFor(lobby.id(), scenario);
        if (config.isEmpty()) return OptionalInt.empty();
        int human = config.get().humanTeamId();
        if (human > 0 && scenario.team(human).isPresent()) return OptionalInt.of(human);
        return scenario.firstDefender().map(KnkSiegeTeam::id).map(OptionalInt::of).orElse(OptionalInt.empty());
    }

    @Override
    public boolean suppressMembershipEnd(SiegeLobbyRuntime lobby, SiegeMatch match) {
        // The empty NPC team never forfeits; with the last human gone the ordinary path ends the match (nothing to settle).
        return matchOfLobby.apply(lobby.id()).isPresent() && match.roster().size() > 0;
    }

    @Override
    public boolean allowRejoin(SiegeLobbyRuntime lobby) {
        return !isSurvival(lobby);
    }

    @Override
    public Optional<WinResolver.Result> resolveResult(SiegeLobbyRuntime lobby, SiegeMatch match, SiegeEndReason reason) {
        Optional<SurvivalMatch> survival = matchOfLobby.apply(lobby.id());
        if (survival.isEmpty()) return Optional.empty();
        SurvivalMatch m = survival.get();
        SurvivalEndReason survivalReason = m.endReason()
            .orElseGet(() -> SurvivalEndMapping.survivalReason(reason, match.roster().size() > 0));
        if (m.endReason().isEmpty()) m.endedExternally(survivalReason);
        return Optional.of(SurvivalEndMapping.result(survivalReason, reason, m.npcAllianceGroup()));
    }

    /** S51: the side-capture reduction is off in survival. */
    @Override
    public KnkSiegeConfiguration matchConfiguration(SiegeLobbyRuntime lobby, KnkSiegeScenario scenario, KnkSiegeConfiguration c) {
        if (configurationFor(lobby.id(), scenario).isEmpty() || c.sideCaptureReduction() == 0) return c;
        return new KnkSiegeConfiguration(c.captureAttackBase(), c.captureAttackPerExtra(), c.captureAttackPerExtraInstantVictory(),
            c.captureDefendBase(), c.captureDefendPerExtra(), c.captureDefendPerExtraInstantVictory(), 0.0,
            c.voteCloseSecondsBeforeStart(), c.drawSecondsBeforeStart(), c.hubSecondsBeforeStart(), c.teamSplitSecondsBeforeStart(),
            c.matchmakingAnnouncementMarks(), c.killAnnouncementThresholds(), c.killStreakAnnounceAbove(), c.headshotMultiplier(),
            c.allowedCommands(), c.spawnPickerDelayTicks(), c.enchantDropChancePerMille(), c.allowedEnchantmentKeys(),
            c.enchantLevelMin(), c.enchantLevelMax(), c.maxBooksAlive(), c.nonMemberGateView(), c.giveNavigationItem(),
            c.playerSkipMatchmakingSeconds(), c.adminSkipMatchmakingSeconds(), c.postMatchSeconds());
    }
}
