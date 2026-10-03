package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.events.GateDoorDamageEvent;
import net.knightsandkings.knk.paper.events.GateDoorIgniteEvent;
import net.knightsandkings.knk.paper.gates.GateDamageSink;
import net.knightsandkings.knk.paper.gates.GateFireSystem;
import net.knightsandkings.knk.paper.gates.HealthSystem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Consequence listener for gate door events: owns the damage amount per GateDoorDamageEvent
 * Cause and applies it via HealthSystem (which already no-ops for invincible/destroyed gates),
 * and starts a burn via GateFireSystem for GateDoorIgniteEvent. Kept separate from detection
 * (GateDoorHitService) so future causes/amounts can be tuned here without touching how a hit is
 * detected.
 */
public class GateDamageConsequenceListener implements Listener {
    private static final Map<GateDoorDamageEvent.Cause, Double> DAMAGE_BY_CAUSE = Map.of(
        GateDoorDamageEvent.Cause.LEFT_CLICK, 10.0,
        GateDoorDamageEvent.Cause.PROJECTILE, 10.0,
        GateDoorDamageEvent.Cause.EXPLOSION, 10.0,
        GateDoorDamageEvent.Cause.BLOCK_BREAK, 10.0
    );

    private static final Logger LOGGER = Logger.getLogger(GateDamageConsequenceListener.class.getName());

    private final HealthSystem healthSystem;
    private final GateFireSystem fireSystem;
    private final GateDamageSink damageSink;

    public GateDamageConsequenceListener(HealthSystem healthSystem, GateFireSystem fireSystem) {
        this(healthSystem, fireSystem, GateDamageSink.NONE);
    }

    /** @param damageSink KNG-34 gate-damage statistics: receives the effective loss of each hit, changes nothing */
    public GateDamageConsequenceListener(HealthSystem healthSystem, GateFireSystem fireSystem, GateDamageSink damageSink) {
        this.healthSystem = healthSystem;
        this.fireSystem = fireSystem;
        this.damageSink = damageSink == null ? GateDamageSink.NONE : damageSink;
    }

    @EventHandler
    public void onGateDoorDamage(GateDoorDamageEvent event) {
        if (event.isCancelled()) {
            return;
        }

        double amount = DAMAGE_BY_CAUSE.getOrDefault(event.getCause(), 10.0);
        double loss = healthSystem.applyDamage(event.getGate(), amount);
        if (loss > 0) {
            try {
                damageSink.directDamage(event.getGate(), event.getCausingEntity(), loss);
            } catch (RuntimeException e) {
                LOGGER.warning("Gate damage statistics failed: " + e);
            }
        }
    }

    @EventHandler
    public void onGateDoorIgnite(GateDoorIgniteEvent event) {
        if (event.isCancelled()) {
            return;
        }

        fireSystem.igniteBlock(event.getGate(), event.getHitBlock(), event.getCausingEntity());
    }
}
