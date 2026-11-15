package net.knightsandkings.knk.core.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runs scenarios one after the other, driven by {@link #tick()} once per server tick, and writes the
 * {@link ScenarioReport} lines to {@code out}: a START and a PASS/FAIL line per scenario, then one
 * SUMMARY line. A scenario whose scene cannot be opened, or whose {@link Scenario#define} throws,
 * fails without running; a scene that cannot be closed fails a scenario that had passed.
 *
 * @param <S> the scene type
 */
public final class ScenarioSuiteRun<S> {

    private final List<Scenario<S>> scenarios;
    private final ScenarioHost<S> host;
    private final Consumer<String> out;
    private final List<ScenarioResult> results = new ArrayList<>();

    private int next;
    private Scenario<S> current;
    private S scene;
    private ScenarioRun run;
    private boolean finished;

    public ScenarioSuiteRun(List<? extends Scenario<S>> scenarios, ScenarioHost<S> host, Consumer<String> out) {
        this.scenarios = List.copyOf(scenarios);
        this.host = Objects.requireNonNull(host, "host");
        this.out = Objects.requireNonNull(out, "out");
    }

    /** Advances by one server tick; returns true once the summary line has been written. */
    public boolean tick() {
        if (finished) {
            return true;
        }
        if (current == null) {
            if (next >= scenarios.size()) {
                out.accept(ScenarioReport.summaryLine(results));
                finished = true;
                return true;
            }
            if (!start(scenarios.get(next), next++)) {
                return false; // failed to start; the next scenario starts in the next tick
            }
        }
        if (run.tick()) {
            complete(run.result());
        }
        return false;
    }

    public boolean finished() {
        return finished;
    }

    /** Results so far, in run order. */
    public List<ScenarioResult> results() {
        return Collections.unmodifiableList(results);
    }

    private boolean start(Scenario<S> scenario, int index) {
        current = scenario;
        out.accept(ScenarioReport.startLine(scenario.name()));
        try {
            scene = host.open(scenario, index);
        } catch (Exception | Error error) {
            scene = null;
            complete(ScenarioResult.fail(scenario.name(), "could not prepare the scene: " + ScenarioReport.describe(error), 0));
            return false;
        }
        try {
            ScenarioPlan plan = new ScenarioPlan();
            scenario.define(plan, scene);
            run = new ScenarioRun(scenario.name(), plan);
            return true;
        } catch (RuntimeException | Error error) {
            complete(ScenarioResult.fail(scenario.name(), "define threw " + ScenarioReport.describe(error), 0));
            return false;
        }
    }

    private void complete(ScenarioResult result) {
        if (scene != null) {
            try {
                host.close(scene);
            } catch (Exception | Error error) {
                if (result.passed()) {
                    result = ScenarioResult.fail(result.name(),
                        "could not tear the scene down: " + ScenarioReport.describe(error), result.ticks());
                }
            }
        }
        results.add(result);
        out.accept(ScenarioReport.line(result));
        current = null;
        scene = null;
        run = null;
    }
}
