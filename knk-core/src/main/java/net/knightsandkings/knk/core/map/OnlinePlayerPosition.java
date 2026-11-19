package net.knightsandkings.knk.core.map;

import java.util.UUID;

import net.knightsandkings.knk.core.domain.users.ActiveMode;

/**
 * One online player as the overview map's player report sees it (PandiO/knk-workspace#738, OVERVIEW_MAP_DESIGN § 5 rule 6,
 * § 7.1.2): where they are, whether they are AFK, their active mode and the minigame match they play in.
 *
 * @param minigame the short minigame key of the running match the player plays in ({@code "siege"}), or null outside one
 * @param matchId  the API id of that match, or null (outside a match, or while the API has not answered the start yet)
 */
public record OnlinePlayerPosition(UUID uuid, String world, double x, double y, double z, float yaw, boolean afk,
                                   ActiveMode mode, String minigame, Long matchId) {

    public OnlinePlayerPosition {
        mode = mode == null ? ActiveMode.NONE : mode;
        minigame = minigame == null || minigame.isBlank() ? null : minigame;
    }
}
