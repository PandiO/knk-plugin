package net.knightsandkings.knk.paper.realtime;

import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Maps a hub event type to its handler (DESIGN §4.4). Handlers are thin: they call the refresh paths that already
 * exist (P3). Each runs on the executor it was registered with: the main thread for anything touching Bukkit (P6), a
 * direct executor for pure cache work. A type nobody handles, or a version newer than its handler knows, is logged
 * once and ignored (forward compatible, DESIGN §4.2).
 */
public final class RealtimeDispatcher {

    private record Registration(int maxVersion, Executor executor, Consumer<RealtimeEnvelope> handler) {
    }

    private final Map<String, Registration> handlers = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final Logger logger;

    public RealtimeDispatcher(Logger logger) {
        this.logger = logger;
    }

    /**
     * @param type       the event type (= hub method name)
     * @param maxVersion the highest {@code v} this handler understands
     * @param executor   where the handler runs
     */
    public void register(String type, int maxVersion, Executor executor, Consumer<RealtimeEnvelope> handler) {
        handlers.put(type, new Registration(maxVersion, executor, handler));
    }

    /** The types to listen for on the hub connection. */
    public Set<String> types() {
        return Set.copyOf(handlers.keySet());
    }

    /** Routes one envelope; returns false when it was ignored (unknown type or version). */
    public boolean dispatch(RealtimeEnvelope envelope) {
        Registration registration = envelope.type() == null ? null : handlers.get(envelope.type());
        if (registration == null) {
            warnOnce("type:" + envelope.type(), "Realtime: no handler for event type " + envelope.type() + "; ignored");
            return false;
        }
        if (envelope.v() > registration.maxVersion()) {
            warnOnce("version:" + envelope.type() + ":" + envelope.v(), "Realtime: " + envelope.type() + " version "
                + envelope.v() + " is newer than this plugin understands (" + registration.maxVersion() + "); ignored");
            return false;
        }
        registration.executor().execute(() -> {
            try {
                registration.handler().accept(envelope);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Realtime: handling " + envelope.type() + " failed", e);
            }
        });
        return true;
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            logger.warning(message);
        }
    }
}
