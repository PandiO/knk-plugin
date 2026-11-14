package net.knightsandkings.knk.core.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The steps of one scenario, in order, plus its cleanup actions (PandiO/knk-workspace#421).
 * <ul>
 *   <li>{@link #act}: runs once;</li>
 *   <li>{@link #expect} / {@link #expectEquals}: an assertion, checked once;</li>
 *   <li>{@link #waitTicks}: lets the server run for a number of ticks;</li>
 *   <li>{@link #await}: checks a condition every tick until it holds, failing after a timeout;</li>
 *   <li>{@link #cleanup}: always runs at the end, also after a failure, last added first.</li>
 * </ul>
 * Acts and expectations that follow each other run in the same tick; only waits and awaits let time
 * pass. Every method returns this plan, so steps chain.
 */
public final class ScenarioPlan {

    /** One step of a plan. */
    public sealed interface Step permits Act, Expect, Wait, Await {
        /** What the step does, used in failure reasons. */
        String description();
    }

    /** Runs {@code action} once. */
    public record Act(String description, Runnable action) implements Step { }

    /** Checks {@code check} once: a null result passes, anything else is the failure reason. */
    public record Expect(String description, Supplier<String> check) implements Step { }

    /** Lets {@code ticks} server ticks pass. */
    public record Wait(String description, int ticks) implements Step { }

    /** Checks {@code condition} every tick until it holds, at most {@code timeoutTicks} ticks. */
    public record Await(String description, int timeoutTicks, BooleanSupplier condition) implements Step { }

    /** A cleanup action. */
    public record Cleanup(String description, Runnable action) { }

    private final List<Step> steps = new ArrayList<>();
    private final List<Cleanup> cleanups = new ArrayList<>();

    /** Runs the action once, in the current tick. */
    public ScenarioPlan act(String description, Runnable action) {
        steps.add(new Act(text(description), Objects.requireNonNull(action, "action")));
        return this;
    }

    /** Fails the scenario with "expected: {description}" when the condition is false. */
    public ScenarioPlan expect(String description, BooleanSupplier condition) {
        Objects.requireNonNull(condition, "condition");
        String what = text(description);
        steps.add(new Expect(what, () -> condition.getAsBoolean() ? null : "expected: " + what));
        return this;
    }

    /** Fails the scenario with "{description}: expected X, was Y" when the values differ. */
    public <T> ScenarioPlan expectEquals(String description, Supplier<T> actual, T expected) {
        Objects.requireNonNull(actual, "actual");
        String what = text(description);
        steps.add(new Expect(what, () -> {
            T value = actual.get();
            return Objects.equals(value, expected) ? null : what + ": expected " + expected + ", was " + value;
        }));
        return this;
    }

    /**
     * A check that explains itself: the supplier returns null when it passes, otherwise the reason
     * (for example "3 of 9 door cells are still stone").
     */
    public ScenarioPlan check(String description, Supplier<String> check) {
        steps.add(new Expect(text(description), Objects.requireNonNull(check, "check")));
        return this;
    }

    /** Lets the server run for {@code ticks} ticks (20 ticks = 1 second). */
    public ScenarioPlan waitTicks(int ticks) {
        if (ticks < 1) {
            throw new IllegalArgumentException("ticks must be at least 1, was " + ticks);
        }
        steps.add(new Wait("wait " + ticks + " ticks", ticks));
        return this;
    }

    /**
     * Checks the condition every tick, starting in the current one, until it holds; fails with
     * "timed out after N ticks waiting for: {description}" otherwise.
     */
    public ScenarioPlan await(String description, int timeoutTicks, BooleanSupplier condition) {
        if (timeoutTicks < 1) {
            throw new IllegalArgumentException("timeoutTicks must be at least 1, was " + timeoutTicks);
        }
        steps.add(new Await(text(description), timeoutTicks, Objects.requireNonNull(condition, "condition")));
        return this;
    }

    /**
     * Registers an action that always runs when the scenario ends, passed or failed. Cleanups run
     * last-added first; one that throws fails a scenario that had passed, and the others still run.
     */
    public ScenarioPlan cleanup(String description, Runnable action) {
        cleanups.add(new Cleanup(text(description), Objects.requireNonNull(action, "action")));
        return this;
    }

    public List<Step> steps() {
        return Collections.unmodifiableList(steps);
    }

    public List<Cleanup> cleanups() {
        return Collections.unmodifiableList(cleanups);
    }

    /** The longest the plan can take if every wait and await runs out, in ticks. */
    public long maxTicks() {
        long total = 0;
        for (Step step : steps) {
            if (step instanceof Wait wait) {
                total += wait.ticks();
            } else if (step instanceof Await await) {
                total += await.timeoutTicks();
            }
        }
        return total;
    }

    private static String text(String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("a step needs a description");
        }
        return description;
    }
}
