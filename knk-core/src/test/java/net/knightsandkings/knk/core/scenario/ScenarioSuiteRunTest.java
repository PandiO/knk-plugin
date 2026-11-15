package net.knightsandkings.knk.core.scenario;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioSuiteRunTest {

    /** A scene is just the list of events the host and the scenario write to. */
    private static final class RecordingHost implements ScenarioHost<List<String>> {
        final List<String> events = new ArrayList<>();
        String failOpenFor;
        String failCloseFor;

        @Override
        public List<String> open(Scenario<List<String>> scenario, int index) {
            if (scenario.name().equals(failOpenFor)) {
                throw new IllegalStateException("no world");
            }
            events.add("open " + scenario.name() + " #" + index);
            List<String> scene = new ArrayList<>();
            scene.add(scenario.name());
            return scene;
        }

        @Override
        public void close(List<String> scene) {
            events.add("close " + scene.get(0));
            if (scene.get(0).equals(failCloseFor)) {
                throw new IllegalStateException("chunk stuck");
            }
        }
    }

    private static Scenario<List<String>> scenario(String name, BiConsumer<ScenarioPlan, List<String>> body) {
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
            public void define(ScenarioPlan plan, List<String> scene) {
                body.accept(plan, scene);
            }
        };
    }

    private static List<String> runAll(ScenarioSuiteRun<List<String>> suite) {
        for (int tick = 0; tick < 1_000; tick++) {
            if (suite.tick()) {
                return null;
            }
        }
        fail("suite did not finish");
        return null;
    }

    @Test
    void reportsOneLinePerScenarioAndASummary() {
        RecordingHost host = new RecordingHost();
        List<String> out = new ArrayList<>();
        ScenarioSuiteRun<List<String>> suite = new ScenarioSuiteRun<>(List.of(
            scenario("passes", (plan, scene) -> plan.waitTicks(2)),
            scenario("fails", (plan, scene) -> plan.expect("nothing", () -> false))
        ), host, out::add);

        runAll(suite);

        assertEquals(List.of(
            "KNK-SCENARIO START passes",
            "KNK-SCENARIO PASS passes (3 ticks)",
            "KNK-SCENARIO START fails",
            "KNK-SCENARIO FAIL fails: expected: nothing",
            "KNK-SCENARIO SUMMARY passed=1 failed=1 total=2"
        ), out);
        assertEquals(List.of("open passes #0", "close passes", "open fails #1", "close fails"), host.events);
        assertTrue(suite.finished());
    }

    @Test
    void aSceneThatCannotOpenFailsThatScenarioOnly() {
        RecordingHost host = new RecordingHost();
        host.failOpenFor = "broken";
        List<String> out = new ArrayList<>();
        ScenarioSuiteRun<List<String>> suite = new ScenarioSuiteRun<>(List.of(
            scenario("broken", (plan, scene) -> fail("define must not run")),
            scenario("fine", (plan, scene) -> { })
        ), host, out::add);

        runAll(suite);

        assertTrue(out.contains("KNK-SCENARIO FAIL broken: could not prepare the scene: IllegalStateException: no world"));
        assertTrue(out.contains("KNK-SCENARIO PASS fine (1 ticks)"));
        assertEquals("KNK-SCENARIO SUMMARY passed=1 failed=1 total=2", out.get(out.size() - 1));
        assertFalse(host.events.contains("close broken"));
    }

    @Test
    void aThrowingDefineFailsAndStillClosesTheScene() {
        RecordingHost host = new RecordingHost();
        List<String> out = new ArrayList<>();
        ScenarioSuiteRun<List<String>> suite = new ScenarioSuiteRun<>(List.of(
            scenario("bad-define", (plan, scene) -> {
                throw new IllegalArgumentException("no gate");
            })
        ), host, out::add);

        runAll(suite);

        assertTrue(out.contains("KNK-SCENARIO FAIL bad-define: define threw IllegalArgumentException: no gate"));
        assertTrue(host.events.contains("close bad-define"));
    }

    @Test
    void aSceneThatCannotCloseFailsAPassedScenario() {
        RecordingHost host = new RecordingHost();
        host.failCloseFor = "sticky";
        List<String> out = new ArrayList<>();
        ScenarioSuiteRun<List<String>> suite = new ScenarioSuiteRun<>(List.of(
            scenario("sticky", (plan, scene) -> { })
        ), host, out::add);

        runAll(suite);

        assertTrue(out.contains("KNK-SCENARIO FAIL sticky: could not tear the scene down: IllegalStateException: chunk stuck"));
    }

    @Test
    void anEmptySuiteWritesOnlyTheSummary() {
        List<String> out = new ArrayList<>();
        ScenarioSuiteRun<List<String>> suite = new ScenarioSuiteRun<>(List.of(), new RecordingHost(), out::add);

        assertTrue(suite.tick());
        assertEquals(List.of("KNK-SCENARIO SUMMARY passed=0 failed=0 total=0"), out);
    }

    @Test
    void reasonsAreFlattenedToOneLine() {
        assertEquals("KNK-SCENARIO FAIL x: a b c",
            ScenarioReport.line(ScenarioResult.fail("x", "a\nb \t c", 3)));
        assertEquals("KNK-SCENARIO FAIL x: (no reason given)", ScenarioReport.line(ScenarioResult.fail("x", null, 3)));
    }

    @Test
    void registryValidatesNamesAndSelects() {
        ScenarioRegistry<List<String>> registry = new ScenarioRegistry<>();
        registry.register(scenario("gate-open-close", (p, s) -> { }));
        registry.register(scenario("second", (p, s) -> { }));

        assertThrows(IllegalArgumentException.class, () -> registry.register(scenario("Gate Open", (p, s) -> { })));
        assertThrows(IllegalArgumentException.class, () -> registry.register(scenario("all", (p, s) -> { })));
        assertThrows(IllegalArgumentException.class, () -> registry.register(scenario("second", (p, s) -> { })));

        assertEquals(2, registry.select("ALL").size());
        assertEquals("second", registry.select("Second").get(0).name());
        assertTrue(registry.select("unknown").isEmpty());
        assertTrue(registry.select(null).isEmpty());
    }
}
