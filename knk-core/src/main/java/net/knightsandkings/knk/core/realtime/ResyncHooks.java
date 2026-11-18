package net.knightsandkings.knk.core.realtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The resync that runs after every (re)connect to the game hub and after a sequence gap (DESIGN §4.4, P2/P3): an
 * ordered list of named hooks that call the refresh paths the plugin already has ({@code /knk cache refresh}, the
 * domain access sync, the lootbox runtime refresh, ...). A failing hook is logged and skipped; the others still run.
 * Hooks run in registration order on the caller's thread (the plugin calls {@link #runAll()} on the main thread).
 */
public final class ResyncHooks {

    private static final Logger LOGGER = Logger.getLogger(ResyncHooks.class.getName());

    private final List<Map.Entry<String, Runnable>> hooks = new CopyOnWriteArrayList<>();

    /** Adds a hook; a second registration under the same name replaces the first in place. */
    public void register(String name, Runnable hook) {
        if (name == null || name.isBlank() || hook == null) {
            throw new IllegalArgumentException("A resync hook needs a name and a body");
        }
        for (int i = 0; i < hooks.size(); i++) {
            if (hooks.get(i).getKey().equals(name)) {
                hooks.set(i, Map.entry(name, hook));
                return;
            }
        }
        hooks.add(Map.entry(name, hook));
    }

    public List<String> names() {
        return hooks.stream().map(Map.Entry::getKey).toList();
    }

    /** Runs every hook in order; returns the names of the hooks that threw. */
    public List<String> runAll() {
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, Runnable> hook : hooks) {
            try {
                hook.getValue().run();
            } catch (RuntimeException e) {
                failed.add(hook.getKey());
                LOGGER.log(Level.WARNING, "Realtime resync step '" + hook.getKey() + "' failed", e);
            }
        }
        return failed;
    }
}
