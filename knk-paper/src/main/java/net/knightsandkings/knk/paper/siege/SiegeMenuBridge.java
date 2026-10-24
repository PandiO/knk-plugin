package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.siege.ObjectiveState.CaptureEvent;
import net.knightsandkings.knk.core.siege.menu.SiegeMenuIds;
import net.knightsandkings.knk.paper.menu.MenuService;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Siege Phase 8b: connects the runtime to the menu engine once both exist. It opens the siege menus
 * for {@link SiegeService} ({@code /siege}, the spawn picker at match start and respawn - each falls
 * back to chat when the menu isn't available) and repaints open {@code siege.*} menus when a lobby
 * changes (join, leave, vote, phase change) or an objective changes hands, on top of the menus' own
 * {@code AutoRefreshTicks}.
 */
public final class SiegeMenuBridge implements SiegeMatchObserver, SiegeService.MenuHooks {

    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(SiegeMenuBridge.class.getName());

    private final MenuService menus;
    /** Keys whose unavailability was already logged (logged again once they recover and fail anew). */
    private final java.util.Set<String> reported = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public SiegeMenuBridge(MenuService menus) {
        this.menus = menus;
    }

    @Override
    public void lobbyChanged(SiegeLobbyRuntime lobby) {
        refresh();
    }

    @Override
    public void objectiveCaptured(SiegeLobbyRuntime lobby, SiegeMatch match, CaptureEvent capture) {
        refresh();
    }

    @Override
    public boolean openOverview(Player player) {
        return open(player, SiegeMenuIds.MENU_OVERVIEW, MenuContextParams.EMPTY);
    }

    @Override
    public boolean openInformation(Player player, int lobbyId) {
        return open(player, SiegeMenuIds.MENU_INFORMATION,
                MenuContextParams.of(Map.of(SiegeMenuIds.CTX_LOBBY_ID, String.valueOf(lobbyId))));
    }

    @Override
    public boolean openSpawnPicker(Player player) {
        return open(player, SiegeMenuIds.MENU_SPAWNPOINT, MenuContextParams.EMPTY);
    }

    private boolean open(Player player, String key, MenuContextParams context) {
        if (player == null) return false;
        if (!menus.isMenuAvailable(key)) {
            // Playtest 2026-10-24: the chat fallback alone hid why. Say it once per key and have the menu
            // validated now, so a startup hiccup (API not up yet) costs one chat fallback, not a restart.
            boolean validating = menus.requestValidation(key);
            if (reported.add(key)) {
                LOGGER.warning("[Siege] Menu '" + key + "' is unavailable (" + menus.unavailableReason(key).orElse("?")
                        + ")" + (validating ? "; validating it now, the chat fallback is used this time" : "")
                        + ". /knk menu broken lists blocked menus.");
            }
            return false;
        }
        reported.remove(key);
        menus.openMenu(player, key, context);
        return true;
    }

    private void refresh() {
        menus.refreshOpenMenus(ctx -> ctx.menuKey() != null && ctx.menuKey().startsWith(SiegeMenuIds.MENU_PREFIX));
    }
}
