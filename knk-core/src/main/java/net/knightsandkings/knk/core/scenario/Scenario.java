package net.knightsandkings.knk.core.scenario;

import java.util.regex.Pattern;

/**
 * One in-plugin test scenario (PandiO/knk-workspace#421). It gets a fresh scene from the platform
 * and adds its steps to a {@link ScenarioPlan}; the runner plays the plan over server ticks.
 * {@link #define} runs once per run, on the server thread, before the first step: build nothing
 * there that the plan's steps could build instead, so a failure is reported as a step.
 *
 * @param <S> the scene type the platform hands out (knk-paper: {@code ScenarioScene})
 */
public interface Scenario<S> {

    /** Allowed names: lower-case words joined by hyphens, as they appear in the report lines. */
    Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /** Unique name, matching {@link #NAME} (for example {@code gate-open-close}). */
    String name();

    /** One sentence on what the scenario proves, shown by {@code /knk scenario list}. */
    String description();

    /**
     * Adds the scenario's steps to the plan.
     *
     * @param plan  the plan to fill
     * @param scene the scene the platform prepared for this run
     */
    void define(ScenarioPlan plan, S scene);
}
