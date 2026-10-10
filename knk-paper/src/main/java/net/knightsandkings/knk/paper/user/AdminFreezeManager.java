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
    // KNG-58: the last freeze the API (or this server) set, for a join while the API is down.
    private volatile net.knightsandkings.knk.core.offline.OfflineSecurityStore offline;

    public void setOfflineStore(net.knightsandkings.knk.core.offline.OfflineSecurityStore offline) {
        this.offline = offline;
    }

    public boolean isFrozen(UUID uuid) {
        return frozen.containsKey(uuid);
    }

    public String reasonFor(UUID uuid) {
        return frozen.get(uuid);
    }

    public void freeze(UUID uuid, String reason) {
        frozen.put(uuid, reason);
        if (offline != null) {
            offline.setFrozen(uuid, true, reason);
        }
    }

    public void unfreeze(UUID uuid) {
        frozen.remove(uuid);
        if (offline != null) {
            offline.setFrozen(uuid, false, null);
        }
    }

    /**
     * Called on join to make a persisted freeze (possibly applied while offline) take effect. When
     * the API can't be asked, the last known freeze is applied (KNG-58) - it used to fail open, so a
     * frozen player who relogged during an outage was free.
     */
    public void restoreOnJoin(Plugin plugin, Player player, UsersDataAccess usersDataAccess) {
        UUID uuid = player.getUniqueId();
        usersDataAccess.getByUsernameAsync(player.getName()).thenAccept(result -> {
            if (result.isSuccess() && result.value().isPresent()) {
                if (result.value().get().isFrozen()) {
                    applyOnJoin(plugin, player, result.value().get().frozenReason());
                }
                return;
            }
            if (result.status() == net.knightsandkings.knk.core.dataaccess.FetchStatus.NOT_FOUND) {
                return;
            }
            LOGGER.warning("Failed to check frozen state for " + player.getName() + " on join; using the last known state");
            applyLastKnown(plugin, player, uuid);
        }).exceptionally(ex -> {
            LOGGER.warning("Failed to check frozen state for " + player.getName() + " on join: " + ex.getMessage()
                + "; using the last known state");
            applyLastKnown(plugin, player, uuid);
            return null;
        });
    }

    private void applyLastKnown(Plugin plugin, Player player, UUID uuid) {
        if (offline == null) {
            return;
        }
        offline.identity(uuid)
            .filter(net.knightsandkings.knk.core.offline.OfflineSecurityStore.Identity::frozen)
            .ifPresent(identity -> applyOnJoin(plugin, player, identity.frozenReason()));
    }

    private void applyOnJoin(Plugin plugin, Player player, String reason) {
        frozen.put(player.getUniqueId(), reason != null ? reason : "");
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(org.bukkit.ChatColor.RED + "You are frozen: " + frozen.get(player.getUniqueId()));
            }
        });
    }

    public void forget(UUID uuid) {
        frozen.remove(uuid);
    }
}
