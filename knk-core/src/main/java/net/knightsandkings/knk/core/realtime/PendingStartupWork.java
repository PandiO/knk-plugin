package net.knightsandkings.knk.core.realtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Startup work that needs the API and never retried by itself (PandiO/knk-workspace#31, KNG-202): the siege's
 * unfinished-match abort, the stale gate restore, the runtime config with its lobbies and the title brackets. Each step
 * runs once at startup as before; one that did not succeed stays pending and runs again when the API is back: on the
 * next hub (re)connect (DESIGN §4.4 step 3, PandiO/knk-workspace#182) and, with or without the hub, when the API
 * connectivity monitor reports UP again or a fallback retry comes due ({@link StartupWorkRetry}), until it has.
 * <p>
 * {@link #runPending()} runs the pending steps one after the other in registration order (each waits for the one
 * before, success or not, since the order matters: abort unfinished matches, then restore stale gates, then build the
 * lobbies). It is the one runner for every trigger: a call while a pass runs does not start a second chain but asks for
 * one more pass after it (at most one is queued), so the hub reconnect and the connectivity recovery arriving together
 * run each step once. A step reports success with {@code true}; a {@code false}, an exception or a failed future keeps
 * it pending. A step that is running is not started twice.
 */
public final class PendingStartupWork {

    private static final Logger LOGGER = Logger.getLogger(PendingStartupWork.class.getName());

    /** One startup step. */
    public static final class Step {
        private final String name;
        private final Supplier<CompletableFuture<Boolean>> body;
        private volatile boolean done;
        private CompletableFuture<Boolean> inFlight;

        private Step(String name, Supplier<CompletableFuture<Boolean>> body) {
            this.name = name;
            this.body = body;
        }

        public String name() {
            return name;
        }

        public boolean done() {
            return done;
        }

        /** Marks the step done without running it (e.g. it no longer applies). */
        public void markDone() {
            done = true;
        }

        /** Runs the step unless it is done or running; the future says whether it is done now. Never fails. */
        public synchronized CompletableFuture<Boolean> run() {
            if (done) {
                return CompletableFuture.completedFuture(true);
            }
            if (inFlight != null && !inFlight.isDone()) {
                return inFlight;
            }
            CompletableFuture<Boolean> started;
            try {
                started = Objects.requireNonNull(body.get(), "step future");
            } catch (RuntimeException e) {
                started = CompletableFuture.failedFuture(e);
            }
            inFlight = started.handle((ok, error) -> {
                if (error != null) {
                    LOGGER.log(Level.WARNING, "Startup step '" + name + "' failed; it runs again when the API is back", error);
                }
                done = error == null && Boolean.TRUE.equals(ok);
                return done;
            });
            return inFlight;
        }
    }

    private final List<Step> steps = new CopyOnWriteArrayList<>();

    /** Adds a step (run it at startup with {@link Step#run()}); a name registered twice replaces nothing and throws. */
    public Step register(String name, Supplier<CompletableFuture<Boolean>> body) {
        if (name == null || name.isBlank() || body == null) {
            throw new IllegalArgumentException("A startup step needs a name and a body");
        }
        for (Step step : steps) {
            if (step.name.equals(name)) {
                throw new IllegalArgumentException("Startup step '" + name + "' is registered already");
            }
        }
        Step step = new Step(name, body);
        steps.add(step);
        return step;
    }

    /** Names of the steps that have not succeeded yet, in order. */
    public List<String> pending() {
        return steps.stream().filter(step -> !step.done).map(Step::name).toList();
    }

    private final Object passLock = new Object();
    /** The pass running now; null when none runs. Guarded by {@link #passLock}. */
    private CompletableFuture<List<String>> currentPass;
    /** The pass asked for while {@link #currentPass} runs; it starts when that one ends. Guarded by {@link #passLock}. */
    private CompletableFuture<List<String>> queuedPass;

    /** Whether any step has not succeeded yet. */
    public boolean hasPending() {
        return steps.stream().anyMatch(step -> !step.done);
    }

    /**
     * Runs every pending step, one after the other; completes with the names still pending. Never fails. While a pass
     * runs, a call returns the one pass queued after it (shared by every caller meanwhile), so a trigger that comes in
     * during a pass is not lost and no step runs in two chains at once.
     */
    public CompletableFuture<List<String>> runPending() {
        CompletableFuture<List<String>> pass;
        synchronized (passLock) {
            if (currentPass != null) {
                if (queuedPass == null) {
                    queuedPass = new CompletableFuture<>();
                }
                return queuedPass;
            }
            pass = new CompletableFuture<>();
            currentPass = pass;
        }
        startPass(pass);
        return pass;
    }

    private void startPass(CompletableFuture<List<String>> pass) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Step step : new ArrayList<>(steps)) {
            if (step.done) {
                continue;
            }
            chain = chain.thenCompose(ignored -> step.run().thenApply(ok -> (Void) null));
        }
        chain.handle((ignored, error) -> pending()).thenAccept(left -> {
            CompletableFuture<List<String>> next;
            synchronized (passLock) {
                next = queuedPass;
                queuedPass = null;
                currentPass = next;
            }
            pass.complete(left);
            if (next != null) {
                startPass(next);
            }
        });
    }
}
