package net.knightsandkings.knk.paper.siege.survival;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.ConfigurationDto;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGate;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchLength;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.domain.siege.SiegeGateState;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.SiegeScenarioRules;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The hooks answer PvP for an ordinary scenario and survival for one with a cached configuration. */
class SurvivalModeHooksTest {

    static KnkSiegeScenario scenario(int id) {
        List<KnkSiegeTeam> teams = List.of(
            new KnkSiegeTeam(1, 0, SiegeTeamRole.DEFENDER, 1, null, "Defenders", "BLUE", null, null, List.of()),
            new KnkSiegeTeam(2, 1, SiegeTeamRole.ATTACKER, 2, null, "Raiders", "RED", null, null, List.of()));
        List<KnkSiegeObjective> objectives = List.of(new KnkSiegeObjective(1, 0, "Throne", null, null, 500, 3, true, 1, false, SiegeGateState.OPEN));
        return new KnkSiegeScenario(id, "Scenario " + id, null, 1, "Town", "town_1", List.of(), null, 2, 20, null, null,
            KnkSiegeMatchLength.DEFAULT, null, true, false, true, teams, objectives, List.<KnkSiegeGate>of(), List.of());
    }

    static SurvivalConfiguration survivalConfig(int scenarioId, boolean ready) throws Exception {
        ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String body = "{\"siegeScenarioId\": " + scenarioId + ", \"minPlayers\": 1, \"humanTeamId\": 1, \"npcTeamId\": 2,"
            + " \"monsterTypes\": [{\"entityType\": \"ZOMBIE\"}], \"readiness\": {\"isReady\": " + ready + "}}";
        return SurvivalConfigurationMapper.map(json.readValue(body, ConfigurationDto.class));
    }

    private final SurvivalConfigurationCache cache = new SurvivalConfigurationCache(null, Runnable::run, Logger.getLogger("test"));
    private final SurvivalModeHooks hooks = new SurvivalModeHooks(cache, id -> Optional.empty(), id -> Optional.empty(), null);
    private final KnkSiegeScenario pvp = scenario(1);
    private final KnkSiegeScenario survival = scenario(2);

    private SiegeLobbyRuntime lobby(KnkSiegeScenario drawn) {
        SiegeLobbyRuntime rt = mock(SiegeLobbyRuntime.class);
        when(rt.id()).thenReturn(1);
        when(rt.drawnScenario()).thenReturn(Optional.ofNullable(drawn));
        return rt;
    }

    @Test
    void pvpScenariosKeepEveryDefault() {
        SiegeScenarioRules rules = hooks.scenarioRules(1);
        assertEquals(2, rules.playersMin(pvp));
        assertTrue(rules.timed(pvp));
        assertTrue(rules.startable(pvp));
        assertEquals(OptionalInt.empty(), hooks.singleTeam(lobby(pvp), pvp));
        assertTrue(hooks.allowRejoin(lobby(pvp)));
        KnkSiegeConfiguration c = KnkSiegeConfiguration.legacyDefaults();
        assertSame(c, hooks.matchConfiguration(lobby(pvp), pvp, c));
        assertTrue(hooks.resolveResult(lobby(pvp), null, SiegeEndReason.TIME_EXPIRED).isEmpty());
    }

    @Test
    void survivalScenariosGetTheSurvivalRules() throws Exception {
        cache.put(survivalConfig(2, true));
        SiegeScenarioRules rules = hooks.scenarioRules(1);
        assertEquals(1, rules.playersMin(survival));
        assertFalse(rules.timed(survival));
        assertTrue(rules.startable(survival));
        assertEquals(OptionalInt.of(1), hooks.singleTeam(lobby(survival), survival));
        assertFalse(hooks.allowRejoin(lobby(survival)));
        KnkSiegeConfiguration c = KnkSiegeConfiguration.legacyDefaults();
        KnkSiegeConfiguration played = hooks.matchConfiguration(lobby(survival), survival, c);
        assertEquals(0.0, played.sideCaptureReduction());
        assertEquals(c.captureAttackBase(), played.captureAttackBase());
        assertEquals(c.postMatchSeconds(), played.postMatchSeconds());
    }

    @Test
    void anUnreadyConfigurationIsNotStartable() throws Exception {
        cache.put(survivalConfig(2, false));
        assertFalse(hooks.scenarioRules(1).startable(survival));
        assertTrue(hooks.scenarioRules(1).startable(pvp));
    }

    @Test
    void theLobbyLevelConfigurationWinsOverTheScenarioLevelOne() throws Exception {
        cache.put(survivalConfig(2, true));
        SurvivalConfiguration lobbyLevel = survivalConfig(2, false);
        SurvivalModeHooks withFrozen = new SurvivalModeHooks(cache, id -> Optional.empty(), id -> id == 1 ? Optional.of(lobbyLevel) : Optional.empty(), null);
        assertFalse(withFrozen.scenarioRules(1).startable(survival));
        assertTrue(withFrozen.scenarioRules(7).startable(survival));
    }
}
