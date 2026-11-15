package net.knightsandkings.knk.core.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Plays one {@link ScenarioPlan}, driven by the platform: call {@link #tick()} once per server tick on
 * the server thread until it returns true, then read {@link #result()}.
 * <p>
 * In each tick the run executes steps until one lets time pass: acts and expectations run back to
 * back; {@code waitTicks(n)} resumes {@code n} ticks later; {@code await} checks its condition in the
 * tick it is reached and every tick after, failing once {@code timeoutTicks} ticks have passed without
 * it holding. The first failing step (an expectation, a timeout or any exception) ends the run. The
 * cleanups then run, last added first; a failing cleanup fails a run that had passed.
 */
public final class ScenarioRun {

    private final String name;
    private final List<ScenarioPlan.Step> steps;
    private final List<ScenarioPlan.Cleanup> cleanups;

    private int index;
    private long ticks;
    private int waitRemaining;
    private long awaitStartTick = -1;
    private ScenarioResult result;

    public ScenarioRun(String name, ScenarioPlan plan) {
        this.name = Objects.requireNonNull(name, "name");
        this.steps = List.copyOf(plan.steps());
        this.cleanups = List.copyOf(plan.cleanups());
    }

    /** Advances the run by one server tick; returns true once it has finished (cleanups included). */
    public boolean tick() {
        if (result != null) {
            return true;
        }
        ticks++;
        try {
            while (index < steps.size()) {
                ScenarioPlan.Step step = steps.get(index);
                if (step instanceof ScenarioPlan.Act act) {
                    act.action().run();
                    index++;
                } else if (step instanceof ScenarioPlan.Expect expect) {
                    String problem = expect.check().get();
                    if (problem != null) {
                        return finish(problem);
                    }
                    index++;
                } else if (step instanceof ScenarioPlan.Wait wait) {
                    if (waitRemaining == 0) {
                        waitRemaining = wait.ticks();
                        return false;
                    }
                    if (--waitRemaining > 0) {
                        return false;
                    }
                    index++;
                } else if (step instanceof ScenarioPlan.Await await) {
                    if (awaitStartTick < 0) {
                        awaitStartTick = ticks;
                    }
                    if (await.condition().getAsBoolean()) {
                        awaitStartTick = -1;
                        index++;
                        continue;
                    }
                    long waited = ticks - awaitStartTick;
                    if (waited >= await.timeoutTicks()) {
                        return finish("timed out after " + waited + " ticks waiting for: " + await.description());
                    }
                    return false;
                }
            }
        } catch (RuntimeException | Error error) {
            String where = index < steps.size() ? steps.get(index).description() : "the plan";
            return finish("step '" + where + "' threw " + ScenarioReport.describe(error));
        }
        return finish(null);
    }

    /** Whether the run has finished. */
    public boolean finished() {
        return result != null;
    }

    /** The outcome; null until {@link #tick()} returned true. */
    public ScenarioResult result() {
        return result;
    }

    private boolean finish(String failure) {
        List<String> cleanupFailures = new ArrayList<>();
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            ScenarioPlan.Cleanup cleanup = cleanups.get(i);
            try {
                cleanup.action().run();
            } catch (RuntimeException | Error error) {
                cleanupFailures.add("cleanup '" + cleanup.description() + "' threw " + ScenarioReport.describe(error));
            }
        }
        if (failure == null && !cleanupFailures.isEmpty()) {
            failure = String.join("; ", cleanupFailures);
        }
        result = failure == null ? ScenarioResult.pass(name, ticks) : ScenarioResult.fail(name, failure, ticks);
        return true;
    }
}
