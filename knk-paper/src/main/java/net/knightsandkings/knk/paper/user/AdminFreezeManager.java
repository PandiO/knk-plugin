package net.knightsandkings.knk.paper.user;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;

/**
 * In-memory, session-scoped tracker for admin-frozen players (rebuild of v1's FreezeCommands, a
 * dead no-op stub there - see /freeze's own javadoc). Separate from the combat-only
 * FrozenPlayerTracker (a different concern: timed enchantment effect, not a moderation lock).
 * <p>
 * Freeze state is persisted server-side (User.IsFrozen/FrozenReason via PUT /api/users/{id}/freeze)
 * specifically so it can be applied to an offline player; this manager's job is only to make that
 * persisted state take effect once the target is next online, by fetching it fresh at join (not
 * reusing UserManager's separate PlayerUserData/UserCache caches, which don't carry the field -
 * a deliberate, isolated extra lookup rather than threading a new field through two existing
 * cache layers under this round's time pressure).
 */
public class AdminFreezeManager {
    private static final Logger LOGGER = Logger.getLogger(AdminFreezeManager.class.getName());

    private final Map<UUID, String> frozen = new ConcurrentHashMap<>();

    public boolean isFrozen(UUID uuid) {
        return frozen.containsKey(uuid);
    }

    public String reasonFor(UUID uuid) {
        return frozen.get(uuid);
    }

    public void freeze(UUID uuid, String reason) {
        frozen.put(uuid, reason);
    }

    public void unfreeze(UUID uuid) {
        frozen.remove(uuid);
    }

    /** Called on join to make a persisted freeze (possibly applied while offline) take effect. */
    public void restoreOnJoin(Plugin plugin, Player player, UsersDataAccess usersDataAccess) {
        usersDataAccess.getByUsernameAsync(player.getName()).thenAccept(result -> {
            if (result.isSuccess() && result.value().isPresent() && result.value().get().isFrozen()) {
                frozen.put(player.getUniqueId(), result.value().get().frozenReason());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(org.bukkit.ChatColor.RED + "You are frozen: " + frozen.get(player.getUniqueId()));
                    }
                });
            }
        }).exceptionally(ex -> {
            LOGGER.warning("Failed to check frozen state for " + player.getName() + " on join: " + ex.getMessage());
            return null;
        });
    }

    public void forget(UUID uuid) {
        frozen.remove(uuid);
    }
}
