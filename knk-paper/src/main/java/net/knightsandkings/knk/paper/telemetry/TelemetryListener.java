package net.knightsandkings.knk.paper.telemetry;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.telemetry.TelemetryCorrelation;
import net.knightsandkings.knk.core.telemetry.TelemetryEvent;
import net.knightsandkings.knk.core.telemetry.TelemetryEventNames;
import net.knightsandkings.knk.paper.events.GateDoorDamageEvent;
import net.knightsandkings.knk.paper.events.UserDataLoadedEvent;

/**
 * Baseline and enhanced diagnostic events from plain Bukkit events (KNG-34 link 6, DESIGN.md §F.12):
 * {@code session.join}/{@code session.leave}, {@code command.result} (label only - never the
 * arguments), {@code siege.gate_destroyed} (direct hits), and for enhanced players
 * {@code combat.hit} and {@code gate.hit}. Every handler is MONITOR and never changes the event.
 * Registered only when {@code telemetry.enabled} is true.
 */
public final class TelemetryListener implements Listener {

    private final TelemetryEmitter emitter;
    private final Consumer<Runnable> nextTick;
    private final Set<UUID> kicked = new HashSet<>();
    private final Set<Integer> destroyedGates = new HashSet<>();

    /**
     * @param nextTick runs a task on the main thread one tick later (ends a command's correlation)
     */
    public TelemetryListener(TelemetryEmitter emitter, Consumer<Runnable> nextTick) {
        this.emitter = emitter;
        this.nextTick = nextTick;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUserDataLoaded(UserDataLoadedEvent event) {
        Player player = event.getPlayer();
        emitter.event(TelemetryEventNames.SESSION_JOIN)
            .player(player.getUniqueId())
            .user(event.getUserId())
            .outcome(TelemetryEvent.Outcome.SUCCEEDED)
            .put("world", player.getWorld() == null ? null : player.getWorld().getName())
            .put("gameMode", player.getGameMode() == null ? null : player.getGameMode().name().toLowerCase(Locale.ROOT))
            .put("firstJoin", !player.hasPlayedBefore())
            .emit();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        kicked.add(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        boolean wasKicked = kicked.remove(id);
        emitter.event(TelemetryEventNames.SESSION_LEAVE)
            .player(id)
            .outcome(TelemetryEvent.Outcome.INFO)
            .reason(wasKicked ? "kick" : "quit")
            .put("reason", wasKicked ? "kick" : "quit")
            .emit();
    }

    /**
     * {@code command.result}: the command label and whether a plugin cancelled it - never its
     * arguments. The command runs right after this event on the main thread, so its API calls carry
     * this event's correlation id until the next tick.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String label = labelOf(event.getMessage());
        if (label == null) {
            return;
        }
        String correlation = TelemetryCorrelation.newId();
        emitter.event(TelemetryEventNames.COMMAND_RESULT)
            .player(event.getPlayer().getUniqueId())
            .correlation(correlation)
            .outcome(event.isCancelled() ? TelemetryEvent.Outcome.DENIED : TelemetryEvent.Outcome.INFO)
            .reason(event.isCancelled() ? "cancelled" : "dispatched")
            .object("command", label)
            .put("command", label)
            .put("cancelled", event.isCancelled())
            .emit();
        if (!event.isCancelled()) {
            CommandCorrelation.begin(correlation, nextTick);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = playerOf(event.getDamager());
        Player victim = event.getEntity() instanceof Player p ? p : null;
        Player enhanced = attacker != null && emitter.isEnhanced(attacker.getUniqueId()) ? attacker
            : victim != null && emitter.isEnhanced(victim.getUniqueId()) ? victim : null;
        if (enhanced == null) {
            return;
        }
        emitter.event(TelemetryEventNames.COMBAT_HIT)
            .player(enhanced.getUniqueId())
            .outcome(TelemetryEvent.Outcome.INFO)
            .put("attackerUserId", attacker == null ? null : emitter.userIdOf(attacker.getUniqueId()))
            .put("victimUserId", victim == null ? null : emitter.userIdOf(victim.getUniqueId()))
            .put("victimType", event.getEntityType().name().toLowerCase(Locale.ROOT))
            .put("cause", event.getCause().name().toLowerCase(Locale.ROOT))
            .put("damage", Math.round(event.getFinalDamage() * 10.0) / 10.0)
            .emit();
    }

    /**
     * After the gate's own handlers (MONITOR): {@code gate.hit} for enhanced attackers, and the first
     * time a door is seen destroyed, {@code siege.gate_destroyed}.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGateDamage(GateDoorDamageEvent event) {
        CachedGateDoor gate = event.getGate();
        if (gate == null) {
            return;
        }
        Player attacker = playerOf(event.getCausingEntity());
        UUID attackerId = attacker == null ? null : attacker.getUniqueId();
        if (attackerId != null && emitter.isEnhanced(attackerId)) {
            emitter.event(TelemetryEventNames.GATE_HIT)
                .player(attackerId)
                .object("gate", gate.getId())
                .put("gateId", gate.getId())
                .put("cause", event.getCause() == null ? null : event.getCause().name().toLowerCase(Locale.ROOT))
                .emit();
        }
        if (gate.isDestroyed()) {
            if (destroyedGates.add(gate.getId())) {
                emitter.event(TelemetryEventNames.SIEGE_GATE_DESTROYED)
                    .player(attackerId)
                    .outcome(TelemetryEvent.Outcome.SUCCEEDED)
                    .object("gate", gate.getId())
                    .put("gateId", gate.getId())
                    .emit();
            }
        } else {
            destroyedGates.remove(gate.getId());
        }
    }

    /** The command label in lower case ({@code siege} for {@code /siege join 2}); null when empty. */
    static String labelOf(String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.startsWith("/") ? message.substring(1) : message;
        int space = trimmed.indexOf(' ');
        String label = (space < 0 ? trimmed : trimmed.substring(0, space)).toLowerCase(Locale.ROOT);
        label = label.replaceAll("[^a-z0-9_:\\-]", "");
        if (label.isEmpty()) {
            return null;
        }
        return label.length() <= 32 ? label : label.substring(0, 32);
    }

    static Player playerOf(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player source) {
            return source;
        }
        return null;
    }

    /**
     * Keeps a command's correlation id current on the main thread from its preprocess event until the
     * next tick (the command executes in between). A later command replaces it.
     */
    static final class CommandCorrelation {
        private static String active;
        private static TelemetryCorrelation.Scope scope;

        private CommandCorrelation() {
        }

        static void begin(String correlationId, Consumer<Runnable> nextTick) {
            end();
            active = correlationId;
            scope = TelemetryCorrelation.open(correlationId);
            nextTick.accept(() -> {
                if (correlationId.equals(active)) {
                    end();
                }
            });
        }

        static void end() {
            if (scope != null) {
                scope.close();
            }
            active = null;
            scope = null;
        }
    }
}
