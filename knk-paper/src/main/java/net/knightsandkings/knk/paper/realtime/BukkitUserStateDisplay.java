package net.knightsandkings.knk.paper.realtime;

import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.paper.modes.ModeService;
import net.knightsandkings.knk.paper.user.SpyService;
import org.bukkit.entity.Player;

import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * {@link UserStateSync.Display} on the live server (PandiO/knk-workspace#179): the same calls the in-game commands make
 * after a group, node or mode change (UserAdminService, ModeListener). Main thread only.
 */
public final class BukkitUserStateDisplay implements UserStateSync.Display {

    private final BiConsumer<Player, UserSummary> tabList;
    private final ModeService modes;
    private final SpyService spy;
    private final Logger logger;

    /**
     * @param tabList re-renders a player's tab-list team and footer (ScoreboardUtil, KNG-7)
     * @param modes   null when modes aren't wired
     * @param spy     null when private messaging isn't wired
     */
    public BukkitUserStateDisplay(BiConsumer<Player, UserSummary> tabList, ModeService modes, SpyService spy, Logger logger) {
        this.tabList = tabList;
        this.modes = modes;
        this.spy = spy;
        this.logger = logger;
    }

    @Override
    public void redraw(Player player, UserSummary fresh) {
        tabList.accept(player, fresh);
    }

    @Override
    public void permissionsApplied(Player player) {
        if (modes != null) {
            // e.g. knk.mode.staff granted: they see vanished staff at once (as UserAdminService does in game).
            modes.refreshVisibilityFor(player);
            ActiveMode current = modes.getActiveMode(player);
            if (current != ActiveMode.NONE) {
                // The node of the mode they are in was taken away: drop the mode, as a join would (ModeListener).
                modes.whenModePermissionKnown(player, current, decision -> {
                    if (decision == PermissionDecision.DENIED && player.isOnline() && modes.getActiveMode(player) == current) {
                        logger.info("Realtime: " + player.getName() + " no longer holds " + ModeService.nodeFor(current)
                            + "; leaving " + current);
                        modes.applyMode(player, ActiveMode.NONE, true);
                        modes.persist(player, ActiveMode.NONE);
                    }
                });
            }
        }
        if (spy != null) {
            spy.refresh(player);
        }
        player.updateCommands(); // KNG-107: the "/" list follows the change
    }

    @Override
    public void applyMode(Player player, ActiveMode mode) {
        if (modes == null || mode == null || modes.getActiveMode(player) == mode) {
            return; // already in it: an echo of a change made here, or nothing to do
        }
        if (mode == ActiveMode.NONE) {
            modes.applyMode(player, ActiveMode.NONE, true);
            return;
        }
        modes.whenModePermissionKnown(player, mode, decision -> {
            if (!player.isOnline()) {
                return;
            }
            if (decision == PermissionDecision.DENIED) {
                // Set for a player who can't use it: don't hide them, and put the stored mode back (as a join would).
                logger.info("Realtime: " + player.getName() + " was set to " + mode + " but doesn't hold "
                    + ModeService.nodeFor(mode) + "; staying visible");
                modes.persist(player, ActiveMode.NONE);
                return;
            }
            // ALLOWED, or UNAVAILABLE (API down, no last answer): hiding is the safe direction (KNG-58).
            modes.applyMode(player, mode, true);
        });
    }
}
