package net.knightsandkings.knk.core.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ScenarioRegistryTest {

    private static Scenario<Object> named(String name) {
        return new Scenario<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public void define(ScenarioPlan plan, Object scene) {
            }
        };
    }

    private final ScenarioRegistry<Object> registry = new ScenarioRegistry<>()
        .register(named("gate-open-close"))
        .register(named("player-command-needs-grant"))
        .register(named("player-menu-click"));

    private List<String> names(String argument) {
        return registry.select(argument).stream().map(Scenario::name).toList();
    }

    @Test
    void allSelectsEveryScenarioInOrder() {
        assertEquals(List.of("gate-open-close", "player-command-needs-grant", "player-menu-click"), names("all"));
    }

    @Test
    void aNameSelectsThatScenario() {
        assertEquals(List.of("player-menu-click"), names("player-menu-click"));
        assertEquals(List.of("player-menu-click"), names("Player-Menu-Click"));
    }

    @Test
    void aPrefixWithAStarSelectsEveryMatchingScenario() {
        // PandiO/knk-workspace#540: `knk scenario run player-*`
        assertEquals(List.of("player-command-needs-grant", "player-menu-click"), names("player-*"));
        assertEquals(List.of("gate-open-close"), names("GATE-*"));
    }

    @Test
    void nothingMatchesAnUnknownNameOrABareStar() {
        assertTrue(names("player").isEmpty());
        assertTrue(names("npc-*").isEmpty());
        assertTrue(names("*").isEmpty());
        assertTrue(names(null).isEmpty());
    }
}
