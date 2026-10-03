package net.knightsandkings.knk.paper.menu.content;

import java.util.ArrayList;
import java.util.List;

import net.knightsandkings.knk.core.domain.leaderboards.LeaderboardBoard;

/**
 * One board of {@code statistics.leaderboards} (row source {@code statistics.leaderboards.boards}); a
 * click opens {@code statistics.leaderboard} with {@code ctx.board} = {@link #getBoardKey()}.
 */
public final class LeaderboardBoardRow {

    private final LeaderboardBoard board;

    LeaderboardBoardRow(LeaderboardBoard board) {
        this.board = board;
    }

    public String getBoardKey() {
        return board.boardKey();
    }

    public String getMaterial() {
        String metric = board.metric() == null ? "" : board.metric();
        return switch (metric) {
            case "active_playtime" -> "CLOCK";
            case "xp_gained" -> "EXPERIENCE_BOTTLE";
            case "pvp_kills" -> "IRON_SWORD";
            case "pve_kills" -> "ZOMBIE_HEAD";
            case "wins" -> "WHITE_BANNER";
            case "objectives_captured" -> "BEACON";
            case "gate_damage" -> "IRON_DOOR";
            case "distance.foot" -> "LEATHER_BOOTS";
            case "distance.flying" -> "ELYTRA";
            case "distance.vehicle" -> "MINECART";
            case "discoveries" -> "COMPASS";
            case "highest_killstreak" -> "BLAZE_POWDER";
            default -> "PAPER";
        };
    }

    public String getName() {
        return "&f" + board.label();
    }

    public List<String> getLoreLines() {
        List<String> lore = new ArrayList<>();
        lore.add(board.alwaysPublic()
                ? "&7Ranks every player"
                : "&7Ranks players who show this statistic");
        lore.add("&7Weekly, monthly and all time");
        lore.add("");
        lore.add("&eClick: &7open");
        return lore;
    }
}
