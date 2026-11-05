package net.knightsandkings.knk.core.privacy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What the game server deletes about an erased account (PandiO/knk-workspace#184, #66 "Data erasure push"): an ordered
 * list of named steps, each owned by the feature that keeps the data (the offline security store, the user and
 * permission caches, the discovery and statistics spools, the PM-log queue and local log, the siege vault, the hub
 * outage return records, the list of Minecraft's own player files). Features register their step while they start;
 * {@link AccountErasureService} runs them. A failing step is logged and the others still run.
 *
 * <p>Steps run either on the server's main thread ({@link Where#MAIN_THREAD}: state that only the main thread touches)
 * or on the erasure worker ({@link Where#WORKER}: thread-safe stores and file I/O). Every step is idempotent: the same
 * account may be erased twice (a push and the catch-up after it, or a feed applied again from the start).
 */
public final class LocalDataEraser {

    private static final Logger LOGGER = Logger.getLogger(LocalDataEraser.class.getName());

    /** The thread a step needs. */
    public enum Where { MAIN_THREAD, WORKER }

    /** Deletes what one feature keeps about {@code account}; returns how many records/files/entries went. */
    @FunctionalInterface
    public interface Step {
        int erase(ErasedAccount account) throws Exception;
    }

    private record Registered(String name, Where where, Step step) {
    }

    private final List<Registered> steps = new CopyOnWriteArrayList<>();

    /** Adds a step; a second registration under the same name replaces the first in place. */
    public void register(String name, Where where, Step step) {
        if (name == null || name.isBlank() || where == null || step == null) {
            throw new IllegalArgumentException("An erasure step needs a name, a thread and a body");
        }
        Registered entry = new Registered(name, where, step);
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).name().equals(name)) {
                steps.set(i, entry);
                return;
            }
        }
        steps.add(entry);
    }

    public List<String> names() {
        return steps.stream().map(Registered::name).toList();
    }

    /** The result of the steps of one thread. */
    public record Outcome(Map<String, Integer> erased, List<String> failed) {
        public Outcome {
            erased = Map.copyOf(erased);
            failed = List.copyOf(failed);
        }
    }

    /** Runs the steps registered for {@code where}, in order, on the calling thread. */
    public Outcome run(Where where, ErasedAccount account) {
        Objects.requireNonNull(account, "account");
        Map<String, Integer> erased = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        for (Registered step : steps) {
            if (step.where() != where) {
                continue;
            }
            try {
                erased.put(step.name(), Math.max(0, step.step().erase(account)));
            } catch (Exception e) {
                failed.add(step.name());
                // The erasure id only: no UUID or name in the log.
                LOGGER.log(Level.WARNING, "[KnK Privacy] Erasure #" + account.erasureId() + ": step '" + step.name()
                    + "' failed", e);
            }
        }
        return new Outcome(erased, failed);
    }
}
