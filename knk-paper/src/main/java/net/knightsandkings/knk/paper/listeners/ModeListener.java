package net.knightsandkings.knk.paper.listeners;

import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.paper.modes.ModeService;
import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.kyori.adventure.text.Component;

/**
 * Restores a player's persisted owner/staff mode on login (docs/specs/user-features/
 * IMPLEMENTATION_PLAN.md §3) instead of defaulting everyone visible after a restart the way v1
 * did, and keeps vanished players' join/leave messages suppressed (v1 behavior).
 * <p>
 * Runs at HIGHEST so it sees - and can null - the join/quit messages {@link PlayerListener}
 * sets at NORMAL. The persisted mode is read from the user cache, which
 * {@link PlayerListener#onValidateLogin} populates at pre-login and {@link ModeService#persist}
 * keeps current.
 */
public class ModeListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(ModeListener.class.getName());

    private final ModeService modeService;

    public ModeListener(ModeService modeService) {
        this.modeService = Objects.requireNonNull(modeService, "modeService must not be null");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();

        // Hide anyone already vanished from the joining player (revealed again if they can see
        // vanished players).
        modeService.refreshVisibilityFor(player);

        ActiveMode persisted = modeService.getPersistedMode(player);
        if (!persisted.isVanished()) {
            // Also shows this player again to viewers who still had them hidden from a previous
            // vanished session (Bukkit keeps a viewer's hidden set across the target relogging).
            modeService.applyMode(player, ActiveMode.NONE, false);
            return;
        }

        // Vanish immediately so there's no window where other players see them, then confirm
        // they still hold the node - a grant revoked while they were offline drops them back to
        // visible rather than silently keeping a mode they can no longer toggle off-and-on.
        e.joinMessage(null);
        modeService.applyMode(player, persisted, false);
        modeService.whenHasModePermission(player, persisted, allowed -> {
            if (!player.isOnline() || modeService.getActiveMode(player) != persisted) {
                return;
            }
            String name = ModeService.displayName(persisted);
            if (allowed) {
                player.sendActionBar(Component.text("Restored " + name + " - you are hidden from other players.")
                    .color(ColorOptions.messageachievement));
                return;
            }
            LOGGER.info("Clearing persisted " + persisted + " for " + player.getName() + " - no longer holds " + ModeService.nodeFor(persisted));
            modeService.applyMode(player, ActiveMode.NONE, false);
            modeService.persist(player, ActiveMode.NONE);
            player.sendMessage(Component.text("You no longer have permission for " + name + ", so it has been disabled.")
                .color(ColorOptions.falsecommand));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        if (modeService.isVanished(player)) {
            e.quitMessage(null);
        }
        // Nothing to persist here: every in-session change was already persisted when it was
        // made, and a pending "onquit" change was persisted up front.
        modeService.forget(player);
    }
}
