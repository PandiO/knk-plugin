package net.knightsandkings.knk.paper.enchantment;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FrozenPlayerTracker {
    /**
     * Told when a player's freeze starts and ends (PandiO/knk-workspace#155: flight is allowed while frozen, so a player
     * held in mid-air isn't kicked for flying). Creatures are not reported.
     */
    public interface PlayerFreezeHook {
        PlayerFreezeHook NONE = new PlayerFreezeHook() {
            @Override
            public void frozen(UUID playerId) {
            }

            @Override
            public void released(UUID playerId) {
            }
        };

        void frozen(UUID playerId);

        void released(UUID playerId);
    }

    private final Plugin plugin;
    private final Map<UUID, Integer> pendingUnfreezeTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> frozenUntilMillis = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> originalAiStates = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> frozenPlayers = new ConcurrentHashMap<>();
    private volatile PlayerFreezeHook hook = PlayerFreezeHook.NONE;

    public FrozenPlayerTracker(Plugin plugin) {
        this.plugin = plugin;
    }

    public void setPlayerFreezeHook(PlayerFreezeHook hook) {
        this.hook = hook != null ? hook : PlayerFreezeHook.NONE;
    }

    public void freeze(LivingEntity target, int durationTicks) {
        if (target == null || durationTicks <= 0) {
            return;
        }

        UUID targetId = target.getUniqueId();
        frozenUntilMillis.put(targetId, System.currentTimeMillis() + (durationTicks * 50L));

        Integer previousTask = pendingUnfreezeTasks.remove(targetId);
        if (previousTask != null) {
            Bukkit.getScheduler().cancelTask(previousTask);
        }

        if (!(target instanceof Player)) {
            if (!originalAiStates.containsKey(targetId)) {
                try {
                    originalAiStates.put(targetId, target.hasAI());
                    target.setAI(false);
                } catch (UnsupportedOperationException ignored) {
                    originalAiStates.remove(targetId);
                }
            }
            target.setVelocity(new Vector(0.0d, 0.0d, 0.0d));
        } else if (frozenPlayers.put(targetId, Boolean.TRUE) == null) {
            hook.frozen(targetId);
        }

        int[] taskId = new int[1];
        taskId[0] = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            // Only the freeze this task belongs to ends here; a newer one has its own task.
            if (pendingUnfreezeTasks.remove(targetId, taskId[0])) {
                end(targetId);
            }
        }, durationTicks);
        pendingUnfreezeTasks.put(targetId, taskId[0]);
    }

    public void freeze(Player player, int durationTicks) {
        freeze((LivingEntity) player, durationTicks);
    }

    public boolean isFrozen(UUID playerId) {
        if (playerId == null) {
            return false;
        }

        Long frozenUntil = frozenUntilMillis.get(playerId);
        return frozenUntil != null && frozenUntil > System.currentTimeMillis();
    }

    public void unfreeze(UUID playerId) {
        if (playerId == null) {
            return;
        }

        Integer taskId = pendingUnfreezeTasks.remove(playerId);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        end(playerId);
    }

    private void end(UUID entityId) {
        frozenUntilMillis.remove(entityId);
        restoreEntityState(entityId);
        if (frozenPlayers.remove(entityId) != null) {
            hook.released(entityId);
        }
    }

    private void restoreEntityState(UUID entityId) {
        Boolean originalAi = originalAiStates.remove(entityId);
        if (originalAi == null) {
            return;
        }

        Entity entity = Bukkit.getEntity(entityId);
        if (entity instanceof LivingEntity livingEntity && !(livingEntity instanceof Player)) {
            try {
                livingEntity.setAI(originalAi);
            } catch (UnsupportedOperationException ignored) {
                // No AI support on this entity implementation
            }
        }
    }
}
