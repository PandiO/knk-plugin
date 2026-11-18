package net.knightsandkings.knk.paper.admission;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.knightsandkings.knk.core.admission.JoinRefusal;

/**
 * The reason a Knights and Kings pre-login check gave when it turned a connection away (PandiO/knk-workspace#159):
 * the admission gate, the world readiness check and the hub record it before they disallow, and
 * {@link JoinRefusalNotifier} takes it when the pre-login ends refused. Without it the notice could only read the
 * disconnect screen - which for the admission gate holds the player's claim code, a credential. Thread-safe.
 */
public final class JoinRefusalReasons {

    private static final JoinRefusalReasons SHARED = new JoinRefusalReasons();

    private final Map<UUID, JoinRefusal> pending = new ConcurrentHashMap<>();

    /** The one the plugin's listeners use. */
    public static JoinRefusalReasons shared() {
        return SHARED;
    }

    public void record(UUID uuid, String name, JoinRefusal.Reason reason, String detail) {
        if (uuid != null && reason != null) {
            if (pending.size() > 1024) {
                pending.clear(); // nothing takes them (the notifier isn't registered); don't grow
            }
            pending.put(uuid, new JoinRefusal(uuid, name, reason, detail));
        }
    }

    /** The recorded refusal for {@code uuid}, removed. */
    public Optional<JoinRefusal> take(UUID uuid) {
        return uuid == null ? Optional.empty() : Optional.ofNullable(pending.remove(uuid));
    }
}
