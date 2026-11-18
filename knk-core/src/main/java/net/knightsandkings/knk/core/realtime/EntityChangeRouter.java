package net.knightsandkings.knk.core.realtime;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code EntityChanged} → the caches that hold that entity type (PandiO/knk-workspace#182, DESIGN §6.3). Each cache
 * registers what it does with a change: drop one key, drop the type ({@code id} null: many changed at once), re-read an
 * online player. Handlers run on the caller's thread and must be cheap and thread-safe (cache invalidation), or hop to
 * the main thread themselves. A failing handler is logged; the others still run.
 */
public final class EntityChangeRouter {

    /** What one cache does with a change. */
    @FunctionalInterface
    public interface Handler {
        /**
         * @param id  the entity's id; null = drop everything of the type
         * @param key the natural key the API sent (UUID, region id, menu key), or null
         * @param op  {@code Created}, {@code Updated} or {@code Deleted}
         */
        void changed(Integer id, String key, String op);
    }

    private static final Logger LOGGER = Logger.getLogger(EntityChangeRouter.class.getName());

    private final Map<String, List<Handler>> handlers = new ConcurrentHashMap<>();

    /** Adds a handler for {@code type} (the API's entity type name, e.g. {@code Town}). */
    public EntityChangeRouter on(String type, Handler handler) {
        if (type == null || type.isBlank() || handler == null) {
            throw new IllegalArgumentException("An entity change handler needs a type and a body");
        }
        handlers.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>()).add(handler);
        return this;
    }

    public Set<String> types() {
        return Set.copyOf(handlers.keySet());
    }

    /** Hands the change to every handler of {@code type}; false when none is registered (the type isn't cached here). */
    public boolean route(String type, Integer id, String key, String op) {
        List<Handler> list = type == null ? null : handlers.get(type);
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (Handler handler : list) {
            try {
                handler.changed(id, key, op);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Realtime: handling EntityChanged for " + type + " " + id + " failed", e);
            }
        }
        return true;
    }
}
