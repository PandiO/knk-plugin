package net.knightsandkings.knk.paper.user;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.messaging.ParticipantId;
import net.knightsandkings.knk.core.messaging.PrivateMessageNodes;
import net.knightsandkings.knk.core.messaging.SpyRules;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.kyori.adventure.text.Component;

/**
 * Social spy (docs/specs/private-messages/DESIGN.md §3.3.4; developer decision 2026-09-26: staff +
 * owners via {@code knk.socialspy}, owners' PMs hidden from staff via {@code knk.socialspy.exempt}).
 * <p>
 * Replaces v3's first cut, which echoed every PM to Bukkit {@code hasPermission("knk.staffchat")}
 * holders - in practice ops only, because in-house grants never reach Bukkit's permission tree.
 * Here both nodes resolve through {@link KnkPermissible} (op bypass included) on join, on
 * /socialspy and every {@code private-messages.spy.refresh-seconds}, and are cached in concurrent
 * sets, so fanning a message out does no permission I/O. The per-player toggle lives in the
 * player's PDC and defaults to on (v1: staff always saw PMs), so it survives restarts.
 */
public class SpyService {

    private final KnkPermissible knkPermissible;
    private final NamespacedKey toggleKey;
    private final Supplier<Collection<? extends Player>> onlinePlayers;
    private final Set<UUID> audience = ConcurrentHashMap.newKeySet();
    private final Set<UUID> exempt = ConcurrentHashMap.newKeySet();

    public SpyService(KnkPermissible knkPermissible, NamespacedKey toggleKey,
                      Supplier<Collection<? extends Player>> onlinePlayers) {
        this.knkPermissible = Objects.requireNonNull(knkPermissible, "knkPermissible must not be null");
        this.toggleKey = Objects.requireNonNull(toggleKey, "toggleKey must not be null");
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers must not be null");
    }

    /**
     * Re-checks the audience every {@code refreshSeconds} on the main thread, starting a second
     * after enable (covers players already online after a reload).
     */
    public BukkitTask start(Plugin plugin, int refreshSeconds) {
        long period = Math.max(1, refreshSeconds) * 20L;
        return plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshAll, 20L, period);
    }

    public void refreshAll() {
        for (Player player : onlinePlayers.get()) {
            refresh(player);
        }
    }

    /** Re-checks both nodes for one player (async; the cached sets update when the answers arrive). */
    public void refresh(Player player) {
        UUID uuid = player.getUniqueId();
        knkPermissible.hasPermissionAsync(player, PrivateMessageNodes.SOCIAL_SPY)
                .thenAccept(allowed -> update(audience, player, uuid, allowed));
        knkPermissible.hasPermissionAsync(player, PrivateMessageNodes.SOCIAL_SPY_EXEMPT)
                .thenAccept(allowed -> update(exempt, player, uuid, allowed));
    }

    public void forget(UUID uuid) {
        audience.remove(uuid);
        exempt.remove(uuid);
    }

    public boolean isInAudience(UUID uuid) {
        return audience.contains(uuid);
    }

    public boolean isExempt(ParticipantId participant) {
        return !participant.isConsole() && exempt.contains(participant.uuid());
    }

    /** The player's /socialspy toggle (main thread: reads the PDC). Default on. */
    public boolean isEnabled(Player player) {
        Byte value = player.getPersistentDataContainer().get(toggleKey, PersistentDataType.BYTE);
        return value == null || value != 0;
    }

    public void setEnabled(Player player, boolean enabled) {
        player.getPersistentDataContainer().set(toggleKey, PersistentDataType.BYTE, (byte) (enabled ? 1 : 0));
    }

    /** Sends {@code line} to every online spy who may see a PM between these two (main thread). */
    public void broadcast(ParticipantId sender, ParticipantId recipient, Component line) {
        SpyRules.Participant from = new SpyRules.Participant(sender, isExempt(sender));
        SpyRules.Participant to = new SpyRules.Participant(recipient, isExempt(recipient));
        for (Player online : onlinePlayers.get()) {
            UUID uuid = online.getUniqueId();
            if (!audience.contains(uuid)) {
                continue;
            }
            SpyRules.Spy spy = new SpyRules.Spy(ParticipantId.player(uuid), true, isEnabled(online), exempt.contains(uuid));
            if (SpyRules.shouldSee(spy, from, to)) {
                online.sendMessage(line);
            }
        }
    }

    private static void update(Set<UUID> set, Player player, UUID uuid, Boolean allowed) {
        // A late answer for a player who already quit must not re-add them.
        if (Boolean.TRUE.equals(allowed) && player.isOnline()) {
            set.add(uuid);
        } else {
            set.remove(uuid);
        }
    }
}
