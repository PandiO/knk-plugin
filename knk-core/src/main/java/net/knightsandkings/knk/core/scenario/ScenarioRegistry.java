package net.knightsandkings.knk.core.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The known scenarios, in registration order (the order {@code run all} uses).
 *
 * @param <S> the scene type
 */
public final class ScenarioRegistry<S> {

    /** The argument that selects every scenario. */
    public static final String ALL = "all";

    private final Map<String, Scenario<S>> scenarios = new LinkedHashMap<>();

    /** Adds a scenario; its name must match {@link Scenario#NAME}, be unique and not be {@value #ALL}. */
    public ScenarioRegistry<S> register(Scenario<S> scenario) {
        String name = scenario.name();
        if (name == null || !Scenario.NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("scenario name must be lower-case words joined by hyphens: " + name);
        }
        if (ALL.equals(name)) {
            throw new IllegalArgumentException("'" + ALL + "' is reserved");
        }
        if (scenarios.putIfAbsent(name, scenario) != null) {
            throw new IllegalArgumentException("duplicate scenario name: " + name);
        }
        return this;
    }

    public List<Scenario<S>> all() {
        return Collections.unmodifiableList(new ArrayList<>(scenarios.values()));
    }

    public Optional<Scenario<S>> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(scenarios.get(name.toLowerCase(Locale.ROOT)));
    }

    /** {@value #ALL} gives every scenario, a name gives that one; empty when the name is unknown. */
    public List<Scenario<S>> select(String argument) {
        if (argument != null && ALL.equalsIgnoreCase(argument)) {
            return all();
        }
        return find(argument).map(List::of).orElse(List.of());
    }
}
