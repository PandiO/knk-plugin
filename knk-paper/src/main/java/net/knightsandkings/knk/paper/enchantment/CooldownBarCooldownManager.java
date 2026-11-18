package net.knightsandkings.knk.paper.enchantment;

import net.knightsandkings.knk.core.domain.enchantment.Enchantment;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentRegistry;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentType;
import net.knightsandkings.knk.core.ports.enchantment.CooldownManager;
import net.knightsandkings.knk.paper.enchantment.display.EffectBossBars;
import net.knightsandkings.knk.paper.gates.GateCleaving;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The custom-enchantment cooldown store, plus a boss bar with the remaining cooldown for the player who used an ability
 * (PandiO/knk-workspace#202): {@code Flash Chaos cooldown · 1:28}, counting down. Every ability cooldown goes through
 * here (the right-click abilities and the on-hit enchantments in {@link ExecutorImpl}, Cleaving's charged strike in
 * {@link GateCleaving}), so the lengths are exactly what was applied: the #151 combat values (Cleaving's strike: #259).
 * <p>
 * Bars are shown for the abilities in {@link #labels}: every SUPPORT ability, Freeze's per-attacker cooldown and
 * Cleaving's charged strike. The other on-hit enchantments (Poison, Wither, ...) keep their cooldown without a bar.
 * {@code /ce cooldown clear} ({@link #clearCooldowns}) removes the bars; they stay through death, go on quit and come
 * back on join while the cooldown still runs. The bars themselves live in {@link EffectBossBars}, next to the debuff
 * bars and the navigation bar.
 */
public final class CooldownBarCooldownManager implements CooldownManager, Listener {

    private final CooldownManager delegate;
    private final EffectBossBars bars;
    private final Map<String, String> labels;
    private final Executor mainThread;

    /**
     * @param labels     ability id to bar label; only these get a bar
     * @param mainThread runs the bar updates on the server thread (a cooldown can be applied from an async task)
     */
    public CooldownBarCooldownManager(CooldownManager delegate, EffectBossBars bars, Map<String, String> labels,
                                      Executor mainThread) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.bars = Objects.requireNonNull(bars, "bars must not be null");
        this.labels = labels == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(labels));
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread must not be null");
        bars.cooldownsFrom((playerId, abilityId) -> delegate.getRemainingCooldown(playerId, abilityId).join());
    }

    /**
     * The abilities with a cooldown bar and their labels: every SUPPORT ability of the registry ({@code Chaos cooldown}),
     * Freeze ({@code Freeze cooldown}) and Cleaving's charged strike ({@code Cleaving strike cooldown}).
     */
    public static Map<String, String> defaultLabels(EnchantmentRegistry registry) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (Enchantment enchantment : registry.getAll()) {
            if (enchantment.type() == EnchantmentType.SUPPORT) {
                labels.put(enchantment.id(), enchantment.displayName() + " cooldown");
            }
        }
        registry.getById("freeze").ifPresent(e -> labels.put(e.id(), e.displayName() + " cooldown"));
        registry.getById(GateCleaving.CLEAVING_ID).ifPresent(e -> labels.put(e.id(), e.displayName() + " strike cooldown"));
        return labels;
    }

    public Map<String, String> labels() {
        return labels;
    }

    @Override
    public CompletableFuture<Long> getRemainingCooldown(UUID playerId, String enchantmentId) {
        return delegate.getRemainingCooldown(playerId, enchantmentId);
    }

    @Override
    public CompletableFuture<Void> applyCooldown(UUID playerId, String enchantmentId, long durationMs) {
        return delegate.applyCooldown(playerId, enchantmentId, durationMs).thenRun(() -> {
            String label = enchantmentId == null ? null : labels.get(enchantmentId);
            if (label != null && durationMs > 0L) {
                onMainThread(() -> bars.cooldown(playerId, enchantmentId, label, durationMs));
            }
        });
    }

    @Override
    public CompletableFuture<Void> clearCooldowns(UUID playerId) {
        return delegate.clearCooldowns(playerId).thenRun(() -> onMainThread(() -> bars.clearCooldowns(playerId)));
    }

    /** A cooldown that is still running when the player comes back shows its bar again, full at the time left. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        showRunning(event.getPlayer().getUniqueId());
    }

    void showRunning(UUID playerId) {
        for (Map.Entry<String, String> e : labels.entrySet()) {
            long remaining = delegate.getRemainingCooldown(playerId, e.getKey()).join();
            if (remaining > 0L) {
                bars.cooldown(playerId, e.getKey(), e.getValue(), remaining);
            }
        }
    }

    private void onMainThread(Runnable task) {
        try {
            mainThread.execute(task);
        } catch (RuntimeException ignored) {
            // Plugin disabling: no bars to update any more.
        }
    }
}
