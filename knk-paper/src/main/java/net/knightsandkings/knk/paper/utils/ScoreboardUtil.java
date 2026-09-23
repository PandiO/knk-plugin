package net.knightsandkings.knk.paper.utils;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class ScoreboardUtil {
    private static Scoreboard scoreboard;

    public ScoreboardUtil() {}

    public static Scoreboard getScoreboard() {
        if (scoreboard == null) {
            scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            scoreboard = setTeams(scoreboard);
        }

        return scoreboard;
    }

    public static Scoreboard setTeams(Scoreboard scoreboard) {
        Team def = scoreboard.registerNewTeam("default");
        def.prefix(Component.text("§7"));
        def.color(NamedTextColor.GRAY);

        Team owner = scoreboard.registerNewTeam("owner");
        owner.prefix(Component.text("§5"));
        owner.color(NamedTextColor.DARK_PURPLE);

        Objective health = scoreboard.registerNewObjective("Health", Criteria.HEALTH.getName(), Component.text("❤").color(ColorOptions.error), RenderType.HEARTS);
        health.setDisplaySlot(DisplaySlot.BELOW_NAME);

        return scoreboard;
    }

    public static void setScoreboard(List<Player> players, KnkPermissible knkPermissible) {
        Scoreboard scoreboard = getScoreboard();

        for (Player p : players) {
            Team team = scoreboard.getTeam("default");
            if (knkPermissible.hasPermission(p, "knk.mode.owner")) {
                team = scoreboard.getTeam("owner");
            }

            team.addPlayer(p);
            p.setScoreboard(scoreboard);
            
            // Set tab layout header/footer
            Component header = Component.text("§7Welcome to §9Knights and Kings");
            Component footer = Component.text("§cOpen Beta\n§cFollow us on instagram @knightsandkings.official");
            p.sendPlayerListHeaderAndFooter(header, footer);
        }
    }
}