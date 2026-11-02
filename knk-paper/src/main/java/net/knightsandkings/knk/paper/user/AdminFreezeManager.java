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
    /**
     * PandiO/knk-workspace#170: who froze a player, for the boss bar, in memory only (the API stores FrozenByUserId
     * but doesn't hand it out). Kept through a quit so a relog on the same server run still names them; dropped on
     * unfreeze. Absent: console, a vanished staff member, or a freeze from before a restart or from the web app.
     */
    private final Map<UUID, Freezer> freezers = new ConcurrentHashMap<>();

    /** The staff member's name and the reason they froze with (a different reason later means someone else froze). */
    private record Freezer(String name, String reason) {
    }

    // PandiO/knk-workspace#155: told when a player's freeze starts (true) and ends (false), so a frozen player held in
    // mid-air gets flight allowed and isn't kicked for flying.
    private volatile java.util.function.BiConsumer<UUID, Boolean> freezeHook = (uuid, frozenNow) -> { };
    // KNG-58: the last freeze the API (or this server) set, for a join while the API is down.
    private volatile net.knightsandkings.knk.core.offline.OfflineSecurityStore offline;

    public void setOfflineStore(net.knightsandkings.knk.core.offline.OfflineSecurityStore offline) {
        this.offline = offline;
    }

    public void setFreezeHook(java.util.function.BiConsumer<UUID, Boolean> freezeHook) {
        this.freezeHook = freezeHook != null ? freezeHook : (uuid, frozenNow) -> { };
    }

    public boolean isFrozen(UUID uuid) {
        return frozen.containsKey(uuid);
    }

    public String reasonFor(UUID uuid) {
        return frozen.get(uuid);
    }

    /** Who froze the player, or null when unknown (console, vanished staff, restart, web app). */
    public String frozenBy(UUID uuid) {
        Freezer freezer = uuid == null ? null : freezers.get(uuid);
        return freezer == null ? null : freezer.name();
    }

    public void freeze(UUID uuid, String reason) {
        freeze(uuid, reason, null);
    }

    /** @param frozenBy the staff member's name for the boss bar, null to show {@code staff} (#170) */
    public void freeze(UUID uuid, String reason, String frozenBy) {
        if (frozenBy != null && !frozenBy.isBlank()) {
            freezers.put(uuid, new Freezer(frozenBy, reason));
        } else {
            freezers.remove(uuid);
        }
        frozen.put(uuid, reason);
        freezeHook.accept(uuid, true);
        if (offline != null) {
            offline.setFrozen(uuid, true, reason);
        }
    }

    /**
     * Notes who froze an offline player (the freeze itself takes effect on their next join), so the join on this
     * server run names them on the boss bar (#170). Null {@code frozenBy} forgets any earlier one.
     */
    public void rememberFreezer(UUID uuid, String reason, String frozenBy) {
        if (uuid == null) {
            return;
        }
        if (frozenBy != null && !frozenBy.isBlank()) {
            freezers.put(uuid, new Freezer(frozenBy, reason));
        } else {
            freezers.remove(uuid);
        }
    }

    public void unfreeze(UUID uuid) {
        frozen.remove(uuid);
        freezers.remove(uuid);
        freezeHook.accept(uuid, false);
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
        // #170: the freezer is only known for a freeze made on this server run with the same reason.
        Freezer freezer = freezers.get(player.getUniqueId());
        if (freezer != null && !java.util.Objects.equals(blankToEmpty(freezer.reason()), blankToEmpty(reason))) {
            freezers.remove(player.getUniqueId());
        }
        frozen.put(player.getUniqueId(), reason != null ? reason : "");
        freezeHook.accept(player.getUniqueId(), true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(org.bukkit.ChatColor.RED + "You are frozen: " + frozen.get(player.getUniqueId()));
            }
        });
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** Quit: the freeze stops holding the player until the next join; who froze them is kept for that join. */
    public void forget(UUID uuid) {
        frozen.remove(uuid);
        freezeHook.accept(uuid, false);
    }
}
