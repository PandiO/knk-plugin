package net.knightsandkings.knk.paper.telemetry;

import java.util.Collection;
import java.util.Locale;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.telemetry.TelemetryEventNames;

/**
 * Enhanced {@code movement.sample} (KNG-34 link 6, DESIGN.md §F.12): every
 * {@code telemetry.enhanced-movement-sample-seconds} (main thread, it reads locations) one block
 * position per enhanced player. Nobody else is sampled; with no enhanced player this does nothing but
 * one config check per online player.
 */
public final class EnhancedMovementSampler implements Runnable {

    private final TelemetryEmitter emitter;
    private final Supplier<Collection<? extends Player>> online;

    public EnhancedMovementSampler(TelemetryEmitter emitter, Supplier<Collection<? extends Player>> online) {
        this.emitter = emitter;
        this.online = online;
    }

    @Override
    public void run() {
        if (emitter.config().enhancedUserIds().isEmpty() && emitter.config().enhancedTestRunIds().isEmpty()) {
            return;
        }
        for (Player player : online.get()) {
            if (!emitter.isEnhanced(player.getUniqueId())) {
                continue;
            }
            Location at = player.getLocation();
            if (at == null || at.getWorld() == null) {
                continue;
            }
            emitter.event(TelemetryEventNames.MOVEMENT_SAMPLE)
                .player(player.getUniqueId())
                .put("world", at.getWorld().getName())
                .put("x", at.getBlockX())
                .put("y", at.getBlockY())
                .put("z", at.getBlockZ())
                .put("mode", player.isFlying() ? "flying" : player.isInsideVehicle() ? "vehicle"
                    : player.isSwimming() ? "swim" : player.getGameMode().name().toLowerCase(Locale.ROOT))
                .emit();
        }
    }
}
