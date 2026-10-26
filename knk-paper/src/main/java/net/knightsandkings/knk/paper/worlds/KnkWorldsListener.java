package net.knightsandkings.knk.paper.worlds;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import net.kyori.adventure.text.Component;
import net.knightsandkings.knk.core.worlds.WorldReadiness;

/**
 * Keeps the configured worlds there (KNG-114, hub DESIGN §16 decision 1):
 * <ul>
 *   <li>an unload of a configured world is cancelled, whoever asks for it;</li>
 *   <li>while a required world is missing, players may not join (fail closed), except operators, who can fix it.</li>
 * </ul>
 */
public final class KnkWorldsListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(KnkWorldsListener.class.getName());

    private final Supplier<WorldReadiness> readiness;
    private final Predicate<UUID> operator;

    /**
     * @param readiness the startup result
     * @param operator  whether a joining player is an operator (read before they are online, so no permissions yet)
     */
    public KnkWorldsListener(Supplier<WorldReadiness> readiness, Predicate<UUID> operator) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.operator = Objects.requireNonNull(operator, "operator");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        String name = event.getWorld().getName();
        readiness.get().find(name).ifPresent(status -> {
            event.setCancelled(true);
            LOGGER.warning("[KnK Worlds] Refused to unload " + name + ": it is in worlds.load");
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        String refusal = refusal(readiness.get(), operator.test(event.getUniqueId()));
        if (refusal != null) {
            // PandiO/knk-workspace#159: the reason for the staff notice
            net.knightsandkings.knk.paper.admission.JoinRefusalReasons.shared().record(event.getUniqueId(), event.getName(),
                net.knightsandkings.knk.core.admission.JoinRefusal.Reason.WORLD_NOT_READY,
                String.join(", ", readiness.get().unavailableRequired().stream().map(WorldReadiness.Status::name).toList()));
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(refusal));
        }
    }

    /** The kick message while a required world is missing, or null when the player may join. */
    static String refusal(WorldReadiness readiness, boolean operator) {
        if (readiness.ready() || operator) {
            return null;
        }
        String missing = String.join(", ", readiness.unavailableRequired().stream().map(WorldReadiness.Status::name).toList());
        return "The server is not ready yet (world " + missing + " is not available). Please try again later.";
    }
}
