package net.knightsandkings.knk.core.navigation.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one in-memory navigation model (destination engine DESIGN § 7.1, § 11 "thread model"): an immutable
 * {@link NavigationModel} swapped through a {@code volatile}, read from any thread without locks; replaced only by the
 * start-up load, a successful refresh and the paced place fallback.
 *
 * <p>The {@link #version()} goes up only when the content changes, not when a refresh merely confirms it (a newer
 * {@code asOf}), so lists cached per {@code (scope, world, modelVersion)} stay valid (E18). Version 0 means no model.
 */
public final class NavigationModelHolder {

    private static final Logger LOGGER = Logger.getLogger(NavigationModelHolder.class.getName());

    private volatile NavigationModel model;
    private volatile long version;
    private final List<Consumer<NavigationModel>> listeners = new CopyOnWriteArrayList<>();

    /** The model now; null when none was ever loaded (limited mode, § 7.4). */
    public NavigationModel current() {
        return model;
    }

    public Optional<NavigationModel> model() {
        return Optional.ofNullable(model);
    }

    /** Goes up with every content change; 0 while there is no model. */
    public long version() {
        return version;
    }

    /** {@link ModelFreshness} of the model now. */
    public ModelFreshness freshness(Instant now, Duration staleAfter) {
        return ModelFreshness.of(model, now, staleAfter);
    }

    /**
     * Puts {@code next} in place. Returns whether the content changed (then the version goes up and the listeners are
     * told); the same content with a newer {@code asOf} is taken quietly.
     */
    public boolean replace(NavigationModel next) {
        Objects.requireNonNull(next, "next");
        boolean changed;
        synchronized (this) {
            NavigationModel previous = model;
            changed = previous == null || !previous.sameContent(next);
            if (!changed && !next.asOf().isAfter(previous.asOf())) {
                return false;
            }
            model = next;
            if (changed) {
                version++;
            }
        }
        if (changed) {
            notifyListeners(next);
        }
        return changed;
    }

    /** Applies {@code change} to the model now (nothing when there is none); as {@link #replace}. */
    public boolean update(UnaryOperator<NavigationModel> change) {
        Objects.requireNonNull(change, "change");
        NavigationModel next;
        synchronized (this) {
            if (model == null) {
                return false;
            }
            next = change.apply(model);
            if (next == null || next == model) {
                return false;
            }
            return replace(next);
        }
    }

    /** Called (on the thread that changed it) after every content change. Nothing reads it before phase 3. */
    public void onChange(Consumer<NavigationModel> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void notifyListeners(NavigationModel next) {
        for (Consumer<NavigationModel> listener : listeners) {
            try {
                listener.accept(next);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "[Navigation] Model listener failed", e);
            }
        }
    }
}
